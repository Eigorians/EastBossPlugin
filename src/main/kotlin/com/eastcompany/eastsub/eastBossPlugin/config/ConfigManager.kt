package com.eastcompany.eastsub.eastBossPlugin.config

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * 設定ファイルをまとめて管理するクラス。
 * 設定値は必ずここ経由で取得する（JavaPlugin#getConfig は使わない）。
 *
 * 使用例:
 * ```
 * val prefix = configManager.main.component("prefix")
 * val golem = configManager.file("bosses/golem.yml")
 * ```
 */
class ConfigManager(private val plugin: JavaPlugin) {

    private val files = linkedMapOf<String, ConfigFile>()

    /** メインの config.yml */
    val main: ConfigFile get() = file("config.yml")

    /** 指定ファイルを取得する。初回アクセス時に読み込む */
    fun file(name: String): ConfigFile =
        files.getOrPut(name) { ConfigFile(plugin, name).also { it.reload() } }

    /**
     * データフォルダ内のディレクトリにある .yml をすべて読み込む（ボスごとの設定ファイル向け）。
     * 新しく追加されたファイルを拾うため、リロード時もこれを呼び直す。
     */
    fun loadDirectory(dir: String): List<ConfigFile> {
        val folder = File(plugin.dataFolder, dir)
        folder.mkdirs()
        return folder.listFiles { f -> f.isFile && f.extension == "yml" }
            .orEmpty()
            .sortedBy { it.name }
            .map { file("$dir/${it.name}") }
    }

    /**
     * 読み込み済みの全ファイルを読み込み直す。
     * @return 全ファイル成功なら true
     */
    fun reloadAll(): Boolean = files.values.map { it.reload() }.all { it }
}
