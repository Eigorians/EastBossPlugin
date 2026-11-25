package com.eastcompany.eastsub.eastBossPlugin.status

import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/**
 * プレイヤーの回復不可デバフを管理する。
 * 回復の打ち消しは [StatusListener] が行う。
 */
class HealLockManager(private val server: Server) {

    private val locked = HashMap<UUID, Int>()

    fun isLocked(player: Player): Boolean = locked.containsKey(player.uniqueId)

    /** 付与。すでにかかっている場合は時間を上書きする */
    fun lock(player: Player, ticks: Int) {
        locked[player.uniqueId] = ticks

        // 視覚表現: ポーション効果(アイコンと残り時間がHUDに出る。ゲームプレイへの影響なし)
        player.addPotionEffect(
            PotionEffect(PotionEffectType.UNLUCK, ticks + 20, 0, false, true, true)
        )
    }

    fun tick() {
        val iter = locked.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val player = server.getPlayer(entry.key)
            if (player == null || !player.isOnline || player.isDead) {
                iter.remove()
                continue
            }

            val remain = entry.value - 1
            if (remain <= 0) {
                player.removePotionEffect(PotionEffectType.UNLUCK)
                iter.remove()
                continue
            }
            entry.setValue(remain)

            if (remain % 10 == 0) {
                player.world.spawnParticle(
                    Particle.SOUL, player.location.add(0.0, 1.0, 0.0), 3, 0.3, 0.5, 0.3, 0.01
                )
            }
        }
    }

    /** 1人だけ回復不可を解く */
    fun release(player: Player) {
        if (locked.remove(player.uniqueId) != null) player.removePotionEffect(PotionEffectType.UNLUCK)
    }

    fun clear() {
        locked.keys.forEach { server.getPlayer(it)?.removePotionEffect(PotionEffectType.UNLUCK) }
        locked.clear()
    }
}
