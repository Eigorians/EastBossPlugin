package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonFireField
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonFireballs
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * 急降下。高く昇って標的に狙いを定め、翼をたたんで一直線に突っ込む。
 *
 *   上昇（climb-ticks まで）  … 地面から climb-height の高さへ昇る
 *   狙う（aim-ticks）         … 標的の足元に輪が出て追いかける。最後の lock-ticks は赤くなって止まる（ここで横へ逃げる）
 *   降下（dive-max-ticks まで）… 決まった所へ dive-speed で突っ込む。通り道の相手にも当たる（hit-radius）
 *   着地                      … 地面すれすれ（bottom-height）で衝撃（impact-radius）。周りが燃える
 *   引き起こし（pull-ticks）   … 勢いのまま低く滑空して体を起こす
 *   立て直す（recover-ticks） … 低く浮いたまま息を整える（殴りどき。受けるダメージ × recover-damage-multiplier）
 *
 * repeats が 2 以上なら、引き起こしの後にすぐまた昇って、もう一度突っ込む（最後だけ立て直す）
 */
class DiveSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val climbHeight: Double,
        val climbTicks: Int,
        val climbSpeed: Double,
        val climbAnimation: String,
        val aimTicks: Int,
        val lockTicks: Int,
        val aimAnimation: String,
        val diveSpeed: Double,
        val diveMaxTicks: Int,
        val diveAnimation: String,
        val bottomHeight: Double,
        val hitRadius: Double,
        val damage: Double,
        val impactRadius: Double,
        val impactDamage: Double,
        val knockback: Double,
        val pullAnimation: String,
        val pullTicks: Int,
        val recoverAnimation: String,
        val recoverTicks: Int,
        val recoverHeight: Double,
        val recoverDamageMultiplier: Double,
        val repeats: Int,
        val burn: DragonFireField.Burn,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 28.0),
                climbHeight = section.double(Names.SkillConfig.CLIMB_HEIGHT, 14.0),
                climbTicks = section.int(Names.SkillConfig.CLIMB_TICKS, 40),
                climbSpeed = section.double(Names.SkillConfig.APPROACH_SPEED, 0.7),
                climbAnimation = section.string(Names.SkillConfig.CLIMB_ANIMATION, "dive_climb"),
                aimTicks = section.int(Names.SkillConfig.AIM_TICKS, 30).coerceAtLeast(1),
                lockTicks = section.int(Names.SkillConfig.LOCK_TICKS, 10),
                aimAnimation = section.string(Names.SkillConfig.AIM_ANIMATION, "dive_aim"),
                diveSpeed = section.double(Names.SkillConfig.DIVE_SPEED, 1.5).coerceAtLeast(0.2),
                diveMaxTicks = section.int(Names.SkillConfig.DIVE_MAX_TICKS, 40),
                diveAnimation = section.string(Names.SkillConfig.DIVE_ANIMATION, "dive"),
                bottomHeight = section.double(Names.SkillConfig.BOTTOM_HEIGHT, 1.0),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 2.6),
                damage = section.double(Names.SkillConfig.DAMAGE, 9.0),
                impactRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 4.5),
                impactDamage = section.double(Names.SkillConfig.IMPACT_DAMAGE, 7.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 1.1),
                pullAnimation = section.string(Names.SkillConfig.PULL_ANIMATION, "dive_pull"),
                pullTicks = section.int(Names.SkillConfig.PULL_TICKS, 16),
                recoverAnimation = section.string(Names.SkillConfig.RECOVER_ANIMATION, "recover"),
                recoverTicks = section.int(Names.SkillConfig.RECOVER_TICKS, 36),
                recoverHeight = section.double(Names.SkillConfig.RECOVER_HEIGHT, 1.3),
                recoverDamageMultiplier = section.double(Names.SkillConfig.RECOVER_DAMAGE_MULTIPLIER, 1.3),
                repeats = section.int(Names.SkillConfig.REPEATS, 1).coerceAtLeast(1),
                burn = DragonFireballs.Spec.burnOf(section),
            )
        }
    }

    private enum class Phase { CLIMB, AIM, DIVE, PULL, RECOVER }

    override val weight: Int get() = settings.weight

    private var phase = Phase.CLIMB
    private var tick = 0
    private var round = 0
    private var target: LivingEntity? = null
    /** 狙う地面の点（lock の後は動かない） */
    private var mark: Location? = null
    private var locked = false
    private var diveDir = Vector(0, -1, 0)
    private val hit = HashSet<UUID>()
    /** 今流している繰り返しアニメ */
    private var loop: String? = null

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val d = boss as DragonBoss
        round = 0
        target = d.findTarget(settings.range)
        d.setBaseAnimation(DragonBoss.HOVER)
        enter(d, Phase.CLIMB)
    }

    override fun tick(boss: Boss): Boolean {
        val d = boss as DragonBoss
        val t = target?.takeIf { TargetFinder.isValidTarget(d, it) }
            ?: d.findTarget(settings.range)?.also { target = it }
        when (phase) {
            Phase.CLIMB -> {
                // 標的から少し離れながら昇る（真上から落ちると見えにくいので、斜めに突っ込む）
                val pos = d.entity.location
                val away = t?.let { Facing.horizontalDir(it.location.toVector(), pos.toVector()) } ?: Vector()
                val dest = d.flight.above(pos.x + away.x * 3, pos.z + away.z * 3, settings.climbHeight, pos.y - d.flight.altitude())
                t?.let { d.flight.turnToward(it.location, 8f) }
                d.flight.steer(dest, settings.climbSpeed, accel = 0.1, arriveRadius = 2.0)
                if (tick % 12 == 0) DragonSounds.flap(d.entity, heavy = true)
                if (tick >= settings.climbTicks || d.flight.altitude() >= settings.climbHeight - 1.0) return enter(d, Phase.AIM)
            }
            Phase.AIM -> {
                d.flight.brake()
                val lockAt = (settings.aimTicks - settings.lockTicks).coerceAtLeast(0)
                if (!locked && t != null) {
                    mark = d.groundAt(t.location.x, t.location.z, t.location.y)
                    d.flight.turnToward(t.location, 10f)
                }
                if (tick >= lockAt && !locked) {
                    locked = true
                    DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_GROWL, 4.0f, 1.2f)
                    DragonSounds.play(d.entity, Sound.ENTITY_PHANTOM_SWOOP, 3.0f, 0.6f)
                }
                mark?.let { if (tick % 2 == 0) DragonFx.ring(it, settings.impactRadius, if (locked) DragonFx.MARK_LOCKED else DragonFx.MARK) }
                if (tick >= settings.aimTicks) return enter(d, Phase.DIVE)
            }
            Phase.DIVE -> {
                if (diveStep(d)) return enter(d, Phase.PULL)
            }
            Phase.PULL -> {
                // 勢いのまま低く滑空して、だんだん止まる
                val f = tick.toDouble() / settings.pullTicks.coerceAtLeast(1)
                val horiz = diveDir.clone().setY(0).let { if (it.lengthSquared() < 1e-4) d.flight.forward() else it.normalize() }
                val speed = 0.9 * (1 - f)
                val alt = d.flight.altitude()
                val vy = ((settings.recoverHeight - alt) * 0.3).coerceIn(-0.4, 0.4)
                d.flight.moveWith(Vector(horiz.x * speed, vy, horiz.z * speed))
                if (tick >= settings.pullTicks) return enter(d, if (round < settings.repeats) Phase.CLIMB else Phase.RECOVER)
            }
            Phase.RECOVER -> {
                val pos = d.entity.location
                val dest = d.flight.above(pos.x, pos.z, settings.recoverHeight, pos.y - d.flight.altitude())
                t?.let { d.flight.turnToward(it.location, 3f) }
                d.flight.steer(dest, 0.2, accel = 0.05, arriveRadius = 1.0)
                if (tick % 6 == 0) d.mouthPoint()?.let { DragonFx.nostrilSmoke(it, heavy = true) }
                if (tick >= settings.recoverTicks) return true
            }
        }
        tick++
        return false
    }

    override fun stop(boss: Boss) {
        val d = boss as DragonBoss
        loop?.let { d.stopAnimation(it) }
        loop = null
        target = null
        mark = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (phase == Phase.RECOVER || phase == Phase.PULL) event.damage *= settings.recoverDamageMultiplier
    }

    private fun enter(d: DragonBoss, p: Phase): Boolean {
        phase = p
        tick = 0
        when (p) {
            Phase.CLIMB -> {
                round++
                locked = false
                mark = null
                hit.clear()
                setLoop(d, settings.climbAnimation)
                DragonSounds.roar(d.entity, 1.0f)
            }
            Phase.AIM -> setLoop(d, settings.aimAnimation)
            Phase.DIVE -> {
                setLoop(d, settings.diveAnimation)
                DragonSounds.play(d.entity, Sound.ITEM_ELYTRA_FLYING, 3.0f, 1.2f)
                DragonSounds.play(d.entity, Sound.ENTITY_PHANTOM_SWOOP, 3.0f, 0.5f)
            }
            Phase.PULL -> {
                setLoop(d, null)
                d.playOnce(settings.pullAnimation)
            }
            Phase.RECOVER -> setLoop(d, settings.recoverAnimation)
        }
        return false
    }

    private fun setLoop(d: DragonBoss, name: String?) {
        loop?.let { d.stopAnimation(it) }
        loop = name
        if (name != null) d.playLoop(name, lerpTicks = 3)
    }

    /** 1tick 突っ込む。地面に着いたら（ぶつかったら）衝撃を出して true */
    private fun diveStep(d: DragonBoss): Boolean {
        val m = mark ?: d.groundAt(d.entity.location.x, d.entity.location.z).also { mark = it }
        val goal = m.toVector().add(Vector(0.0, settings.bottomHeight, 0.0))
        val to = goal.clone().subtract(d.flight.position)
        val dist = to.length()
        if (dist > 1e-3) diveDir = to.clone().normalize()
        d.flight.turnToward(diveDir, 20f)

        val close = dist <= settings.diveSpeed
        val moved = if (close) d.flight.moveWith(to) else d.flight.moveWith(diveDir.clone().multiply(settings.diveSpeed))
        // 着いた・時間切れ（届かない所を狙っていた）
        val arrived = close || tick >= settings.diveMaxTicks

        // 通り道の相手
        val center = d.entity.location.add(0.0, d.entity.height * 0.5, 0.0)
        val r = settings.hitRadius
        for (e in center.world.getNearbyEntities(center, r, r, r)) {
            if (!TargetFinder.isValidTarget(d, e) || !hit.add(e.uniqueId)) continue
            val victim = e as LivingEntity
            victim.noDamageTicks = 0
            victim.damage(settings.damage, d.entity)
            victim.velocity = diveDir.clone().setY(0).multiply(settings.knockback).setY(0.6)
        }
        // 風を切る跡
        val world = center.world
        world.spawnParticle(Particle.CLOUD, center, 3, 0.8, 0.8, 0.8, 0.02)
        if (d.isRaging) world.spawnParticle(Particle.FLAME, center, 4, 0.8, 0.8, 0.8, 0.02)

        if (arrived || !moved) {
            impact(d)
            return true
        }
        return false
    }

    private fun impact(d: DragonBoss) {
        val pos = d.entity.location
        val ground = d.groundAt(pos.x, pos.z)
        val world = ground.world
        DragonFx.groundShock(ground, settings.impactRadius)
        DragonSounds.play(ground, Sound.ENTITY_GENERIC_EXPLODE, 3.0f, 0.7f)
        DragonSounds.play(ground, Sound.BLOCK_ANVIL_LAND, 2.0f, 0.5f)
        DragonSounds.roar(d.entity, 1.1f)

        val r = settings.impactRadius
        for (e in world.getNearbyEntities(ground, r, 3.0, r)) {
            if (!TargetFinder.isValidTarget(d, e) || !hit.add(e.uniqueId)) continue
            val dx = e.location.x - ground.x
            val dz = e.location.z - ground.z
            if (dx * dx + dz * dz > r * r) continue
            val victim = e as LivingEntity
            victim.noDamageTicks = 0
            victim.damage(settings.impactDamage, d.entity)
            val away = Vector(dx, 0.0, dz).let { if (it.lengthSquared() < 1e-4) diveDir.clone().setY(0) else it.normalize() }
            victim.velocity = away.multiply(settings.knockback).setY(0.7)
        }
        // 着地点のまわりが燃える
        if (settings.burn.enabled) {
            for (i in 0 until 6) {
                val a = Math.PI * 2 * i / 6
                val p = d.groundAt(ground.x + cos(a) * r * 0.6, ground.z + sin(a) * r * 0.6, ground.y)
                d.fireField.ignite(p, settings.burn)
            }
        }
    }
}
