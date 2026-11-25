package com.eastcompany.eastsub.eastBossPlugin.hook

import com.eastcompany.eastsub.eastBossPlugin.EastBossPlugin
import com.eastcompany.eastsub.eastBossPlugin.Names
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent

/**
 * サーバーの読み込みが終わったら、自動で BetterModel をリロードする（config.yml の bettermodel セクション）。
 *
 * - /reload の後: reload-after-server-reload が true なら実行（初期値 true）
 * - サーバーの起動時: reload-on-startup が true なら実行（初期値 false。起動時は BetterModel 自身が読み込むので普通は要らない）
 *
 * BetterModel の reload() を直接呼ぶとメインスレッドで重い処理が走ることがあるので、
 * `/bettermodel reload` と同じくコンソールからコマンドを実行する（処理の分け方はコマンド側に任せる）。
 * ServerLoadEvent は全プラグインの読み込みが終わった後に来るので、BetterModel の準備も済んでいる。
 */
class BetterModelAutoReload(private val plugin: EastBossPlugin) : Listener {

    @EventHandler
    fun onServerLoad(e: ServerLoadEvent) {
        val config = plugin.configManager.main
        val enabled = when (e.type) {
            ServerLoadEvent.LoadType.RELOAD -> config.boolean(Names.Config.BETTERMODEL_RELOAD_AFTER_SERVER_RELOAD, true)
            ServerLoadEvent.LoadType.STARTUP -> config.boolean(Names.Config.BETTERMODEL_RELOAD_ON_STARTUP, false)
        }
        if (!enabled) return

        val command = config.string(Names.Config.BETTERMODEL_RELOAD_COMMAND, "bettermodel reload").trim().removePrefix("/")
        if (command.isEmpty()) return
        val delay = config.int(Names.Config.BETTERMODEL_RELOAD_DELAY_TICKS, 20).coerceAtLeast(1).toLong()
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            plugin.logger.info("サーバーの読み込みが終わったので「/$command」を実行します（${e.type}）")
            val ok = plugin.server.dispatchCommand(plugin.server.consoleSender, command)
            if (!ok) plugin.logger.warning("「/$command」を実行できませんでした（BetterModel が入っているか確認してください）")
        }, delay)
    }
}
