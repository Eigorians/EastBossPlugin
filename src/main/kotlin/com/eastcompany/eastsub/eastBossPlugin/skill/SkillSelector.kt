package com.eastcompany.eastsub.eastBossPlugin.skill

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import kotlin.random.Random

object SkillSelector {

    /** canStart を満たす技の中から、weight に比例した確率で1つ選ぶ */
    fun pick(boss: Boss, skills: List<BossSkill>): BossSkill? {
        val candidates = skills.filter { it.weight > 0 && it.canStart(boss) }
        if (candidates.isEmpty()) return null

        var roll = Random.nextInt(candidates.sumOf { it.weight })
        for (skill in candidates) {
            roll -= skill.weight
            if (roll < 0) return skill
        }
        return candidates.last()
    }
}
