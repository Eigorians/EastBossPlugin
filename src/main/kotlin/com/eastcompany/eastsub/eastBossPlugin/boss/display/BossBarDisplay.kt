package com.eastcompany.eastsub.eastBossPlugin.boss.display

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.record.TimeFormat
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.UUID

/**
 * ボスの HP を、近くのプレイヤーにだけボスバーで表示する。
 * 旅商人から始めたボス戦では、ボスの名前の横に経過時間も出す（config の battle.bossbar-timer）
 */
class BossBarDisplay(private val boss: Boss, private val server: Server) {

    private val bar: BossBar = BossBar.bossBar(
        boss.settings.displayName,
        1f,
        boss.settings.barColor,
        boss.settings.barOverlay,
    )

    /**
     * 見せている相手（UUID → その時の Player）。抜けて入り直すと Player が別のものになるので、
     * Player が変わっていたら見せ直す（入り直したときにボスバーが消えないように）
     */
    private val viewers = HashMap<UUID, Player>()

    fun update(age: Int) {
        bar.progress(boss.healthRatio().toFloat().coerceIn(0f, 1f))
        updateTimer()
        if (age % 10 == 0) refreshViewers()
    }

    /** ボスバーの名前に経過時間を付ける */
    private fun updateTimer() {
        val battle = boss.battle ?: return
        val plugin = boss.plugin
        if (!plugin.configManager.main.boolean(Names.Config.BATTLE_BOSSBAR_TIMER, true)) return
        bar.name(plugin.messages.component(
            Names.Message.BATTLE_BOSSBAR_TIMER,
            "boss" to boss.settings.displayName,
            "time" to TimeFormat.format(battle.elapsedTicks),
        ))
    }

    private fun refreshViewers() {
        val entity = boss.entity
        val range = boss.settings.barRange * boss.settings.barRange
        // 抜けた人は外す（入り直したら、次の更新で見せ直す）
        viewers.values.removeIf { !it.isOnline }
        for (p in server.onlinePlayers) {
            val near = p.world == entity.world && p.location.distanceSquared(entity.location) <= range
            if (near) {
                if (viewers.put(p.uniqueId, p) !== p) p.showBossBar(bar)
            } else if (viewers.remove(p.uniqueId) != null) {
                p.hideBossBar(bar)
            }
        }
    }

    fun close() {
        viewers.keys.forEach { server.getPlayer(it)?.hideBossBar(bar) }
        viewers.clear()
    }
}
