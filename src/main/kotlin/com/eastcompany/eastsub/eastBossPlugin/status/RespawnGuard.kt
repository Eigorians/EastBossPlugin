package com.eastcompany.eastsub.eastBossPlugin.status

import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.UUID

/**
 * リスポーン直後の保護。リスポーンしてから protectTicks の間は、ボスに狙われず、ボスの攻撃も当たらない
 * （skill.util.TargetFinder.isValidTarget が見る）。
 *
 * 技は始めたときの標的を覚えているので、死んでリスポーンした人を、遠いリスポーン地点まで狙い続けることがあった。
 * 保護の間に技が標的を見失うので、近くのほかの人へ移る。リスポーン直後に袋叩きにされるのも防ぐ
 */
class RespawnGuard(private val server: Server) {

    /** リスポーンした tick */
    private val respawnedAt = HashMap<UUID, Int>()

    fun markRespawn(player: Player) {
        respawnedAt[player.uniqueId] = server.currentTick
    }

    /** リスポーンしてから protectTicks たっていない */
    fun isProtected(player: Player, protectTicks: Int): Boolean {
        val at = respawnedAt[player.uniqueId] ?: return false
        return server.currentTick - at < protectTicks
    }

    /** 毎tick呼ぶ。保護が十分に切れた人を消す */
    fun tick() {
        if (server.currentTick % 200 != 0) return
        val now = server.currentTick
        respawnedAt.values.removeIf { now - it > 600 }
    }

    fun clear() = respawnedAt.clear()
}
