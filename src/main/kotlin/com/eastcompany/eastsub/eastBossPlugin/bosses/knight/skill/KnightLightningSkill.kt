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
import org.bukkit.event.entity.EntityDamageByEntityEvent
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 雷: 大剣を天へ掲げて雷を呼び、しばらくの間、相手のまわりのランダムな所へ雷を降らせ続ける
 * （それぞれ予告の輪が満ちてから落ちる。KnightHazards.lightning）。相手が離れているとき（min-range 以上）だけ使う。
 * 呼んでいる間は動かないので、詰め寄って殴るのもあり。
 *
 *   0 〜 channelStartTick … 掲げる（animation）
 *   〜 +channelTicks      … 呼び続ける（channelAnimation）。intervalTicks ごとに count 本の雷を予約する。
 *                            aimRatio の割合で相手の足元ちょうど、ほかは相手から areaRadius 以内のランダムな所
 *   endTicks              … 終わり（予約した雷は、技が終わっても落ちる）
 */
class KnightLightningSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val channelAnimation: String,
        val range: Double,
        val minRange: Double,
        val channelStartTick: Int,
        val channelTicks: Int,
        val intervalTicks: Int,
        val count: Int,
        val warningTicks: Int,
        val areaRadius: Double,
        val aimRatio: Double,
        val impactRadius: Double,
        val impactDamage: Double,
        val endTicks: Int,
        val takenDamageMultiplier: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings {
                val start = section.int(Names.SkillConfig.CHANNEL_START_TICK, 16)
                val channel = section.int(Names.SkillConfig.CHANNEL_TICKS, 100)
                return Settings(
                    weight = section.int(Names.SkillConfig.WEIGHT, 1),
                    animation = section.string(Names.SkillConfig.ANIMATION, "cast_sky"),
                    channelAnimation = section.string(Names.SkillConfig.CHANNEL_ANIMATION, "channel"),
                    range = section.double(Names.SkillConfig.RANGE, 32.0),
                    minRange = section.double(Names.SkillConfig.MIN_RANGE, 9.0),
                    channelStartTick = start,
                    channelTicks = channel,
                    intervalTicks = section.int(Names.SkillConfig.INTERVAL_TICKS, 5).coerceAtLeast(1),
                    count = section.int(Names.SkillConfig.COUNT, 1).coerceAtLeast(1),
                    warningTicks = section.int(Names.SkillConfig.WARNING_TICKS, 18).coerceAtLeast(1),
                    areaRadius = section.double(Names.SkillConfig.AREA_RADIUS, 9.0),
                    aimRatio = section.double(Names.SkillConfig.AIM_RATIO, 0.35).coerceIn(0.0, 1.0),
                    impactRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 2.0),
                    impactDamage = section.double(Names.SkillConfig.IMPACT_DAMAGE, 6.0),
                    endTicks = section.int(Names.SkillConfig.END_TICKS, start + channel + 12),
                    takenDamageMultiplier = section.double(Names.SkillConfig.TAKEN_DAMAGE_MULTIPLIER, 1.0),
                )
            }
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0

    override fun canStart(boss: Boss): Boolean {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return false
        return hypot(t.location.x - boss.entity.location.x, t.location.z - boss.entity.location.z) >= settings.minRange
    }

    override fun start(boss: Boss) {
        tick = 0
        boss.entity.setAI(false)
        Facing.faceTarget(boss, TargetFinder.findNearest(boss, settings.range))
        boss.playOnce(settings.animation)
        KnightSounds.lightningCast(boss.entity)
    }

    override fun tick(boss: Boss): Boolean {
        val k = boss as KnightBoss
        val channelEnd = settings.channelStartTick + settings.channelTicks
        if (tick == settings.channelStartTick) boss.playLoop(settings.channelAnimation, lerpTicks = 3)
        if (tick == channelEnd) boss.stopAnimation(settings.channelAnimation)
        if (tick in settings.channelStartTick until channelEnd) {
            if (tick % 2 == 0) KnightFx.channelSpark(k)
            if ((tick - settings.channelStartTick) % settings.intervalTicks == 0) repeat(settings.count) { call(k) }
        }
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        boss.stopAnimation(settings.channelAnimation)
        boss.resumeEntityAi()
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        event.damage *= settings.takenDamageMultiplier
    }

    /** 相手（参加者からランダム）のまわりに雷を1本予約する */
    private fun call(boss: KnightBoss) {
        val t = boss.randomTarget(settings.range) ?: return
        val at = t.location.clone()
        if (Random.nextDouble() >= settings.aimRatio) {
            val a = Random.nextDouble(Math.PI * 2)
            val r = settings.areaRadius * sqrt(Random.nextDouble())
            at.add(cos(a) * r, 0.0, sin(a) * r)
        }
        at.y = Ground.groundY(at.world, at.x, t.location.y + 4.0, at.z, 14)
        boss.hazards.lightning(at, settings.warningTicks, settings.impactRadius, settings.impactDamage)
    }
}
