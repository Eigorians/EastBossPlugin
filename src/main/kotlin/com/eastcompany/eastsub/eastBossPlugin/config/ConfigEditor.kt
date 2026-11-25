package com.eastcompany.eastsub.eastBossPlugin.config

import org.bukkit.configuration.ConfigurationSection

/**
 * コマンド（/eb config）から設定ファイルの値を書き換える。
 *
 * - 書き換えられるのは、ファイル（またはjar同梱の初期値）にもうあるキーだけ（打ち間違いで新しいキーを作らない）
 * - 値の型は今の値に合わせる: true/false・数・リスト（[a, b] か a, b）・文字列（"…" で囲んでもよい）
 * - 書き換えたらファイルに保存し、読み込み直す（config.yml はすぐ反映。ボスの設定は次に召喚したボスから）
 */
object ConfigEditor {

    sealed interface Result {
        data class Done(val value: Any) : Result
        data object UnknownKey : Result
        /** セクション（下にキーがある所）は書き換えない */
        data object Section : Result
        /** 型が合わない（expected = 求める型の説明） */
        data class Invalid(val expected: String) : Result
    }

    /** 書き換えられるキーの一覧（セクションは除く。ファイルとjar同梱の初期値の両方から） */
    fun keys(file: ConfigFile): List<String> {
        val yaml = file.yaml
        val keys = LinkedHashSet<String>()
        keys += yaml.getKeys(true)
        yaml.defaults?.getKeys(true)?.let { keys += it }
        return keys.filter { !yaml.isConfigurationSection(it) }.sorted()
    }

    /** 値の補完（今の値と、true/false） */
    fun suggestions(file: ConfigFile, key: String): List<String> {
        val current = file.yaml.get(key) ?: return emptyList()
        if (current is ConfigurationSection) return emptyList()
        return when (current) {
            is Boolean -> listOf("true", "false")
            else -> listOf(format(current))
        }
    }

    fun set(file: ConfigFile, key: String, raw: String): Result {
        val yaml = file.yaml
        if (!yaml.contains(key)) return Result.UnknownKey
        if (yaml.isConfigurationSection(key)) return Result.Section

        val text = raw.trim()
        val value: Any = when (val current = yaml.get(key)) {
            is Boolean -> when (text.lowercase()) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> return Result.Invalid("true / false")
            }
            is Number -> parseNumber(text) ?: return Result.Invalid("数")
            is List<*> -> parseList(text)
            else -> {
                if (current != null && current !is String) return Result.Invalid(current.javaClass.simpleName)
                unquote(text)
            }
        }
        yaml.set(key, value)
        file.save()
        file.reload()
        return Result.Done(value)
    }

    /** 表示用の文字列 */
    fun format(value: Any?): String = when (value) {
        null -> "（なし）"
        is List<*> -> value.joinToString(", ", "[", "]")
        else -> value.toString()
    }

    /** 小数点があれば小数、無ければ整数 */
    private fun parseNumber(text: String): Number? =
        if ('.' in text || 'e' in text.lowercase()) text.toDoubleOrNull()
        else text.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }

    /** "[a, b, 3]" や "a, b, 3" をリストに。数に見えるものは数にする */
    private fun parseList(text: String): List<Any> {
        val inner = text.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            parseNumber(part) ?: unquote(part)
        }
    }

    private fun unquote(text: String): String =
        if (text.length >= 2 && ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith('\'') && text.endsWith('\''))))
            text.substring(1, text.length - 1)
        else text
}
