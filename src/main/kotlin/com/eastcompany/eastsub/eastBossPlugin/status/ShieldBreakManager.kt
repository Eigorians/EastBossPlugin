package com.eastcompany.eastsub.eastBossPlugin.status

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 盾のガード破壊。ボスの攻撃を盾で防ぐたびに、防いだダメージをプレイヤーごとにためる。
 * ためた量が上限に届くと盾が壊れ、しばらく使えなくなる（アイテムのクールダウン。斧で盾を割られたときと同じ）。
 *
 * - 防がずに [DECAY_DELAY_TICKS] たつと、ためた量が少しずつ減っていく（[DECAY_TICKS] で上限ぶん減る）
 * - 上限の半分を超えると、防ぐたびにミシッと鳴って知らせる
 */
class ShieldBreakManager(private val server: Server) {

    private class Gauge(var amount: Double = 0.0, var idle: Int = 0, var threshold: Double = 1.0)

    private val gauges = HashMap<UUID, Gauge>()

    /**
     * 盾で防いだダメージを足す。
     * @param threshold この量で壊れる（0 以下なら壊れない）
     * @param disableTicks 壊れた後に盾を使えない時間
     * @return 今の攻撃で壊れたら true
     */
    fun addBlocked(player: Player, amount: Double, threshold: Double, disableTicks: Int): Boolean {
        if (threshold <= 0 || amount <= 0) return false
        val gauge = gauges.getOrPut(player.uniqueId) { Gauge() }
        gauge.amount += amount
        gauge.idle = 0
        gauge.threshold = threshold

        val world = player.world
        val at = player.location.add(0.0, 1.2, 0.0)
        if (gauge.amount < threshold) {
            if (gauge.amount >= threshold * 0.5) {
                // ひびが入っている
                val pitch = (1.4 - gauge.amount / threshold * 0.6).toFloat()
                world.playSound(at, Sound.BLOCK_WOOD_BREAK, SoundCategory.PLAYERS, 1.0f, pitch)
                world.spawnParticle(Particle.BLOCK, at, 6, 0.2, 0.2, 0.2, 0.0, Material.OAK_PLANKS.createBlockData())
            }
            return false
        }

        gauges.remove(player.uniqueId)
        breakShield(player, disableTicks)
        return true
    }

    private fun breakShield(player: Player, disableTicks: Int) {
        // 構えている盾（盾以外の防げるアイテムでもよい）を下ろさせて、しばらく使えなくする
        val ticks = disableTicks.coerceAtLeast(1)
        val held = player.activeItem.takeIf { !it.isEmpty }?.clone()
        player.clearActiveItem()
        if (held != null) player.setCooldown(held, ticks)
        player.setCooldown(ItemStack.of(Material.SHIELD), ticks)

        val world = player.world
        val at = player.location.add(0.0, 1.2, 0.0)
        world.playSound(at, Sound.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1.5f, 0.8f)
        world.playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.PLAYERS, 0.8f, 1.2f)
        world.spawnParticle(Particle.BLOCK, at, 30, 0.3, 0.3, 0.3, 0.0, Material.OAK_PLANKS.createBlockData())
        world.spawnParticle(Particle.CRIT, at, 15, 0.3, 0.3, 0.3, 0.2)
    }

    /** 毎tick呼ぶ。しばらく防いでいない人のためた量を少しずつ減らす */
    fun tick() {
        val iter = gauges.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val g = entry.value
            g.idle++
            if (g.idle > DECAY_DELAY_TICKS) g.amount -= g.threshold / DECAY_TICKS
            if (g.amount <= 0 || server.getPlayer(entry.key) == null) iter.remove()
        }
    }

    /** 1人だけ元に戻す（ためた量を消し、壊れた盾をすぐ使えるようにする） */
    fun release(player: Player) {
        gauges.remove(player.uniqueId)
        player.setCooldown(ItemStack.of(Material.SHIELD), 0)
    }

    fun clear() = gauges.clear()

    private companion object {
        /** 防がずにこれだけたつと、ためた量が減り始める（2秒） */
        const val DECAY_DELAY_TICKS = 40
        /** 減り始めてから、上限ぶん減るまでの時間（4秒） */
        const val DECAY_TICKS = 80.0
    }
}
