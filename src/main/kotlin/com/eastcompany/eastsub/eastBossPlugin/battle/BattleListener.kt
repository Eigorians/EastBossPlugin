package com.eastcompany.eastsub.eastBossPlugin.battle

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerItemDamageEvent

/** ボス戦の参加者の死亡を数える・乗り物に乗れないようにする・装備の耐久を減らさない・お腹を減らさない */
class BattleListener(private val battles: BattleManager) : Listener {

    /** ボス戦の参加者は、戦っている間は装備（防具・武器・道具）の耐久が減らない */
    @EventHandler(ignoreCancelled = true)
    fun onItemDamage(e: PlayerItemDamageEvent) {
        if (battles.isFighting(e.player)) e.isCancelled = true
    }

    /** ボス戦の参加者は、戦っている間はお腹が減らない（満腹度が下がらない。食べて増えるのはそのまま） */
    @EventHandler(ignoreCancelled = true)
    fun onHunger(e: FoodLevelChangeEvent) {
        val player = e.entity as? Player ?: return
        if (e.foodLevel < player.foodLevel && battles.isFighting(player)) e.isCancelled = true
    }

    /** ボス戦の参加者は、戦っている間は乗り物に乗れない */
    @EventHandler(ignoreCancelled = true)
    fun onMount(e: EntityMountEvent) {
        val player = e.entity as? Player ?: return
        if (battles.isFighting(player)) e.isCancelled = true
    }

    /** ボス戦の参加者が死んだときの死亡ログは、参加者にだけ見せる（サーバー全体には流さない） */
    @EventHandler(priority = EventPriority.HIGH)
    fun onDeathMessage(e: PlayerDeathEvent) {
        val fellows = battles.fellowParticipants(e.player) ?: return
        val message = e.deathMessage() ?: return
        e.deathMessage(null)
        fellows.forEach { it.sendMessage(message) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(e: PlayerDeathEvent) {
        battles.onPlayerDeath(e.player)
    }
}
