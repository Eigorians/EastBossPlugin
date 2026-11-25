package com.eastcompany.eastsub.eastBossPlugin.menu

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.battle.BattleManager
import com.eastcompany.eastsub.eastBossPlugin.message.Messages
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

/**
 * ボス選択メニューの操作。アイテムは動かせず、ボスのアイコンをクリックすると募集を始める。
 * 「ボス戦をやめる」ボタンは2回クリックで中止（募集を始めた人と管理者だけ）
 */
class MenuListener(private val battles: BattleManager, private val messages: Messages) : Listener {

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val menu = e.view.topInventory.holder as? BossSelectMenu ?: return
        // メニューを開いている間は、自分のインベントリ側も含めてアイテムを動かせないようにする
        e.isCancelled = true

        if (e.clickedInventory != e.view.topInventory) return
        val player = e.whoClicked as? Player ?: return

        // ボス戦をやめる（2回クリック）
        if (e.slot == menu.cancelSlot) {
            when (menu.clickCancel()) {
                BossSelectMenu.CancelClick.ARMED ->
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 1.0f, 0.7f)
                BossSelectMenu.CancelClick.CANCELLED -> player.closeInventory()
                BossSelectMenu.CancelClick.DENIED ->
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f)
                BossSelectMenu.CancelClick.GONE -> {
                    player.closeInventory()
                    messages.send(player, Names.Message.BATTLE_NONE)
                }
            }
            return
        }

        val type = menu.typeAt(e.slot) ?: return

        player.closeInventory()
        battles.start(player, type)
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is BossSelectMenu) e.isCancelled = true
    }
}
