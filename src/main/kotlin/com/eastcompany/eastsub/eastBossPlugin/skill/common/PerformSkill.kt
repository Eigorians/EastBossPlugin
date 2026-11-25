package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * その場でアニメーションを流すだけの演出（登場・フェーズ移行・挑発など）。攻撃はしない。
 *
 * weight が 0 なら抽選では選ばれない。[Boss.queueSkill] で決めて出す。
 * 最中に受けるダメージに takenDamageMultiplier を掛ける（演出中に一方的に削られすぎないように）。
 *
 * @param onTick 毎tick呼ばれる（技の開始からの tick）。決まった tick に色を変えるなどの演出用
 */
class PerformSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
    private val onTick: (Boss, Int) -> Unit = { _, _ -> },
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val endTicks: Int,
        val takenDamageMultiplier: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 0),
                animation = section.string(Names.SkillConfig.ANIMATION),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 40),
                takenDamageMultiplier = section.double(Names.SkillConfig.TAKEN_DAMAGE_MULTIPLIER, 1.0),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0

    override fun canStart(boss: Boss): Boolean = true

    override fun start(boss: Boss) {
        tick = 0
        boss.entity.setAI(false)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        onTick(boss, tick)
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        boss.resumeEntityAi()
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        event.damage *= settings.takenDamageMultiplier
    }
}
