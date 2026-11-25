package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.hypot

/**
 * 溜め刺し: 大剣を肩の高さで引き絞って長く溜め、地を蹴って長く踏み込んで突く。
 * 刺さった相手（いちばん先に当たった1人）は刃に刺さったまま（毎tick 刃の blade-point の所へ固定）、
 * 高く掲げて振り回し、前へ投げ飛ばす。投げられた相手は落下ダメージを受けない（status.fall）。
 * 突きをジャストガードすれば刺さらない（ダメージも無し）。
 *
 *   0 〜 thrustTick       … 溜め（標的の方を向く。charge-windows の間、刃に赤い光が集まる）
 *   lungeWindows          … 長く踏み込む（lungeStopDistance まで）
 *   hitWindows            … 刃の線に触れた相手にダメージ。最初の1人が刺さる
 *   刺さったら swingStartTick に、振り回すアニメ（swingAnimation）へ移る
 *   swing の throwTick    … 前へ投げる（throwSpeed・throwUp）
 *   swingTicks / endTicks … 終わり（刺さらなかったら endTicks まで突いたままの隙）
 */
class KnightImpaleSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val minRange: Double,
        val thrustTick: Int,
        val chargeWindows: List<IntRange>,
        val lungeWindows: List<IntRange>,
        val lungeSpeed: Double,
        val lungeStopDistance: Double,
        val hitWindows: List<IntRange>,
        val hitRadius: Double,
        val damage: Double,
        val endTicks: Int,
        val swingAnimation: String,
        val swingStartTick: Int,
        val swingTicks: Int,
        val throwTick: Int,
        val throwSpeed: Double,
        val throwUp: Double,
        val throwDamage: Double,
        val holdDamage: Double,
        val holdDamageInterval: Int,
        val bladePoint: Double,
        val fallProtectTicks: Int,
        /** この技の最中（溜め・突いた後の隙）に受けるダメージの倍率 */
        val takenDamageMultiplier: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION, "impale"),
                range = section.double(Names.SkillConfig.RANGE, 16.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 0.0),
                thrustTick = section.int(Names.SkillConfig.THRUST_TICK, 30),
                chargeWindows = section.intRanges(Names.SkillConfig.CHARGE_WINDOWS),
                lungeWindows = section.intRanges(Names.SkillConfig.LUNGE_WINDOWS),
                lungeSpeed = section.double(Names.SkillConfig.LUNGE_SPEED, 1.2),
                lungeStopDistance = section.double(Names.SkillConfig.LUNGE_STOP_DISTANCE, 3.0),
                hitWindows = section.intRanges(Names.SkillConfig.HIT_WINDOWS),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 1.2),
                damage = section.double(Names.SkillConfig.DAMAGE, 8.0),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 52),
                swingAnimation = section.string(Names.SkillConfig.SWING_ANIMATION, "impale_swing"),
                swingStartTick = section.int(Names.SkillConfig.SWING_START_TICK, 36),
                swingTicks = section.int(Names.SkillConfig.SWING_TICKS, 40),
                throwTick = section.int(Names.SkillConfig.THROW_TICK, 29),
                throwSpeed = section.double(Names.SkillConfig.THROW_SPEED, 2.4),
                throwUp = section.double(Names.SkillConfig.THROW_UP, 0.9),
                throwDamage = section.double(Names.SkillConfig.THROW_DAMAGE, 6.0),
                holdDamage = section.double(Names.SkillConfig.HOLD_DAMAGE, 1.0),
                holdDamageInterval = section.int(Names.SkillConfig.HOLD_DAMAGE_INTERVAL, 8).coerceAtLeast(1),
                bladePoint = section.double(Names.SkillConfig.BLADE_POINT, 0.72).coerceIn(0.0, 1.0),
                fallProtectTicks = section.int(Names.SkillConfig.FALL_PROTECT_TICKS, 200),
                takenDamageMultiplier = section.double(Names.SkillConfig.TAKEN_DAMAGE_MULTIPLIER, 1.0),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private val hit = HashSet<UUID>()

    /** 刺さっている相手 */
    private var impaled: LivingEntity? = null

    /** 振り回し始めてからの tick（-1 = まだ） */
    private var swingTick = -1

    override val soundTick: Int? get() = if (swingTick >= 0) -1 else tick

    override fun canStart(boss: Boss): Boolean {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return false
        return hypot(t.location.x - boss.entity.location.x, t.location.z - boss.entity.location.z) >= settings.minRange
    }

    override fun start(boss: Boss) {
        tick = 0
        swingTick = -1
        impaled = null
        hit.clear()
        target = TargetFinder.findNearest(boss, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val k = boss as KnightBoss
        if (swingTick >= 0) return swing(k)

        if (tick < settings.thrustTick) Facing.faceTarget(boss, target)
        val charge = settings.chargeWindows.firstOrNull()
        if (charge != null && tick in charge) {
            KnightFx.charge(boss.entity, (tick - charge.first + 1).toDouble() / (charge.last - charge.first + 1))
        }
        if (settings.lungeWindows.any { tick in it }) lunge(k)
        if (settings.hitWindows.any { tick in it }) stab(k)
        impaled?.let { hold(k, it) }

        tick++
        if (impaled != null && tick >= settings.swingStartTick) {
            swingTick = 0
            boss.playOnce(settings.swingAnimation)
            KnightSounds.impaleLift(boss.entity)
            return false
        }
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        // 途中で止められたら、刺さっている相手をその場で放す
        impaled?.let { release(boss, it) }
        impaled = null
        boss.resumeEntityAi()
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        event.damage *= settings.takenDamageMultiplier
    }

    // ===== 突き =====

    private fun lunge(boss: KnightBoss) {
        val entity = boss.entity
        val from = entity.location
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) }
        if (impaled == null && t != null && hypot(t.location.x - from.x, t.location.z - from.z) <= settings.lungeStopDistance) return
        val to = Ground.step(from.clone().add(Facing.forward(entity).multiply(settings.lungeSpeed))) ?: return
        to.yaw = from.yaw
        to.pitch = 0f
        entity.teleport(to)
        if (tick % 2 == 0) KnightFx.dashDust(from)
    }

    /** 刃（柄 → 切っ先）の線に触れた相手を突く。最初の1人が刺さる（ジャストガードされたら刺さらない） */
    private fun stab(boss: KnightBoss) {
        val (grip, tip) = boss.bladeSegment() ?: return
        val seg = tip.clone().subtract(grip)
        val len2 = seg.lengthSquared()
        if (len2 < 1e-6) return
        val mid = grip.clone().add(seg.clone().multiply(0.5))
        val world = boss.entity.world
        val r = Math.sqrt(len2) / 2 + settings.hitRadius + 1.0
        for (e in world.getNearbyEntities(mid.toLocation(world), r, r, r)) {
            if (e !is LivingEntity || e.uniqueId in hit || !TargetFinder.isValidTarget(boss, e)) continue
            val c = e.location.toVector().add(Vector(0.0, e.height * 0.5, 0.0))
            val f = (c.clone().subtract(grip).dot(seg) / len2).coerceIn(0.0, 1.0)
            val d = c.clone().subtract(grip.clone().add(seg.clone().multiply(f))).length()
            if (d > settings.hitRadius + e.width / 2) continue
            hit += e.uniqueId
            e.noDamageTicks = 0
            e.damage(settings.damage, boss.entity)
            val guarded = e is Player && boss.status.justGuard.guardedNow(e)
            if (!guarded && impaled == null && !e.isDead) {
                impaled = e
                if (e is Player) boss.status.fall.protect(e, settings.fallProtectTicks)
                KnightFx.impale(c.toLocation(world))
                KnightSounds.impale(e)
            }
        }
    }

    /** 刺さった相手を刃の bladePoint の所へ留める */
    private fun hold(boss: KnightBoss, e: LivingEntity) {
        if (!e.isValid || e.isDead) {
            impaled = null
            return
        }
        val p = boss.bladePoint(settings.bladePoint) ?: return
        val keep = e.location.clone()
        keep.x = p.x
        keep.y = p.y - e.height * 0.5
        keep.z = p.z
        e.teleport(keep)
        e.velocity = Vector(0, 0, 0)
        e.fallDistance = 0f
    }

    // ===== 振り回して投げる =====

    private fun swing(boss: KnightBoss): Boolean {
        val e = impaled
        if (e != null) {
            if (!e.isValid || e.isDead || !TargetFinder.isValidTarget(boss, e)) {
                release(boss, e)
                impaled = null
            } else if (swingTick < settings.throwTick) {
                hold(boss, e)
                if (swingTick > 0 && swingTick % settings.holdDamageInterval == 0) {
                    e.noDamageTicks = 0
                    e.damage(settings.holdDamage, boss.entity)
                }
                if (swingTick % 2 == 0) KnightFx.impaleTrail(e.location.add(0.0, e.height * 0.5, 0.0))
            } else {
                throwAway(boss, e)
                impaled = null
            }
        }
        swingTick++
        return swingTick >= settings.swingTicks
    }

    private fun throwAway(boss: KnightBoss, e: LivingEntity) {
        e.noDamageTicks = 0
        e.damage(settings.throwDamage, boss.entity)
        if (e is Player) boss.status.fall.protect(e, settings.fallProtectTicks)
        val dir = Facing.forward(boss.entity)
        e.velocity = dir.multiply(settings.throwSpeed).setY(settings.throwUp)
        KnightFx.throwBurst(e.location.add(0.0, e.height * 0.5, 0.0))
        KnightSounds.throwAway(boss.entity)
    }

    /** 投げずに放す（技を止められたとき・相手が狙えなくなったとき） */
    private fun release(boss: Boss, e: LivingEntity) {
        if (e is Player) boss.status.fall.protect(e, settings.fallProtectTicks)
        e.velocity = Vector(0.0, 0.2, 0.0)
    }
}
