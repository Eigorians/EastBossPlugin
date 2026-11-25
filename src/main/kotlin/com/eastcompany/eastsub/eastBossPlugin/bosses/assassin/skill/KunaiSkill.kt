package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinBoss
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.entity.LivingEntity

/**
 * 苦無を扇状に投げる。左の短刀を苦無に持ち替え、releaseTicks ごとに count 本を fanDegrees の扇に投げる。
 * 苦無は目に見える速さで飛び、ブロックで止まる（BulletField）。扇の隙間に入るか、横へ走ってかわす。
 * 標的が minRange より近いときは使わない（近くでは連撃）。
 */
class KunaiSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val minRange: Double,
        val releaseTicks: List<Int>,
        val endTicks: Int,
        val count: Int,
        val fanDegrees: Double,
        /** 標的の動きをどれだけ見越すか（0 なら今いる所） */
        val leadRatio: Double,
        val bullet: BulletField.Spec,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                animation = section.string(Names.SkillConfig.ANIMATION, "throw"),
                range = section.double(Names.SkillConfig.RANGE, 18.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 4.0),
                releaseTicks = section.stringList(Names.SkillConfig.RELEASE_TICKS, listOf("7")).mapNotNull { it.trim().toIntOrNull() },
                endTicks = section.int(Names.SkillConfig.END_TICKS, 16),
                count = section.int(Names.SkillConfig.COUNT, 3).coerceAtLeast(1),
                fanDegrees = section.double(Names.SkillConfig.FAN_DEGREES, 30.0),
                leadRatio = section.double(Names.SkillConfig.LEAD_RATIO, 0.0),
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private val lastRelease: Int get() = settings.releaseTicks.maxOrNull() ?: 0

    override fun canStart(boss: Boss): Boolean = findTarget(boss) != null

    private fun findTarget(boss: Boss): LivingEntity? {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return null
        return if (t.location.distanceSquared(boss.entity.location) < settings.minRange * settings.minRange) null else t
    }

    override fun start(boss: Boss) {
        val a = boss as AssassinBoss
        tick = 0
        target = findTarget(a)
        Facing.faceTarget(a, target)
        a.holdKunai(true)
        a.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        val t = target?.takeIf { TargetFinder.isValidTarget(a, it) }
        // 投げ終わるまで標的の方を向き続ける
        if (tick <= lastRelease) Facing.faceTarget(a, t)
        if (tick in settings.releaseTicks) {
            a.throwKunai(t, settings.count, settings.fanDegrees, settings.bullet, settings.leadRatio)
            // 次も投げるなら、手にまた苦無を出す
            if (tick != lastRelease) a.holdKunai(true)
        }
        if (tick == lastRelease + 3) a.holdKunai(false)
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        (boss as AssassinBoss).holdKunai(false)
        target = null
    }
}
