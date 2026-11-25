package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * いくつかの技を順に、間を空けずに出す（連撃・2連続の跳躍など）。
 * 1つ目の技を始められるときだけ発動する。2つ目以降は、前の技が終わった tick にそのまま始める。
 *
 * 効果音は、それぞれの技の sounds をその技の tick（soundTick）で鳴らす。
 * 最中に受けるダメージの補正は、今出している技に任せる。
 */
class ChainSkill(
    override val id: String,
    private val steps: List<BossSkill>,
    override val weight: Int = 1,
) : BossSkill {

    init {
        require(steps.isNotEmpty()) { "$id: 連撃の技が1つもありません" }
    }

    private var index = 0
    private var stepTick = 0

    private val current: BossSkill? get() = steps.getOrNull(index)

    override fun canStart(boss: Boss): Boolean = steps.first().canStart(boss)

    override fun start(boss: Boss) {
        index = 0
        begin(boss)
    }

    override fun tick(boss: Boss): Boolean {
        val step = current ?: return true
        val soundTick = step.soundTick ?: stepTick
        step.sounds.forEach { if (it.tick == soundTick) it.play(boss.entity) }
        stepTick++
        if (!step.tick(boss)) return false
        step.stop(boss)
        index++
        if (current == null) return true
        begin(boss)
        return false
    }

    override fun stop(boss: Boss) {
        current?.stop(boss)
        index = steps.size
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        current?.modifyIncomingDamage(boss, event)
    }

    private fun begin(boss: Boss) {
        stepTick = 0
        current?.start(boss)
    }
}
