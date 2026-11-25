package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import org.bukkit.event.entity.EntityDamageByEntityEvent
import kotlin.random.Random

/**
 * 技を毎回ちがう順に、間を空けずに続ける連携（[ChainSkill] の並びを、出すたびに決め直す）。
 *
 * 並び = [head]（必ずこの順で始まる）→ [pool] からランダムに足す → [finisher]（あれば。疲れて隙をさらすなど）。
 * head と足した技を合わせて minSteps 〜 maxSteps 個（finisher は数えない）。
 * pool に同じ技を何度も書くと、その技が選ばれやすくなる。同じ技は3回続けない。
 *
 * 発動できるのは、最初の技（head が無ければ pool のどれか）を始められるときだけ。
 * 同じ技のインスタンスが並びに何度出てもよい（前の回を止めてから始め直す）。
 */
class RandomChainSkill(
    override val id: String,
    private val head: List<BossSkill>,
    private val pool: List<BossSkill>,
    private val minSteps: Int,
    private val maxSteps: Int,
    private val finisher: BossSkill? = null,
    override val weight: Int = 1,
) : BossSkill {

    init {
        require(head.isNotEmpty() || pool.isNotEmpty()) { "$id: 連携の技が1つもありません" }
    }

    /** 今回の並び */
    private var inner: ChainSkill? = null

    override val sounds: List<SoundCue> get() = emptyList()

    override fun canStart(boss: Boss): Boolean =
        head.firstOrNull()?.canStart(boss) ?: pool.any { it.canStart(boss) }

    override fun start(boss: Boss) {
        val chain = ChainSkill(id, order(boss), weight)
        inner = chain
        chain.start(boss)
    }

    override fun tick(boss: Boss): Boolean = inner?.tick(boss) ?: true

    override fun stop(boss: Boss) {
        inner?.stop(boss)
        inner = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        inner?.modifyIncomingDamage(boss, event)
    }

    private fun order(boss: Boss): List<BossSkill> {
        val steps = ArrayList(head)
        if (steps.isEmpty()) steps += pool.filter { it.canStart(boss) }.ifEmpty { pool }.random()
        val count = Random.nextInt(minSteps, maxOf(minSteps, maxSteps) + 1)
        while (steps.size < count && pool.isNotEmpty()) {
            val last2 = steps.takeLast(2)
            val tripled = { s: BossSkill -> last2.size == 2 && last2.all { it === s } }
            val choices = pool.filterNot(tripled).ifEmpty { pool }
            steps += choices.random()
        }
        finisher?.let { steps += it }
        return steps
    }
}
