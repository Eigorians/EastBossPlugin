package com.eastcompany.eastsub.eastBossPlugin.status

import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.UUID

/**
 * ジャストガードの受付。プレイヤーが盾を構えた瞬間を覚えておき、その直後に届いたボスの攻撃をジャストガードにする。
 *
 * - 盾で右クリックした瞬間から windowTicks の間が受付（バニラの盾はガードが効くまで少し遅れるが、ジャストガードはその遅れに関係ない）
 * - 構えてから lockoutTicks の間は、構え直しても受付が開かない（連打でまぐれ当たりしないように）。
 *   ジャストガードできたときは待たずにすぐ構え直せる（連撃を続けて受けられる）
 * - 受けたときの処理（ダメージを消す・音）は BossListener
 */
class JustGuardManager(private val server: Server) {

    private class State(
        /** 盾を構えた tick（受付の始まり） */
        var raisedAt: Long = Long.MIN_VALUE / 2,
        /** この tick までは構え直しても受付が開かない */
        var lockoutUntil: Long = Long.MIN_VALUE / 2,
        /** 最後にジャストガードした tick */
        var guardedAt: Long = Long.MIN_VALUE / 2,
    )

    private val states = HashMap<UUID, State>()

    private val now: Long get() = server.currentTick.toLong()

    /**
     * 盾を構えた（右クリックした）。
     * @param lockoutTicks 構えてからこの間は、構え直しても受付が開かない
     * @return 受付が開いたら true
     */
    fun raise(player: Player, lockoutTicks: Int): Boolean {
        val s = states.getOrPut(player.uniqueId) { State() }
        val t = now
        if (t < s.lockoutUntil) return false
        s.raisedAt = t
        s.lockoutUntil = t + lockoutTicks
        return true
    }

    /** 今届いた攻撃をジャストガードできるか（盾を構えてから windowTicks 以内か）。できたら記録して true */
    fun tryGuard(player: Player, windowTicks: Int): Boolean {
        val s = states[player.uniqueId] ?: return false
        val t = now
        if (t - s.raisedAt !in 0..windowTicks.toLong()) return false
        s.guardedAt = t
        // 受けられたら、すぐに構え直してよい（次の攻撃も受けられる）
        s.lockoutUntil = t
        return true
    }

    /**
     * この tick（と直前の tick）にジャストガードしたか。
     * 技が当てた後に付ける追加効果（スタン・吹き飛ばしなど）を止めるために使う
     */
    fun guardedNow(player: Player): Boolean {
        val g = states[player.uniqueId]?.guardedAt ?: return false
        return now - g in 0..1
    }

    /** 毎tick呼ぶ。しばらく何もしていない人・抜けた人の記録を消す */
    fun tick() {
        if (server.currentTick % 200 != 0) return
        val t = now
        states.entries.removeIf { (id, s) ->
            server.getPlayer(id) == null || (t - s.raisedAt > 200 && t - s.guardedAt > 200)
        }
    }

    fun release(player: Player) {
        states.remove(player.uniqueId)
    }

    fun clear() = states.clear()
}
