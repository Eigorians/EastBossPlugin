package com.eastcompany.eastsub.eastBossPlugin.npc

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.WanderingTrader
import org.bukkit.persistence.PersistentDataType

/**
 * ボス召喚用の旅商人 NPC を召喚・削除する。
 * 旅商人は動かず無敵で、目印として PDC に [Names.Key.TRADER] を持つ。
 * リロード・再起動後も残るよう永続化する（onDisable では消さない）。
 */
class TraderNpc(private val server: Server, private val configManager: ConfigManager) {

    /** 既存の旅商人を消してから、指定位置に新しく召喚する */
    fun spawn(location: Location): WanderingTrader {
        removeAll()
        return location.world.spawn(location, WanderingTrader::class.java) { trader ->
            // 動かない（AI を止めると移動・重力・押し出しも無効になる）
            trader.setAI(false)
            // 無敵（クリエイティブの攻撃だけは通るので、リスナー側で削除処理にする）
            trader.isInvulnerable = true
            // 自然に消えない
            trader.isPersistent = true
            trader.removeWhenFarAway = false
            trader.despawnDelay = 0
            trader.setCanDrinkPotion(false)
            trader.setCanDrinkMilk(false)
            trader.isSilent = true

            trader.customName(configManager.main.component(Names.Config.TRADER_NAME))
            trader.isCustomNameVisible = true

            trader.persistentDataContainer.set(Names.Key.TRADER, PersistentDataType.BYTE, 1)
        }
    }

    /**
     * 読み込まれている全ワールドの旅商人 NPC を削除する。
     * @return 削除した数
     */
    fun removeAll(): Int {
        val traders = server.worlds.flatMap { it.getEntitiesByClass(WanderingTrader::class.java) }.filter(::isTrader)
        traders.forEach { it.remove() }
        return traders.size
    }

    /** このプラグインが召喚した旅商人 NPC か */
    fun isTrader(entity: Entity): Boolean =
        entity is WanderingTrader && entity.persistentDataContainer.has(Names.Key.TRADER, PersistentDataType.BYTE)
}
