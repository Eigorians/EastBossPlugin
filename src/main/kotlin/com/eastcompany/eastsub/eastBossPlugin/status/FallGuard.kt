package com.eastcompany.eastsub.eastBossPlugin.status

import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 落下ダメージの保護。ボスが空へ打ち上げた・投げ飛ばしたプレイヤーが、落ちてきたときに落下ダメージを受けないようにする
 * （StatusListener が FALL のダメージを打ち消す）。
 *
 * [protect] してから ticks の間守る。着地したら（地面に着いて少したったら）終わる。
 * 守っている間は毎tick fallDistance も 0 に戻す（落下の距離がたまらないように）
 */
class FallGuard(private val server: Server) {

    /** 守り終える tick */
    private val until = HashMap<UUID, Int>()

    /** 地面に着いてからの tick（着地を見届けてから外す） */
    private val grounded = HashMap<UUID, Int>()

    /** 守り始めてから一度でも宙に浮いた人（浮く前の、地面に立っている間は外さない） */
    private val lifted = HashSet<UUID>()

    fun protect(player: Player, ticks: Int) {
        val end = server.currentTick + ticks.coerceAtLeast(1)
        until[player.uniqueId] = maxOf(until[player.uniqueId] ?: 0, end)
        grounded.remove(player.uniqueId)
        lifted.remove(player.uniqueId)
    }

    fun isProtected(player: Player): Boolean {
        val end = until[player.uniqueId] ?: return false
        return server.currentTick <= end
    }

    /** 毎tick呼ぶ */
    fun tick() {
        val now = server.currentTick
        val it = until.entries.iterator()
        while (it.hasNext()) {
            val (id, end) = it.next()
            val p = server.getPlayer(id)
            if (p == null || now > end) {
                it.remove()
                grounded.remove(id)
                lifted.remove(id)
                continue
            }
            if (p.isOnGround && id in lifted) {
                val n = (grounded[id] ?: 0) + 1
                grounded[id] = n
                // 着地して少したったら終わる（着地の tick のダメージを打ち消した後）
                if (n >= LANDED_TICKS) {
                    it.remove()
                    grounded.remove(id)
                    lifted.remove(id)
                }
            } else if (!p.isOnGround) {
                lifted += id
                grounded.remove(id)
                p.fallDistance = 0f
            }
        }
    }

    fun release(player: Player) {
        until.remove(player.uniqueId)
        grounded.remove(player.uniqueId)
        lifted.remove(player.uniqueId)
    }

    fun clear() {
        until.clear()
        grounded.clear()
        lifted.clear()
    }

    private companion object {
        const val LANDED_TICKS = 5
    }
}
