package com.eastcompany.eastsub.eastBossPlugin.status

import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID

/**
 * プレイヤーのスタン状態を管理する。
 * スタン中の操作制限は [StatusListener] が行う。
 */
class StunManager(
    private val server: Server,
    /** 見た目用の低速化の強さ。消すときに「スタン由来の低速化か」の判定にも使う */
    private val slowAmplifier: () -> Int,
) {

    private val stunned = HashMap<UUID, Int>()

    fun isStunned(player: Player): Boolean = stunned.containsKey(player.uniqueId)

    /** スタンさせる。すでにスタン中なら長い方の時間を残す */
    fun stun(player: Player, ticks: Int) {
        val first = !isStunned(player)
        stunned[player.uniqueId] = maxOf(stunned[player.uniqueId] ?: 0, ticks)
        player.velocity = Vector(0.0, 0.0, 0.0)

        // 視覚表現: 低速化のポーション効果
        player.addPotionEffect(
            PotionEffect(PotionEffectType.SLOWNESS, ticks + 5, slowAmplifier(), false, true, true)
        )
        if (first) {
            player.world.playSound(player.location, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.7f)
        }
    }

    fun tick() {
        val iter = stunned.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val player = server.getPlayer(entry.key)
            if (player == null || !player.isOnline || player.isDead) {
                player?.let { removeSlow(it) }
                iter.remove()
                continue
            }

            val remain = entry.value - 1
            if (remain <= 0) {
                removeSlow(player)
                iter.remove()
                continue
            }
            entry.setValue(remain)

            if (remain % 5 == 0) {
                player.world.spawnParticle(
                    Particle.ANGRY_VILLAGER, player.location.add(0.0, 2.1, 0.0), 2, 0.25, 0.1, 0.25, 0.0
                )
            }
        }
    }

    /** 1人だけスタンを解く */
    fun release(player: Player) {
        if (stunned.remove(player.uniqueId) != null) removeSlow(player)
    }

    fun clear() {
        stunned.keys.forEach { server.getPlayer(it)?.let { p -> removeSlow(p) } }
        stunned.clear()
    }

    /** スタン由来の低速化だけを消す(他の低速化は残す) */
    private fun removeSlow(player: Player) {
        val effect = player.getPotionEffect(PotionEffectType.SLOWNESS) ?: return
        if (effect.amplifier == slowAmplifier()) {
            player.removePotionEffect(PotionEffectType.SLOWNESS)
        }
    }
}
