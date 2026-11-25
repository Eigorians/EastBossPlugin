package com.eastcompany.eastsub.eastBossPlugin.boss

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import com.destroystokyo.paper.entity.ai.VanillaGoal
import com.eastcompany.eastsub.eastBossPlugin.Names
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import java.util.EnumSet

/**
 * ボスの本体（ハスク）の素の殴り攻撃の代わりに入れる AI。標的を追って歩くだけで、殴らない。
 *
 * バニラの殴りの AI（ZOMBIE_ATTACK）は「追う」と「殴る」が一体なので、丸ごと外してこれに入れ替える。
 * ダメージのイベントで殴りだけを止めることはできない（技の `damage(量, ボス)` も同じ原因・同じダメージ元で届く）。
 * ボスの攻撃はすべて技で行う。
 */
class ChaseGoal private constructor(private val mob: Zombie) : Goal<Zombie> {

    private var untilRepath = 0

    override fun shouldActivate(): Boolean {
        val t: LivingEntity = mob.target ?: return false
        if (t.isDead || !t.isValid || t.world != mob.world) return false
        return t !is Player || (t.gameMode != GameMode.CREATIVE && t.gameMode != GameMode.SPECTATOR)
    }

    override fun start() {
        untilRepath = 0
    }

    override fun tick() {
        val t = mob.target ?: return
        mob.lookAt(t, LOOK_SPEED, LOOK_SPEED)
        if (--untilRepath > 0) return
        untilRepath = REPATH_INTERVAL
        mob.pathfinder.moveTo(t, 1.0)
    }

    override fun stop() {
        mob.pathfinder.stopPathfinding()
    }

    override fun getKey(): GoalKey<Zombie> = GoalKey.of(Zombie::class.java, Names.Key.CHASE_GOAL)

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK)

    companion object {
        /** 外す殴りの AI と同じ優先度（村の徘徊・散歩より先） */
        private const val PRIORITY = 3
        private const val REPATH_INTERVAL = 4
        private const val LOOK_SPEED = 30f

        /** 素の殴り攻撃を外して、追うだけの AI に入れ替える */
        fun replaceMelee(mob: Zombie) {
            val goals = Bukkit.getMobGoals()
            goals.removeGoal(mob, VanillaGoal.ZOMBIE_ATTACK)
            goals.addGoal(mob, PRIORITY, ChaseGoal(mob))
        }
    }
}
