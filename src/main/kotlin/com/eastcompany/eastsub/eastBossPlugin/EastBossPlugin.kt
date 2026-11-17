package com.eastcompany.eastsub.eastBossPlugin

import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import com.eastcompany.eastsub.eastBossPlugin.hook.BetterModelHook
import org.bukkit.plugin.java.JavaPlugin

class EastBossPlugin : JavaPlugin() {

    lateinit var configManager: ConfigManager
        private set

    lateinit var betterModel: BetterModelHook
        private set

    override fun onEnable() {
        // BetterModel が使えなければ何もせずに停止する
        // （通常は paper-plugin.yml の required 指定で読み込み自体が止まるが、念のため確認する）
        betterModel = BetterModelHook(this)
        if (!betterModel.enable()) {
            server.pluginManager.disablePlugin(this)
            return
        }

        configManager = ConfigManager(this)
        // 起動時に config.yml を生成・読み込みしておく
        if (configManager.main.boolean("debug")) {
            logger.info("デバッグモードで起動しました")
        }
    }

    override fun onDisable() {
        // 起動途中で停止した場合は未初期化のことがあるので確認してから片付ける
        if (::betterModel.isInitialized) {
            betterModel.disable()
        }
    }
}
