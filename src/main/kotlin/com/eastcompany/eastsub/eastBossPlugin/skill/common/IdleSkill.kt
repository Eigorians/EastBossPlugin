package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill

/** 何もしない（通常AIで動く）待機。技の合間に間を空けたいときや、weight の確認用に使う */
class IdleSkill(
    override val id: String,
    private val ticks: Int,
    override val weight: Int = 1,
) : BossSkill {

    private var elapsed = 0

    override fun canStart(boss: Boss) = true

    override fun start(boss: Boss) {
        elapsed = 0
    }

    override fun tick(boss: Boss): Boolean {
        elapsed++
        return elapsed >= ticks
    }
}
