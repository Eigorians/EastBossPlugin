package com.eastcompany.eastsub.eastBossPlugin.location

/**
 * 設定できる座標の種類。
 * @property id コマンド引数や locations.yml のキーに使う名前
 * @property displayName メッセージ表示用の名前
 */
enum class PointType(val id: String, val displayName: String) {
    /** 旅商人（ボス召喚 NPC）を立たせる位置 */
    TRADER("trader", "Eastボス"),

    /** ボスを召喚する位置 */
    BOSS("boss", "ボス召喚地点"),
    ;

    companion object {
        fun fromId(id: String): PointType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
