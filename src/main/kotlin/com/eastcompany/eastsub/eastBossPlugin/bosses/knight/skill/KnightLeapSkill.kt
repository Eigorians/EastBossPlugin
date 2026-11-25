package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightFx
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

/**
 * 跳躍斬り: 大剣を振りかぶって高く跳び、標的の所へ叩きつける（離れた相手への詰め寄り）。
 *
 *   0 〜 jumpStart   … 屈んで溜める。着地点の予告の輪が標的を追う
 *   jumpStart 〜 land … 放物線を描いて跳ぶ。着地点は land の lockTicks 前まで標的を追い、そこで固まる（輪が明るくなる）
 *   land             … 叩きつけ（impactRadius 以内にダメージと吹き飛ばし）。onLand で地割れなどを足せる
 *   〜 endTicks       … 大剣が地面に刺さったまま（殴りどき）
 *
 * アニメ（animation）は技の開始から流し、land で大剣が地面に当たるように作ってある（slam を流用）。
 */
class KnightLeapSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
    private val onLand: (Boss, Location, Vector) -> Unit = { _, _, _ -> },
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        /** この距離以内に標的がいるときだけ */
        val range: Double,
        /** 標的がこれより近いときは使わない（詰め寄りの技なので） */
        val minRange: Double,
        val jumpStartTicks: Int,
        val landTicks: Int,
        val endTicks: Int,
        /** 最高点の高さ（ブロック） */
        val jumpHeight: Double,
        /** 着地の何 tick 前に着地点を確定するか（大きいほど避けやすい） */
        val lockTicks: Int,
        val impactRadius: Double,
        val impactDamage: Double,
        /** 吹き飛ばしの強さ */
        val knockback: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 16.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 5.0),
                jumpStartTicks = section.int(Names.SkillConfig.JUMP_START_TICKS, 8),
                landTicks = section.int(Names.SkillConfig.LAND_TICKS, 18),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 40),
                jumpHeight = section.double(Names.SkillConfig.JUMP_HEIGHT, 4.0),
                lockTicks = section.int(Names.SkillConfig.LOCK_TICKS, 5).coerceAtLeast(0),
                impactRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 3.2),
                impactDamage = section.double(Names.SkillConfig.IMPACT_DAMAGE, 14.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.9),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private var origin: Location? = null
    private var landing: Location? = null
    private var locked = false
    private val hit = HashSet<UUID>()

    private val lockTick: Int get() = (settings.landTicks - settings.lockTicks).coerceAtLeast(settings.jumpStartTicks)

    override fun canStart(boss: Boss): Boolean {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return false
        return t.location.distance(boss.entity.location) >= settings.minRange
    }

    override fun start(boss: Boss) {
        tick = 0
        locked = false
        hit.clear()
        target = TargetFinder.findNearest(boss, settings.range)
        origin = boss.entity.location.clone()
        landing = null
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        updateLanding(boss)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        if (!locked) {
            updateLanding(boss)
            if (tick >= lockTick) locked = true
        }
        val spot = landing

        // 予告の輪（固まると満ち始める）
        if (spot != null && tick < settings.landTicks && tick % 2 == 0) {
            val progress = if (!locked) 0.0
            else ((tick - lockTick).toDouble() / (settings.landTicks - lockTick).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            KnightFx.warnCircle(spot, settings.impactRadius, progress)
        }

        when {
            tick < settings.jumpStartTicks -> Facing.faceTarget(boss, target)
            tick < settings.landTicks -> fly(boss)
            tick == settings.landTicks -> land(boss)
        }

        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        // 跳んでいる途中で止められたら、その下の地面に降ろす
        val loc = boss.entity.location
        if (tick in settings.jumpStartTicks until settings.landTicks) {
            loc.y = Ground.groundY(loc.world, loc.x, loc.y, loc.z, 16)
            boss.entity.teleport(loc)
        }
        boss.resumeEntityAi()
        target = null
    }

    /** 着地点を標的の足元に合わせる（狙えなくなったら前のまま） */
    private fun updateLanding(boss: Boss) {
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) } ?: return
        val at = t.location.clone()
        at.y = Ground.groundY(at.world, at.x, at.y + 1.0, at.z, 8)
        if (!Ground.isFree(at)) return
        landing = at
    }

    /** 放物線の1tick分。着地点が動いても、残りの道のりで合わせる */
    private fun fly(boss: Boss) {
        val from = origin ?: return
        val to = landing ?: return
        val span = (settings.landTicks - settings.jumpStartTicks).coerceAtLeast(1)
        val f = ((tick - settings.jumpStartTicks + 1).toDouble() / span).coerceIn(0.0, 1.0)
        val x = from.x + (to.x - from.x) * f
        val z = from.z + (to.z - from.z) * f
        val y = from.y + (to.y - from.y) * f + settings.jumpHeight * 4 * f * (1 - f)
        val next = Location(from.world, x, y, z)
        val dir = Facing.horizontalDir(from.toVector(), to.toVector()) ?: Facing.forward(boss.entity)
        next.direction = dir
        next.pitch = 0f
        // 壁にめり込むなら、その tick は進まない
        if (!Ground.isFree(next)) return
        boss.entity.teleport(next)
        if (tick == settings.jumpStartTicks) KnightFx.groundBurst(from, 1.6)
    }

    private fun land(boss: Boss) {
        val entity = boss.entity
        val at = landing?.clone() ?: entity.location
        val loc = entity.location
        at.yaw = loc.yaw
        at.pitch = 0f
        if (Ground.isFree(at)) entity.teleport(at) else at.y = loc.y
        val forward = Facing.forward(entity)

        KnightFx.groundBurst(at, settings.impactRadius)
        for (p in at.world.getNearbyPlayers(at, 14.0)) {
            p.playSound(p.location, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, org.bukkit.SoundCategory.HOSTILE, 0.6f, 0.5f)
        }
        val r = settings.impactRadius
        for (e in at.world.getNearbyEntities(at, r + 1.0, 3.0, r + 1.0)) {
            if (e !is LivingEntity || e.uniqueId in hit || !TargetFinder.isValidTarget(boss, e)) continue
            val v = Vector(e.location.x - at.x, 0.0, e.location.z - at.z)
            val dy = e.location.y - at.y
            if (v.length() > r + e.width / 2 || dy < -1.5 || dy > 2.5) continue
            hit += e.uniqueId
            e.noDamageTicks = 0
            e.damage(settings.impactDamage, entity)
            if (e is Player && boss.status.justGuard.guardedNow(e)) continue
            val push = if (v.lengthSquared() > 1e-4) v.normalize() else forward.clone()
            e.velocity = push.multiply(settings.knockback).setY(0.45)
        }
        onLand(boss, at, forward)
    }
}
