package com.eastcompany.eastsub.eastBossPlugin.npc

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.message.Messages
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * 旅商人 NPC への干渉を制御する。
 * - あらゆるダメージを無効化する
 * - クリエイティブモードのプレイヤーに殴られたら消滅する
 * - 右クリックで取引画面の代わりにボス選択メニューを開く
 */
class TraderListener(
    private val traderNpc: TraderNpc,
    private val messages: Messages,
    private val debug: (String) -> Unit,
    /** 右クリックで開くボス選択メニュー */
    private val openMenu: (Player) -> Unit,
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (!traderNpc.isTrader(entity)) return
        event.isCancelled = true

        // クリエイティブのプレイヤーが直接殴った場合だけ消す
        val damager = (event as? EntityDamageByEntityEvent)?.damager as? Player ?: return
        if (damager.gameMode != GameMode.CREATIVE) return

        entity.remove()
        messages.send(damager, Names.Message.TRADER_PUNCHED)
        debug("${damager.name} が旅商人を削除しました (${entity.location.toVector()})")
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (!traderNpc.isTrader(event.rightClicked)) return
        // 取引画面は開かせない
        event.isCancelled = true
        // 右クリックはメインハンド・オフハンドの2回届くので、メインハンドの分だけ開く
        if (event.hand != EquipmentSlot.HAND) return
        openMenu(event.player)
    }
}
