package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerFx
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.abs

/**
 * ガトリング掃射（本気になってからの大技）。
 *
 *   出す（startTicks。swapTick で持ち替え。最後の 10tick は銃口から赤いレーザー）
 *   → 撃ち続ける（fireDurationTicks。shootIntervalTicks ごとに1発）
 *   → 焼き付き（overheatTicks）→ 冷却中（cooldownTicks。銃身が赤熱し、肩で息をする。殴りどき）
 *   → しまう（endAnimationTicks。endSwapTick で拳銃に持ち替え）
 *
 * 撃っている間、向きを変える速さには限りがある（turnSpeed 度/tick）。ただし照準から外れている間は
 * turnAcceleration ずつ速くなり（最大 maxTurnSpeed）、標的の動きを leadRatio だけ見越して狙う。
 * 同じ向きに回り続けるだけでは逃げ切れない。切り返す・壁の陰に隠れる（弾はブロックで止まる）のがかわし方。
 *
 * - 制圧射撃: 弾に当たると hitSlowTicks の間、鈍足になる（捕まると抜けにくい）
 * - 狙い直し: 標的が見えない状態が retargetTicks 続くと、見えているほかの相手に照準を移す（照準に近い人から）
 *
 * 焼き付き〜冷却中は受けるダメージが overheatDamageMultiplier 倍（殴りどき）。
 */
class GatlingSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val minRange: Double,
        val startAnimation: String,
        val startTicks: Int,
        val swapTick: Int,
        val fireAnimation: String,
        val fireDurationTicks: Int,
        val shootIntervalTicks: Int,
        val turnSpeed: Double,
        /** 照準から外れている間、1tick ごとに旋回が速くなる量（度/tick） */
        val turnAcceleration: Double,
        /** 旋回の速さの上限（度/tick） */
        val maxTurnSpeed: Double,
        /** 標的の動きをどれだけ見越して狙うか（0 なら今いる所） */
        val leadRatio: Double,
        /** 当たった相手を鈍足にする時間（0 以下でしない）と強さ（0 = 鈍足I） */
        val hitSlowTicks: Int,
        val hitSlowAmplifier: Int,
        /** 標的が見えない状態がこの tick 続くと、ほかの相手に照準を移す（0 以下で移さない） */
        val retargetTicks: Int,
        val overheatAnimation: String,
        val overheatTicks: Int,
        val cooldownAnimation: String,
        val cooldownTicks: Int,
        val endAnimation: String,
        val endAnimationTicks: Int,
        val endSwapTick: Int,
        val overheatDamageMultiplier: Double,
        val bullet: BulletField.Spec,
    ) {
        val fireStart: Int get() = startTicks
        val fireEnd: Int get() = startTicks + fireDurationTicks
        val cooldownStart: Int get() = fireEnd + overheatTicks
        val endStart: Int get() = cooldownStart + cooldownTicks
        val end: Int get() = endStart + endAnimationTicks

        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 24.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 0.0),
                startAnimation = section.string(Names.SkillConfig.START_ANIMATION, "gatling_start"),
                startTicks = section.int(Names.SkillConfig.START_TICKS, 28),
                swapTick = section.int(Names.SkillConfig.SWAP_TICK, 6),
                fireAnimation = section.string(Names.SkillConfig.FIRE_ANIMATION, "gatling_fire"),
                fireDurationTicks = section.int(Names.SkillConfig.FIRE_DURATION_TICKS, 80),
                shootIntervalTicks = section.int(Names.SkillConfig.SHOOT_INTERVAL_TICKS, 2).coerceAtLeast(1),
                turnSpeed = section.double(Names.SkillConfig.TURN_SPEED, 1.6),
                turnAcceleration = section.double(Names.SkillConfig.TURN_ACCELERATION, 0.0).coerceAtLeast(0.0),
                maxTurnSpeed = section.double(Names.SkillConfig.MAX_TURN_SPEED, 4.0),
                leadRatio = section.double(Names.SkillConfig.LEAD_RATIO, 0.0),
                hitSlowTicks = section.int(Names.SkillConfig.HIT_SLOW_TICKS, 0),
                hitSlowAmplifier = section.int(Names.SkillConfig.HIT_SLOW_AMPLIFIER, 0).coerceAtLeast(0),
                retargetTicks = section.int(Names.SkillConfig.RETARGET_TICKS, 0),
                overheatAnimation = section.string(Names.SkillConfig.OVERHEAT_ANIMATION, "gatling_overheat"),
                overheatTicks = section.int(Names.SkillConfig.OVERHEAT_TICKS, 20),
                cooldownAnimation = section.string(Names.SkillConfig.COOLDOWN_ANIMATION, "gatling_cooldown"),
                cooldownTicks = section.int(Names.SkillConfig.COOLDOWN_TICKS, 80),
                endAnimation = section.string(Names.SkillConfig.END_ANIMATION, "gatling_end"),
                endAnimationTicks = section.int(Names.SkillConfig.END_ANIMATION_TICKS, 20),
                endSwapTick = section.int(Names.SkillConfig.END_SWAP_TICK, 10),
                overheatDamageMultiplier = section.double(Names.SkillConfig.OVERHEAT_DAMAGE_MULTIPLIER, 1.6),
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var yaw = 0f
    private var target: LivingEntity? = null

    /** 照準から外れている tick（旋回が速くなる） */
    private var lagTicks = 0
    /** 標的の前の tick の位置と、そこから求めた速さ（ブロック/tick。プレイヤーの速さはサーバーで取れないので自分で測る） */
    private var lastTargetPos: Vector? = null
    private var targetVelocity = Vector()
    /** 標的が見えない状態が続いている tick */
    private var hiddenTicks = 0

    override fun canStart(boss: Boss): Boolean =
        (boss as GunnerBoss).findTarget(settings.minRange, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        lagTicks = 0
        hiddenTicks = 0
        lastTargetPos = null
        targetVelocity = Vector()
        target = (boss as GunnerBoss).findTarget(settings.minRange, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        yaw = boss.entity.location.yaw
        boss.playOnce(settings.startAnimation)
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GunnerBoss
        val s = settings

        when {
            // 出す
            tick < s.fireStart -> {
                if (tick == s.swapTick) {
                    g.equip(GunnerBoss.Weapon.GATLING)
                    val at = g.entity.location.add(0.0, g.entity.height * 0.7, 0.0)
                    at.world.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.4, 0.4, 0.4, 0.02)
                }
                turn(g)
                if (tick >= s.fireStart - 10 && tick % 2 == 0) {
                    val from = g.muzzle(GunnerBoss.Muzzle.GATLING)
                    GunnerFx.laser(from, fireDir(g, from), 30.0, GunnerFx.LASER)
                }
            }
            // 撃ち続ける
            tick < s.fireEnd -> {
                if (tick == s.fireStart) g.playLoop(s.fireAnimation, lerpTicks = 1)
                retarget(g)
                turn(g)
                if ((tick - s.fireStart) % s.shootIntervalTicks == 0) fire(g)
            }
            // 焼き付き
            tick < s.cooldownStart -> {
                if (tick == s.fireEnd) {
                    g.stopAnimation(s.fireAnimation)
                    g.playOnce(s.overheatAnimation)
                    g.setPartVisible("heat", true)
                }
                steam(g, 2)
            }
            // 冷却中（殴りどき）
            tick < s.endStart -> {
                if (tick == s.cooldownStart) g.playLoop(s.cooldownAnimation, lerpTicks = 2)
                steam(g, 4)
            }
            // しまう
            else -> {
                if (tick == s.endStart) {
                    g.stopAnimation(s.cooldownAnimation)
                    g.playOnce(s.endAnimation)
                }
                if (tick == s.endStart + s.endSwapTick) {
                    g.equip(GunnerBoss.Weapon.PISTOLS)
                    val at = g.entity.location.add(0.0, g.entity.height * 0.7, 0.0)
                    at.world.spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.4, 0.4, 0.4, 0.02)
                }
            }
        }

        tick++
        return tick >= s.end
    }

    /**
     * 標的（の動きを見越した所）の方へ向きを変える。速さは turnSpeed 度/tick から始まり、
     * 照準から外れている間は turnAcceleration ずつ速くなる（maxTurnSpeed まで）。照準に戻ると元へ戻っていく
     */
    private fun turn(g: GunnerBoss) {
        val t = target?.takeIf { TargetFinder.isValidTarget(g, it) }
        if (t != null) {
            trackVelocity(t)
            val want = Facing.horizontalDir(g.entity.location.toVector(), leadPoint(g, t))?.let { Facing.yawOf(it) }
            if (want != null) {
                var diff = (want - yaw) % 360f
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                lagTicks = if (abs(diff) > LAG_DEGREES) lagTicks + 1 else (lagTicks - 3).coerceAtLeast(0)
                val speed = (settings.turnSpeed + settings.turnAcceleration * lagTicks)
                    .coerceAtMost(maxOf(settings.maxTurnSpeed, settings.turnSpeed)).toFloat()
                yaw += diff.coerceIn(-speed, speed)
            }
        }
        Facing.faceYaw(g.entity, yaw)
    }

    /** 標的の速さを、前の tick からの移動で測る（少しなめらかにする） */
    private fun trackVelocity(t: LivingEntity) {
        val now = t.location.toVector()
        val last = lastTargetPos
        lastTargetPos = now
        if (last == null) return
        val v = now.clone().subtract(last).setY(0)
        // テレポートなどの大きな飛びは無視する
        if (v.lengthSquared() > 4.0) return
        targetVelocity = targetVelocity.multiply(0.6).add(v.multiply(0.4))
    }

    /** 標的の動きを leadRatio だけ見越した所（弾が届くまでの時間ぶん先） */
    private fun leadPoint(g: GunnerBoss, t: LivingEntity): Vector {
        val at = t.location.toVector()
        if (settings.leadRatio <= 0) return at
        val flight = at.distance(g.entity.location.toVector()) / settings.bullet.speed
        return at.add(targetVelocity.clone().multiply(flight * settings.leadRatio))
    }

    /**
     * 標的が見えない（壁の陰・いなくなった）状態が retargetTicks 続いたら、見えているほかの相手に照準を移す。
     * 今の照準の向きに近い人を選ぶ（いきなり真後ろへは振り向かない）
     */
    private fun retarget(g: GunnerBoss) {
        if (settings.retargetTicks <= 0) return
        val t = target?.takeIf { TargetFinder.isValidTarget(g, it) }
        hiddenTicks = if (t != null && g.entity.hasLineOfSight(t)) 0 else hiddenTicks + 1
        if (t != null && hiddenTicks < settings.retargetTicks) return
        val aim = Facing.dirOfYaw(yaw)
        val next = g.entity.getNearbyEntities(settings.range, settings.range, settings.range)
            .filterIsInstance<LivingEntity>()
            .filter { it !== t && TargetFinder.isTargetable(g, it) && g.entity.hasLineOfSight(it) }
            .maxByOrNull { Facing.horizontalDir(g.entity, it)?.dot(aim) ?: -2.0 }
            ?: return
        target = next
        hiddenTicks = 0
        lastTargetPos = null
        targetVelocity = Vector()
    }

    /** 撃つ向き: 左右は今の向き、上下だけ標的に合わせる */
    private fun fireDir(g: GunnerBoss, from: Location): Vector {
        val flat = Facing.dirOfYaw(yaw)
        val t = target?.takeIf { TargetFinder.isValidTarget(g, it) } ?: return flat
        val v = g.aimPoint(t).toVector().subtract(from.toVector())
        val horizontal = Math.hypot(v.x, v.z).coerceAtLeast(1.0)
        return flat.multiply(horizontal).setY(v.y).normalize()
    }

    private fun fire(g: GunnerBoss) {
        val from = g.muzzle(GunnerBoss.Muzzle.GATLING)
        val dir = fireDir(g, from)
        g.bullets.fire(from, dir, settings.bullet, ::suppress)
        GunnerFx.muzzleFlash(from, dir, big = (tick % 4 == 0))
        // 薬莢は右へ
        val right = Vector(-dir.z, 0.0, dir.x).multiply(-1)
        GunnerFx.casing(g.entity.location.add(0.0, g.entity.height * 0.45, 0.0).add(Facing.forward(g.entity).multiply(0.5)), right)
    }

    /** 制圧射撃: 当たった相手を少しの間、鈍足にする */
    private fun suppress(victim: LivingEntity) {
        if (settings.hitSlowTicks <= 0) return
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, settings.hitSlowTicks, settings.hitSlowAmplifier, false, false, true))
    }

    /** 銃口から湯気 */
    private fun steam(g: GunnerBoss, every: Int) {
        if (tick % every != 0) return
        val at = g.muzzle(GunnerBoss.Muzzle.GATLING)
        at.world.spawnParticle(Particle.WHITE_SMOKE, at, 3, 0.1, 0.1, 0.1, 0.02)
        at.world.spawnParticle(Particle.CLOUD, at.clone().add(0.0, 0.2, 0.0), 1, 0.05, 0.05, 0.05, 0.02)
    }

    override fun stop(boss: Boss) {
        boss.stopAnimation(settings.fireAnimation)
        boss.stopAnimation(settings.cooldownAnimation)
        (boss as GunnerBoss).equip(GunnerBoss.Weapon.PISTOLS)
        boss.entity.setAI(true)
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (tick >= settings.fireEnd && tick < settings.endStart) event.damage *= settings.overheatDamageMultiplier
    }

    private companion object {
        /** 照準がこの角度（度）より外れていたら、追いすがる（旋回が速くなっていく） */
        const val LAG_DEGREES = 8f
    }
}
