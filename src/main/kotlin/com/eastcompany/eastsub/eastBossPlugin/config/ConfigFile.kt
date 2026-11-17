package com.eastcompany.eastsub.eastBossPlugin.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.logging.Level

/**
 * プラグインのデータフォルダにある YAML ファイル1つを表すクラス。
 *
 * - ファイルが無ければ jar 同梱のものをコピーする（同梱が無ければ空ファイルを作る）
 * - jar 同梱のファイルをデフォルト値として読み込むので、ユーザー側のファイルに
 *   キーが無くても同梱の値が返る（アップデートで増えたキーにも対応できる）
 * - 読み込みに失敗した場合は直前の内容を保持し、サーバーを止めない
 */
class ConfigFile internal constructor(
    private val plugin: JavaPlugin,
    /** データフォルダからの相対パス（例: "config.yml", "bosses/golem.yml"） */
    val name: String,
) {
    private val file = File(plugin.dataFolder, name)

    /** 読み込み済みの YAML。直接触るのは特殊なケースだけにする */
    var yaml: YamlConfiguration = YamlConfiguration()
        private set

    /**
     * ファイルを読み込み直す。
     * @return 成功したら true。失敗時は直前の内容のまま false を返す
     */
    fun reload(): Boolean {
        ensureFileExists()

        val loaded = YamlConfiguration()
        try {
            loaded.load(file)
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "$name の読み込みに失敗しました", e)
            return false
        } catch (e: InvalidConfigurationException) {
            plugin.logger.severe("$name の YAML 構文が不正です。前回の内容を使い続けます: ${e.message}")
            return false
        }

        // jar 同梱のファイルをデフォルト値として設定
        plugin.getResource(name)?.use { stream ->
            loaded.setDefaults(YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8)))
        }

        yaml = loaded
        return true
    }

    /** 現在の内容をファイルに書き出す */
    fun save() {
        try {
            yaml.save(file)
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "$name の保存に失敗しました", e)
        }
    }

    private fun ensureFileExists() {
        if (file.exists()) return
        if (plugin.getResource(name) != null) {
            plugin.saveResource(name, false)
        } else {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
    }

    // ---- 値の取得 ----
    // どれも「キーが無い・型が違う」ときは def を返す。型違いは警告ログを出す。

    fun contains(path: String): Boolean = yaml.contains(path)

    fun string(path: String, def: String = ""): String =
        typed(path, def) { it as? String ?: (it as? Number ?: it as? Boolean)?.toString() }

    fun int(path: String, def: Int = 0): Int = typed(path, def) { (it as? Number)?.toInt() }

    fun long(path: String, def: Long = 0L): Long = typed(path, def) { (it as? Number)?.toLong() }

    fun double(path: String, def: Double = 0.0): Double = typed(path, def) { (it as? Number)?.toDouble() }

    fun boolean(path: String, def: Boolean = false): Boolean = typed(path, def) { it as? Boolean }

    fun stringList(path: String, def: List<String> = emptyList()): List<String> =
        typed(path, def) { value -> (value as? List<*>)?.map { it.toString() } }

    /** 大文字小文字を区別せずに enum に変換する */
    inline fun <reified T : Enum<T>> enum(path: String, def: T): T {
        val raw = string(path, def.name)
        return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: run {
            warnInvalid(path, "${T::class.simpleName} のいずれか")
            def
        }
    }

    /** MiniMessage 形式の文字列を Component に変換して返す */
    fun component(path: String, vararg resolvers: TagResolver, def: String = ""): Component =
        MiniMessage.miniMessage().deserialize(string(path, def), *resolvers)

    /** 子セクションを返す。無ければ null */
    fun section(path: String): ConfigurationSection? = yaml.getConfigurationSection(path)

    /** 指定セクション直下のキー一覧（path 省略でルート直下） */
    fun keys(path: String? = null): Set<String> =
        (if (path == null) yaml else section(path))?.getKeys(false) ?: emptySet()

    /** 値を書き換える。ファイルに反映するには save() を呼ぶ */
    fun set(path: String, value: Any?) = yaml.set(path, value)

    private inline fun <T> typed(path: String, def: T, convert: (Any) -> T?): T {
        val raw = yaml.get(path) ?: return def
        return convert(raw) ?: run {
            warnInvalid(path, "正しい型")
            def
        }
    }

    @PublishedApi
    internal fun warnInvalid(path: String, expected: String) {
        plugin.logger.warning("$name の '$path' の値が不正です（$expected を指定してください）。デフォルト値を使います")
    }
}
