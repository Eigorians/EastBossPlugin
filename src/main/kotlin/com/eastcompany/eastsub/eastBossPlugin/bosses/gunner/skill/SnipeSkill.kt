package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerFx
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import kotlin.math.acos
import kotlin.math.min

/**
 * 狙撃。背中のライフルを抜き、レーザーサイトで狙って、貫通する一撃を撃つ（shots 回）。
 *
 *   抜く（drawTicks。swapTick で持ち替え）
 *   → [狙う（aimTicks。レーザーが標的を追う。追う速さは turnSpeed 度/tick まで）
 *      → 固定（lockTicks。レーザーが白く点滅し、もう動かない）→ 撃つ（fireAnimationTicks）] × shots
 *   → しまう（stowTicks。swapTick で持ち替え）
 *
 * 弾はレーザーの線の上を一瞬で飛び、線に重なった相手全員に当たる。ブロックで止まるので、
 * 固定された線から横へ外れるか、壁の陰に隠れればかわせる。
 * 狙っている間は受けるダメージが aimDamageMultiplier 倍（近づいて殴るのも有効）。
 */
class SnipeSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val minRange: Double,
        val drawAnimation: String,
        val drawTicks: Int,
        val swapTick: Int,
        val aimAnimation: String,
        val aimTicks: Int,
        /** 2発目以降の狙う時間 */
        val nextAimTicks: Int,
        val lockTicks: Int,
        val fireAnimation: String,
        val fireAnimationTicks: Int,
        val stowAnimation: String,
        val stowTicks: Int,
        val shots: Int,
        /** レーザーが標的を追う速さ（度/tick） */
        val turnSpeed: Double,
        val damage: Double,
        val maxRange: Double,
        /** 弾道の当たり判定の太さ（相手の当たり判定をこれだけ広げる） */
        val hitWidth: Double,
        val knockback: Double,
        val aimDamageMultiplier: Double,
        /** 盾を貫くか（ジャストガードも、ふつうのガードも効かない） */
        val pierceShield: Boolean,
    ) {
        /** 技の流れ（何 tick 目に何をするか）。効果音もこれに合わせる */
        val timeline: Timeline get() = Timeline.of(this)

        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 32.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 8.0),
                drawAnimation = section.string(Names.SkillConfig.DRAW_ANIMATION, "snipe_draw"),
                drawTicks = section.int(Names.SkillConfig.DRAW_TICKS, 12),
                swapTick = section.int(Names.SkillConfig.SWAP_TICK, 6),
                aimAnimation = section.string(Names.SkillConfig.AIM_ANIMATION, "snipe_aim"),
                aimTicks = section.int(Names.SkillConfig.AIM_TICKS, 40),
                nextAimTicks = section.int(Names.SkillConfig.NEXT_AIM_TICKS, 24),
                lockTicks = section.int(Names.SkillConfig.LOCK_TICKS, 12),
                fireAnimation = section.string(Names.SkillConfig.FIRE_ANIMATION, "snipe_fire"),
                fireAnimationTicks = section.int(Names.SkillConfig.FIRE_ANIMATION_TICKS, 16),
                stowAnimation = section.string(Names.SkillConfig.STOW_ANIMATION, "snipe_stow"),
                stowTicks = section.int(Names.SkillConfig.STOW_TICKS, 12),
                shots = section.int(Names.SkillConfig.SHOTS, 1).coerceAtLeast(1),
                turnSpeed = section.double(Names.SkillConfig.TURN_SPEED, 4.0),
                damage = section.double(Names.SkillConfig.DAMAGE, 9.0),
                maxRange = section.double(Names.SkillConfig.MAX_RANGE, 48.0),
                hitWidth = section.double(Names.SkillConfig.HIT_WIDTH, 0.35),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 1.2),
                pierceShield = section.boolean(Names.SkillConfig.PIERCE_SHIELD, true),
                aimDamageMultiplier = section.double(Names.SkillConfig.AIM_DAMAGE_MULTIPLIER, 1.3),
            )
        }
    }

    /** 1発分の区間（tick） */
    data class Shot(val aimStart: Int, val lockStart: Int, val fireTick: Int, val fireEnd: Int)

    class Timeline(val shots: List<Shot>, val stowStart: Int, val end: Int) {
        companion object {
            fun of(s: Settings): Timeline {
                var t = s.drawTicks
                val list = mutableListOf<Shot>()
                for (i in 0 until s.shots) {
                    val aim = if (i == 0) s.aimTicks else s.nextAimTicks
                    val lock = t + aim
                    val fire = lock + s.lockTicks
                    list += Shot(t, lock, fire, fire + s.fireAnimationTicks)
                    t = fire + s.fireAnimationTicks
                }
                return Timeline(list, t, t + s.stowTicks)
            }
        }
    }

    override val weight: Int get() = settings.weight

    private val timeline = settings.timeline
    private var tick = 0
    private var target: LivingEntity? = null
    private var aimDir: Vector = Vector(0, 0, 1)
    /** 狙っている（構えている）間か。被ダメージ倍率に使う */
    private var aiming = false

    override fun canStart(boss: Boss): Boolean =
        (boss as GunnerBoss).findTarget(settings.minRange, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        aiming = false
        target = (boss as GunnerBoss).findTarget(settings.minRange, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        aimDir = Facing.forward(boss.entity)
        boss.playOnce(settings.drawAnimation)
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GunnerBoss
        val s = settings

        // 抜く
        if (tick < s.drawTicks) {
            Facing.faceTarget(boss, target)
            if (tick == s.swapTick) g.equip(GunnerBoss.Weapon.RIFLE)
        }

        val shot = timeline.shots.firstOrNull { tick >= it.aimStart && tick < it.fireEnd }
        if (shot != null) {
            aiming = true
            when {
                tick == timeline.shots.first().aimStart -> {
                    g.playLoop(s.aimAnimation, lerpTicks = 2)
                    aimDir = g.aimDir(g.muzzle(GunnerBoss.Muzzle.RIFLE), target)
                    track(g)
                }
                tick < shot.lockStart -> track(g)
                tick < shot.fireTick -> drawLock(g)
                tick == shot.fireTick -> fire(g)
            }
        }

        // しまう
        if (tick == timeline.stowStart) {
            aiming = false
            g.stopAnimation(s.aimAnimation)
            g.playOnce(s.stowAnimation)
        }
        if (tick == timeline.stowStart + s.swapTick) g.equip(GunnerBoss.Weapon.PISTOLS)

        tick++
        return tick >= timeline.end
    }

    /** レーザーを標的へ少しずつ向ける（体ごと回す） */
    private fun track(g: GunnerBoss) {
        val from = g.muzzle(GunnerBoss.Muzzle.RIFLE)
        val t = target?.takeIf { TargetFinder.isValidTarget(g, it) }
        if (t != null) {
            val want = g.aimDir(from, t)
            aimDir = rotateToward(aimDir, want, Math.toRadians(settings.turnSpeed))
        }
        Facing.faceYaw(g.entity, Facing.yawOf(aimDir.clone().setY(0)))
        GunnerFx.laser(from, aimDir, settings.maxRange, GunnerFx.LASER, step = 0.3)
    }

    /** 狙いを固定した後: 白と赤で点滅 */
    private fun drawLock(g: GunnerBoss) {
        val from = g.muzzle(GunnerBoss.Muzzle.RIFLE)
        val dust = if ((tick / 2) % 2 == 0) GunnerFx.LASER_LOCK else GunnerFx.LASER
        GunnerFx.laser(from, aimDir, settings.maxRange, dust, step = 0.25)
    }

    /** 撃つ: レーザーの線の上の相手全員に当たる。ブロックで止まる */
    private fun fire(g: GunnerBoss) {
        val s = settings
        g.playOnce(s.fireAnimation)
        val from = g.muzzle(GunnerBoss.Muzzle.RIFLE)
        val end = GunnerFx.blockEnd(from, aimDir, s.maxRange)
        val length = from.distance(end)

        // 水平の向き（真下・真上を撃ったときは押さない）
        val flat = aimDir.clone().setY(0).let { if (it.lengthSquared() < 1e-4) Vector(0, 0, 0) else it.normalize() }
        val area = BoundingBox.of(from.toVector(), end.toVector()).expand(s.hitWidth + 1.0)
        for (e in from.world.getNearbyEntities(area)) {
            if (!canHit(g, e)) continue
            val box = e.boundingBox.clone().expand(s.hitWidth)
            if (box.rayTrace(from.toVector(), aimDir, length) == null && !box.contains(from.toVector())) continue
            val victim = e as LivingEntity
            victim.noDamageTicks = 0
            if (s.pierceShield) {
                // 盾を貫く: 構えていた盾ごと撃ち抜く（ジャストガードも効かない）
                val wasGuarding = victim is Player && victim.isHandRaised
                g.unblockable(victim) { victim.damage(s.damage, g.entity) }
                if (wasGuarding) GunnerFx.shieldPierced(victim)
            } else {
                victim.damage(s.damage, g.entity)
            }
            victim.velocity = flat.clone().multiply(s.knockback).setY(0.35)
        }

        GunnerFx.muzzleFlash(from, aimDir, big = true)
        GunnerFx.sniperBeam(from, end)
        // 反動で少し後ろへ下がる
        val back = g.entity.location.add(flat.clone().multiply(-0.4))
        if (back.block.isPassable) g.entity.teleport(back)
    }

    private fun canHit(g: GunnerBoss, e: Entity): Boolean {
        if (!TargetFinder.isValidTarget(g, e)) return false
        val battle = g.battle
        return battle == null || battle.isParticipant(e)
    }

    override fun stop(boss: Boss) {
        aiming = false
        boss.stopAnimation(settings.aimAnimation)
        (boss as GunnerBoss).equip(GunnerBoss.Weapon.PISTOLS)
        boss.entity.setAI(true)
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (aiming) event.damage *= settings.aimDamageMultiplier
    }

    private companion object {
        /** a を b へ、最大 maxRad ラジアンだけ回す（どちらも単位ベクトル） */
        fun rotateToward(a: Vector, b: Vector, maxRad: Double): Vector {
            val dot = a.dot(b).coerceIn(-1.0, 1.0)
            val angle = acos(dot)
            if (angle <= maxRad || angle < 1e-6) return b.clone()
            val f = min(1.0, maxRad / angle)
            // 球面線形補間
            val s = Math.sin(angle)
            val wa = Math.sin((1 - f) * angle) / s
            val wb = Math.sin(f * angle) / s
            return a.clone().multiply(wa).add(b.clone().multiply(wb)).normalize()
        }
    }
}
