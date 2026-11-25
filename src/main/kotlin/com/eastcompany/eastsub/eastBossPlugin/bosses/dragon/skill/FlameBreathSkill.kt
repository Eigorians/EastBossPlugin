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
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.tan
import kotlin.random.Random

/**
 * 火炎ブレス。標的の手前に低めに浮いて、首を振りながら前方の地面を炎で焼き払う。
 *
 *   接近（approach-max-ticks まで）… 標的から hover-distance 離れた、高さ hover-height の所へ飛ぶ
 *   溜め（windup-ticks）            … 首を高く上げて息を吸う。のどと口の中が光り、口から火がこぼれる
 *   ブレス（breath-ticks × sweeps）  … 首を振って、sweep-degrees の幅を左右に焼き払う（sweeps 回往復）
 *   終わり（end-ticks）             … 口から煙を吐いて首を振る（隙）
 *
 * - 炎は口から地面へ向かう円すい（cone-degrees）。ブロックで止まるので、物陰に隠れればかわせる
 * - 炎が地面に当たった所は、しばらく燃える（burn-*）
 * - 炎は前にしか出ないので、ドラゴンの腹の下や後ろは安全（低めに浮いているので、もぐり込めば殴れる）
 */
class FlameBreathSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val hoverHeight: Double,
        val hoverDistance: Double,
        val approachSpeed: Double,
        val approachMaxTicks: Int,
        val startAnimation: String,
        val windupTicks: Int,
        val loopAnimation: String,
        val breathTicks: Int,
        val sweeps: Int,
        val sweepDegrees: Double,
        val coneDegrees: Double,
        val length: Double,
        val damage: Double,
        val hitIntervalTicks: Int,
        val fireTicks: Int,
        val endAnimation: String,
        val endTicks: Int,
        val burn: DragonFireField.Burn,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 3),
                range = section.double(Names.SkillConfig.RANGE, 26.0),
                hoverHeight = section.double(Names.SkillConfig.HOVER_HEIGHT, 3.0),
                hoverDistance = section.double(Names.SkillConfig.HOVER_DISTANCE, 9.0),
                approachSpeed = section.double(Names.SkillConfig.APPROACH_SPEED, 0.65),
                approachMaxTicks = section.int(Names.SkillConfig.APPROACH_MAX_TICKS, 40),
                startAnimation = section.string(Names.SkillConfig.START_ANIMATION, "breath_start"),
                windupTicks = section.int(Names.SkillConfig.WINDUP_TICKS, 24).coerceAtLeast(1),
                loopAnimation = section.string(Names.SkillConfig.LOOP_ANIMATION, "breath_loop"),
                breathTicks = section.int(Names.SkillConfig.BREATH_TICKS, 50).coerceAtLeast(1),
                sweeps = section.int(Names.SkillConfig.SWEEPS, 1).coerceAtLeast(1),
                sweepDegrees = section.double(Names.SkillConfig.SWEEP_DEGREES, 80.0),
                coneDegrees = section.double(Names.SkillConfig.CONE_DEGREES, 12.0).coerceIn(1.0, 45.0),
                length = section.double(Names.SkillConfig.LENGTH, 16.0),
                damage = section.double(Names.SkillConfig.DAMAGE, 4.0),
                hitIntervalTicks = section.int(Names.SkillConfig.HIT_INTERVAL_TICKS, 10).coerceAtLeast(1),
                fireTicks = section.int(Names.SkillConfig.FIRE_TICKS, 80),
                endAnimation = section.string(Names.SkillConfig.END_ANIMATION, "breath_end"),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 20),
                burn = DragonFireballs.Spec.burnOf(section),
            )
        }
    }

    private enum class Phase { APPROACH, WINDUP, BREATH, END }

    override val weight: Int get() = settings.weight

    private var phase = Phase.APPROACH
    private var tick = 0
    private var target: LivingEntity? = null
    /** 焼き払いの中心の向き（溜めの終わりに標的の方へ決める）と、口から地面の狙いまでの水平の距離 */
    private var centerYaw = 0f
    private var aimDistance = 8.0
    /** 最初に振る向き（+1 / -1） */
    private var sweepSign = 1
    private val lastHit = HashMap<UUID, Int>()
    private var breathAge = 0

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val d = boss as DragonBoss
        phase = Phase.APPROACH
        tick = 0
        breathAge = 0
        lastHit.clear()
        target = d.findTarget(settings.range)
        sweepSign = if (Random.nextBoolean()) 1 else -1
        d.setBaseAnimation(DragonBoss.HOVER)
        DragonSounds.growl(d.entity, 0.7f)
    }

    override fun tick(boss: Boss): Boolean {
        val d = boss as DragonBoss
        val t = target?.takeIf { TargetFinder.isValidTarget(d, it) }
        when (phase) {
            Phase.APPROACH -> {
                if (t == null || tick >= settings.approachMaxTicks) return next(d, Phase.WINDUP)
                val dest = d.hoverPointNear(t, settings.hoverDistance, settings.hoverHeight)
                d.flight.turnToward(t.location, 8f)
                d.flight.steer(dest, settings.approachSpeed, accel = 0.08, arriveRadius = 3.0)
                if (dest.distanceSquared(d.flight.position) < 1.5 * 1.5) return next(d, Phase.WINDUP)
            }
            Phase.WINDUP -> {
                t?.let { d.flight.turnToward(it.location, 6f) }
                d.flight.brake()
                d.mouthPoint()?.let { DragonFx.mawGlow(it) }
                if (tick % 6 == 0) DragonSounds.play(d.entity, Sound.ENTITY_BLAZE_BURN, 2.5f, 0.5f + tick * 0.02f)
                if (tick >= settings.windupTicks) return next(d, Phase.BREATH)
            }
            Phase.BREATH -> {
                breathe(d)
                if (tick >= settings.breathTicks * settings.sweeps) return next(d, Phase.END)
            }
            Phase.END -> {
                d.flight.brake()
                if (tick % 4 == 0) d.mouthPoint()?.let { DragonFx.nostrilSmoke(it, heavy = true) }
                if (tick >= settings.endTicks) return true
            }
        }
        tick++
        return false
    }

    override fun stop(boss: Boss) {
        val d = boss as DragonBoss
        d.stopAnimation(settings.loopAnimation)
        d.showFire(false)
        target = null
    }

    private fun next(d: DragonBoss, p: Phase): Boolean {
        phase = p
        tick = 0
        when (p) {
            Phase.WINDUP -> {
                d.playOnce(settings.startAnimation)
                d.showFire(true)
                DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 4.0f, 0.55f)
            }
            Phase.BREATH -> {
                // 焼き払いの中心と、どこまで先の地面を焼くかを決める
                val t = target?.takeIf { TargetFinder.isValidTarget(d, it) }
                val pos = d.entity.location
                if (t != null) {
                    Facing.horizontalDir(pos.toVector(), t.location.toVector())?.let { centerYaw = Facing.yawOf(it) }
                    aimDistance = Math.hypot(t.location.x - pos.x, t.location.z - pos.z)
                } else {
                    centerYaw = d.flight.yaw
                    aimDistance = settings.hoverDistance
                }
                aimDistance = aimDistance.coerceIn(4.0, settings.length - 2.0)
                d.playLoop(settings.loopAnimation, lerpTicks = 3)
                DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_SHOOT, 4.0f, 0.5f)
                DragonSounds.play(d.entity, Sound.ENTITY_BLAZE_SHOOT, 4.0f, 0.4f)
            }
            Phase.END -> {
                d.stopAnimation(settings.loopAnimation)
                d.playOnce(settings.endAnimation)
                d.showFire(false)
                DragonSounds.play(d.entity, Sound.BLOCK_FIRE_EXTINGUISH, 2.5f, 0.6f)
            }
            Phase.APPROACH -> {}
        }
        return false
    }

    // ===== 炎 =====

    private fun breathe(d: DragonBoss) {
        // 首を振る: sweeps 回、左右へ往復する（なめらかに折り返す）
        val sweepIndex = (tick / settings.breathTicks).coerceAtMost(settings.sweeps - 1)
        val f = (tick % settings.breathTicks).toDouble() / settings.breathTicks
        val eased = f * f * (3 - 2 * f)
        val sign = if (sweepIndex % 2 == 0) sweepSign else -sweepSign
        val yawOffset = sign * settings.sweepDegrees * (eased - 0.5)
        d.flight.yaw = (centerYaw + yawOffset).toFloat()
        d.flight.brake()
        breathAge++

        val mouth = d.mouthOrFront()
        val world = mouth.world
        // 狙う地面: ドラゴンの真下から、今の向きへ aimDistance 先
        val fwd = d.flight.forward()
        val pos = d.entity.location
        val aim = d.groundAt(pos.x + fwd.x * aimDistance, pos.z + fwd.z * aimDistance, pos.y)
        val dir = aim.toVector().subtract(mouth.toVector())
        if (dir.lengthSquared() < 1e-4) return
        dir.normalize()

        // 炎の届く所（ブロックで止まる）
        val hit = world.rayTraceBlocks(mouth, dir, settings.length, FluidCollisionMode.ALWAYS, true)
        val reach = hit?.hitPosition?.distance(mouth.toVector()) ?: settings.length
        drawFlame(mouth, dir, reach)

        // 地面に当たった所は燃える
        if (hit != null) {
            val end = hit.hitPosition.toLocation(world)
            world.spawnParticle(Particle.FLAME, end, 6, 0.6, 0.2, 0.6, 0.06)
            world.spawnParticle(Particle.LARGE_SMOKE, end, 2, 0.6, 0.3, 0.6, 0.02)
            if (breathAge % 5 == 0) d.fireField.ignite(end.clone().add(0.0, 0.05, 0.0), settings.burn)
        }
        if (breathAge % 5 == 0) DragonSounds.play(mouth, Sound.ITEM_FIRECHARGE_USE, 2.5f, 0.5f + Random.nextFloat() * 0.2f)
        if (breathAge % 10 == 0) DragonSounds.play(mouth, Sound.BLOCK_FIRE_AMBIENT, 3.0f, 0.7f)

        hitTargets(d, mouth, dir, reach)
    }

    private fun drawFlame(mouth: Location, dir: Vector, reach: Double) {
        val world = mouth.world
        val spreadPer = tan(Math.toRadians(settings.coneDegrees))
        var dist = 0.4
        while (dist < reach) {
            val p = mouth.clone().add(dir.clone().multiply(dist))
            val spread = 0.15 + dist * spreadPer * 0.55
            world.spawnParticle(Particle.FLAME, p, 2, spread, spread, spread, 0.02)
            if (dist > 3 && Random.nextInt(3) == 0) world.spawnParticle(Particle.SMALL_FLAME, p, 1, spread, spread, spread, 0.02)
            if (dist > reach * 0.6 && Random.nextInt(4) == 0) world.spawnParticle(Particle.LARGE_SMOKE, p, 1, spread, spread, spread, 0.01)
            dist += 0.7
        }
        // 口元の勢い
        world.spawnParticle(Particle.FLAME, mouth, 0, dir.x, dir.y, dir.z, 0.6)
        world.spawnParticle(Particle.FLAME, mouth, 0, dir.x, dir.y, dir.z, 0.45)
    }

    /** 炎の円すいの中にいて、口から見えている相手に当てる（同じ相手には hit-interval-ticks ごと） */
    private fun hitTargets(d: DragonBoss, mouth: Location, dir: Vector, reach: Double) {
        val world = mouth.world
        val spreadPer = tan(Math.toRadians(settings.coneDegrees))
        val r = reach + 1.5
        for (e in world.getNearbyEntities(mouth, r, r, r)) {
            if (!TargetFinder.isValidTarget(d, e)) continue
            val victim = e as LivingEntity
            val last = lastHit[victim.uniqueId]
            if (last != null && breathAge - last < settings.hitIntervalTicks) continue

            val chest = victim.location.add(0.0, victim.height * 0.5, 0.0)
            val v = chest.toVector().subtract(mouth.toVector())
            val along = v.dot(dir)
            if (along < 0 || along > reach + 1.0) continue
            val perp = v.clone().subtract(dir.clone().multiply(along)).length()
            val radius = 0.5 + along * spreadPer + victim.width * 0.5 + victim.height * 0.35
            if (perp > radius) continue
            // 物陰に隠れていれば当たらない
            val blocked = world.rayTraceBlocks(mouth, v.clone().normalize(), along.coerceAtLeast(0.1), FluidCollisionMode.NEVER, true)
            if (blocked != null) continue

            lastHit[victim.uniqueId] = breathAge
            victim.noDamageTicks = 0
            victim.damage(settings.damage, d.entity)
            if (settings.fireTicks > 0) victim.fireTicks = maxOf(victim.fireTicks, settings.fireTicks)
        }
    }
}
