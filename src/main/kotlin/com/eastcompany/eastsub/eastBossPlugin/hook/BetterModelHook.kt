package com.eastcompany.eastsub.eastBossPlugin.hook

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bukkit.BetterModelBukkit
import kr.toxicity.model.api.data.renderer.ModelRenderer
import kr.toxicity.model.api.event.ModelEventListener
import kr.toxicity.model.api.event.PluginEndReloadEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * BetterModel との連携窓口。
 * BetterModel の API はこのクラス経由で触るようにし、他の場所から直接依存しないようにする。
 */
class BetterModelHook(private val plugin: JavaPlugin) {

    /** BetterModel のリロード完了時に呼ばれる処理（ボスのモデル再適用などに使う） */
    private val reloadCallbacks = mutableListOf<() -> Unit>()

    private var reloadListener: ModelEventListener? = null

    /**
     * BetterModel が有効か確認し、イベント購読を開始する。
     * @return 連携できたら true。false の場合プラグインは起動を中止すべき
     */
    fun enable(): Boolean {
        val bmPlugin = plugin.server.pluginManager.getPlugin(PLUGIN_NAME)
        if (bmPlugin == null || !bmPlugin.isEnabled) {
            plugin.logger.severe("$PLUGIN_NAME が見つからないか、有効になっていません。EastBossPlugin を停止します")
            return false
        }

        val platform = BetterModelBukkit.platform()
        plugin.logger.info("$PLUGIN_NAME ${platform.semver()} と連携しました（読み込み済みモデル: ${BetterModel.modelKeys().size} 件）")

        // BetterModel のリロード（/bm reload）完了を受け取る
        reloadListener = platform.eventBus().subscribe(plugin, PluginEndReloadEvent::class.java) {
            // イベントはメインスレッド以外から来る可能性があるので、メインスレッドに戻して処理する
            plugin.server.scheduler.runTask(plugin, Runnable {
                reloadCallbacks.forEach { callback -> runCatching(callback).onFailure { e ->
                    plugin.logger.warning("BetterModel リロード後の処理で例外が発生しました: ${e.message}")
                } }
            })
        }
        return true
    }

    /** イベント購読を解除する */
    fun disable() {
        reloadListener?.unregister()
        reloadListener = null
        reloadCallbacks.clear()
    }

    /** BetterModel のリロード完了時に実行する処理を登録する */
    fun onReload(callback: () -> Unit) {
        reloadCallbacks += callback
    }

    /** モデルを名前で取得する。存在しなければ null */
    fun model(name: String): ModelRenderer? = BetterModel.modelOrNull(name)

    /** 指定した名前のモデルが読み込まれているか */
    fun hasModel(name: String): Boolean = name in BetterModel.modelKeys()

    companion object {
        const val PLUGIN_NAME = "BetterModel"
    }
}
