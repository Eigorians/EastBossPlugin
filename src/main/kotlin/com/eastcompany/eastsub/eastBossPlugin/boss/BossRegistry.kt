package com.eastcompany.eastsub.eastBossPlugin.boss

/**
 * ボスの種類を ID で管理する。
 * 登録は bosses パッケージの Bosses.registerAll でまとめて行う。
 */
class BossRegistry {

    private val types = linkedMapOf<String, BossType>()

    fun register(type: BossType) {
        val id = type.id.lowercase()
        require(id !in types) { "ボスID '$id' は登録済みです" }
        types[id] = type
    }

    fun get(id: String): BossType? = types[id.lowercase()]

    fun ids(): Set<String> = types.keys

    fun all(): Collection<BossType> = types.values
}
