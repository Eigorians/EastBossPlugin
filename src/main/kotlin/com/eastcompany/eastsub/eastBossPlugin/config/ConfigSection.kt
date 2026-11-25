package com.eastcompany.eastsub.eastBossPlugin.config

/**
 * [ConfigFile] の一部（セクション）を、相対キーで読むためのもの。
 * 技の設定など「skills.dash.range」のようなパスを毎回組み立てずに済む。
 *
 * 使用例:
 * ```
 * val dash = ConfigSection(file, "skills.dash")
 * val range = dash.double("range", 12.0)   // skills.dash.range
 * ```
 */
class ConfigSection(val file: ConfigFile, val path: String) {

    /** 相対キーをファイル内の絶対パスにする */
    fun fullKey(key: String): String = if (path.isEmpty()) key else "$path.$key"

    /** さらに下のセクション */
    fun section(key: String): ConfigSection = ConfigSection(file, fullKey(key))

    fun contains(key: String): Boolean = file.contains(fullKey(key))

    fun string(key: String, def: String = ""): String = file.string(fullKey(key), def)

    fun int(key: String, def: Int = 0): Int = file.int(fullKey(key), def)

    fun double(key: String, def: Double = 0.0): Double = file.double(fullKey(key), def)

    fun float(key: String, def: Float = 0f): Float = file.double(fullKey(key), def.toDouble()).toFloat()

    fun boolean(key: String, def: Boolean = false): Boolean = file.boolean(fullKey(key), def)

    fun stringList(key: String, def: List<String> = emptyList()): List<String> = file.stringList(fullKey(key), def)

    inline fun <reified T : Enum<T>> enum(key: String, def: T): T = file.enum(fullKey(key), def)

    /**
     * tick の区間のリストを読む。各要素は "開始-終了"（両端を含む）か、単独の数字。
     * 例: `hit-windows: ["30-51", "140-155"]`
     * 読めない要素は警告を出して飛ばす。
     */
    fun intRanges(key: String, def: List<IntRange> = emptyList()): List<IntRange> {
        if (!contains(key)) return def
        return stringList(key).mapNotNull { raw ->
            parseRange(raw) ?: run {
                file.warnInvalid(fullKey(key), "\"開始-終了\" 形式の区間")
                null
            }
        }
    }

    private fun parseRange(raw: String): IntRange? {
        val parts = raw.split('-').map { it.trim() }
        return when (parts.size) {
            1 -> parts[0].toIntOrNull()?.let { it..it }
            2 -> {
                val start = parts[0].toIntOrNull() ?: return null
                val end = parts[1].toIntOrNull() ?: return null
                if (start <= end) start..end else null
            }
            else -> null
        }
    }
}
