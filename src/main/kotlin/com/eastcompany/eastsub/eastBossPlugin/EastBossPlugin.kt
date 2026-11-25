package com.eastcompany.eastsub.eastBossPlugin

import com.eastcompany.eastsub.eastBossPlugin.battle.BattleListener
import com.eastcompany.eastsub.eastBossPlugin.battle.BattleManager
import com.eastcompany.eastsub.eastBossPlugin.boss.BossListener
import com.eastcompany.eastsub.eastBossPlugin.boss.BossManager
import com.eastcompany.eastsub.eastBossPlugin.boss.BossRegistry
import com.eastcompany.eastsub.eastBossPlugin.bosses.Bosses
import com.eastcompany.eastsub.eastBossPlugin.command.EastBossCommand
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import com.eastcompany.eastsub.eastBossPlugin.hook.BetterModelAutoReload
import com.eastcompany.eastsub.eastBossPlugin.hook.BetterModelHook
import com.eastcompany.eastsub.eastBossPlugin.location.LocationManager
import com.eastcompany.eastsub.eastBossPlugin.menu.BossSelectMenu
import com.eastcompany.eastsub.eastBossPlugin.menu.MenuListener
import com.eastcompany.eastsub.eastBossPlugin.message.Messages
import com.eastcompany.eastsub.eastBossPlugin.npc.TraderListener
import com.eastcompany.eastsub.eastBossPlugin.npc.TraderNpc
import com.eastcompany.eastsub.eastBossPlugin.progress.ProgressManager
import com.eastcompany.eastsub.eastBossPlugin.record.RecordManager
import com.eastcompany.eastsub.eastBossPlugin.status.StatusListener
import com.eastcompany.eastsub.eastBossPlugin.status.StatusManager
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class EastBossPlugin : JavaPlugin() {

    lateinit var configManager: ConfigManager
        private set

    lateinit var betterModel: BetterModelHook
        private set

    lateinit var messages: Messages
        private set

    lateinit var locationManager: LocationManager
        private set

    lateinit var traderNpc: TraderNpc
        private set

    lateinit var status: StatusManager
        private set

    lateinit var bossRegistry: BossRegistry
        private set

    lateinit var bossManager: BossManager
        private set

    lateinit var battleManager: BattleManager
        private set

    lateinit var recordManager: RecordManager
        private set

    /** ボスの解放（順番に倒していく進行） */
    lateinit var progressManager: ProgressManager
        private set

    private var tickTask: BukkitTask? = null

    override fun onEnable() {
        // BetterModel が使えなければ何もせずに停止する
        // （通常は paper-plugin.yml の required 指定で読み込み自体が止まるが、念のため確認する）
        betterModel = BetterModelHook(this)
        if (!betterModel.enable()) {
            server.pluginManager.disablePlugin(this)
            return
        }

        configManager = ConfigManager(this)
        messages = Messages(configManager)
        locationManager = LocationManager(server, configManager)
        traderNpc = TraderNpc(server, configManager)
        status = StatusManager(server, configManager)

        bossRegistry = BossRegistry()
        Bosses.registerAll(bossRegistry)
        // 各ボスの設定ファイルを先に生成・読み込みしておく（/eb reload の対象にもなる）
        bossRegistry.all().forEach { configManager.file(it.configPath) }
        bossManager = BossManager(this)
        recordManager = RecordManager(configManager)
        progressManager = ProgressManager(configManager, bossRegistry)
        battleManager = BattleManager(this)

        server.pluginManager.registerEvents(
            TraderListener(traderNpc, messages, ::debug) { player -> BossSelectMenu(this, player).open() }, this
        )
        server.pluginManager.registerEvents(MenuListener(battleManager, messages), this)
        server.pluginManager.registerEvents(BattleListener(battleManager), this)
        server.pluginManager.registerEvents(StatusListener(status), this)
        server.pluginManager.registerEvents(BossListener(bossManager), this)
        // サーバーの /reload の後に、自動で /bettermodel reload を実行する
        server.pluginManager.registerEvents(BetterModelAutoReload(this), this)

        // BetterModel をリロードするとモデルが作り直されるので、召喚中のボスは消す
        betterModel.onReload {
            val count = bossManager.removeAll()
            if (count > 0) logger.info("BetterModel がリロードされたため、ボスを $count 体削除しました")
        }

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(EastBossCommand(this).build(), "EastBoss の管理コマンド", Names.Command.ALIASES)
        }

        tickTask = server.scheduler.runTaskTimer(this, Runnable {
            status.tick()
            bossManager.tickAll()
            battleManager.tick()
        }, 1L, 1L)

        debug("デバッグモードで起動しました（ボス ${bossRegistry.ids().size} 種類）")
    }

    override fun onDisable() {
        tickTask?.cancel()
        tickTask = null

        // 起動途中で停止した場合は未初期化のことがあるので確認してから片付ける
        // 全ボスのエンティティとモデルを必ず片付ける
        if (::battleManager.isInitialized) battleManager.cancel()
        if (::bossManager.isInitialized) bossManager.removeAll()
        if (::status.isInitialized) status.clear()
        if (::betterModel.isInitialized) betterModel.disable()
    }

    /** デバッグモード中か（config.yml の debug.enabled） */
    fun isDebug(): Boolean =
        ::configManager.isInitialized && configManager.main.boolean(Names.Config.DEBUG_ENABLED)

    /** デバッグモードを切り替えて config.yml に保存する（再起動後も残る） */
    fun setDebug(enabled: Boolean) {
        val main = configManager.main
        main.set(Names.Config.DEBUG_ENABLED, enabled)
        main.save()
    }

    /** デバッグモード中だけログを出す */
    fun debug(message: String) {
        if (isDebug()) logger.info("[DEBUG] $message")
    }
}
