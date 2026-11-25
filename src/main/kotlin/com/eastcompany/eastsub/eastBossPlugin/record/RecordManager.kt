package com.eastcompany.eastsub.eastBossPlugin.record

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigFile
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import java.util.UUID

/**
 * ボスごとのクリアタイムの記録（records.yml）。記録は2種類ある（[Kind]）。
 *
 * - ソロ記録: 1人で討伐したときのランキング。プレイヤーごとの自己ベストもソロで取る
 * - 集団討伐: 2人以上で討伐したときのランキング（記録の保持者は、そのボス戦に参加した全員）
 *
 * ランキングは速い順に config の record.ranking-size 件まで（同じ人でも記録ごとに載る）。
 * 記録の保持者は名前と UUID の両方で残す（表示は名前、リセットは UUID で探す。古い記録は名前だけ）。
 * タイムは tick 数で持つ（ラグでボスの動きが遅くなっても公平になるように）。表示は [TimeFormat]。
 *
 * records.yml の形:
 * ```
 * <ボスID>:
 *   ranking:            # ソロ
 *     - { time: 1234, players: [name], uuids: [uuid], date: 1700000000000 }
 *   group-ranking:      # 集団討伐
 *     - { time: 2345, players: [name1, name2], uuids: [uuid1, uuid2], date: 1700000000000 }
 *   personal:           # ソロの自己ベスト
 *     <UUID>: 1234
 * ```
 */
class RecordManager(private val configManager: ConfigManager) {

    /** 記録の種類 */
    enum class Kind(val path: String) {
        /** 1人で討伐 */
        SOLO("ranking"),
        /** 2人以上で討伐 */
        GROUP("group-ranking"),
    }

    /** ランキングの1件（players = 記録の保持者の名前、uuids = 同じ順の UUID。古い記録では空） */
    data class ClearRecord(val ticks: Int, val players: List<String>, val uuids: List<UUID>, val date: Long) {
        /** この記録の保持者か */
        fun isHolder(uuid: UUID, name: String): Boolean =
            uuid in uuids || (uuids.isEmpty() && players.any { it.equals(name, ignoreCase = true) })
    }

    /**
     * 記録を登録した結果。
     * @param rank ランキングに入った順位（1始まり。圏外なら null）
     * @param newBests 自己ベストを更新した人 → 前回の自己ベスト（初クリアなら null。ソロのときだけ）
     */
    data class Submission(val rank: Int?, val newBests: Map<UUID, Int?>)

    private val file: ConfigFile get() = configManager.file(Names.File.RECORDS)

    private val rankingSize: Int
        get() = configManager.main.int(Names.Config.RECORD_RANKING_SIZE, 10).coerceAtLeast(1)

    /** 速い順のランキング */
    fun ranking(bossId: String, kind: Kind = Kind.SOLO): List<ClearRecord> =
        file.yaml.getMapList(rankingPath(bossId, kind)).mapNotNull { map ->
            val ticks = (map["time"] as? Number)?.toInt() ?: return@mapNotNull null
            val players = (map["players"] as? List<*>)?.map { it.toString() }.orEmpty()
            val uuids = (map["uuids"] as? List<*>)?.mapNotNull { runCatching { UUID.fromString(it.toString()) }.getOrNull() }.orEmpty()
            val date = (map["date"] as? Number)?.toLong() ?: 0L
            ClearRecord(ticks, players, uuids, date)
        }.sortedBy { it.ticks }

    /** 一番速い記録（記録の保持者つき。無ければ null） */
    fun best(bossId: String, kind: Kind): ClearRecord? = ranking(bossId, kind).firstOrNull()

    /** ソロの自己ベスト（記録が無ければ null） */
    fun personalBest(bossId: String, player: UUID): Int? {
        val path = personalPath(bossId, player)
        return if (file.contains(path)) file.int(path) else null
    }

    /**
     * クリアを記録して保存する。参加者が1人ならソロ、2人以上なら集団討伐のランキングに載せる。
     * 自己ベストはソロのときだけ更新する。
     * @param players 記録の保持者（UUID → 表示する名前。参加順）
     */
    fun submit(bossId: String, ticks: Int, players: Map<UUID, String>): Submission {
        val kind = if (players.size <= 1) Kind.SOLO else Kind.GROUP
        val record = ClearRecord(ticks, players.values.toList(), players.keys.toList(), System.currentTimeMillis())
        val ranking = (ranking(bossId, kind) + record)
            .sortedWith(compareBy<ClearRecord> { it.ticks }.thenBy { it.date })
            .take(rankingSize)
        val index = ranking.indexOfFirst { it === record }
        writeRanking(bossId, kind, ranking)

        val newBests = LinkedHashMap<UUID, Int?>()
        if (kind == Kind.SOLO) {
            for (uuid in players.keys) {
                val previous = personalBest(bossId, uuid)
                if (previous != null && previous <= ticks) continue
                file.set(personalPath(bossId, uuid), ticks)
                newBests[uuid] = previous
            }
        }

        file.save()
        return Submission(if (index >= 0) index + 1 else null, newBests)
    }

    /** そのボスの記録（ソロ・集団・全員の自己ベスト）を消す */
    fun reset(bossId: String) {
        file.set(bossId, null)
        file.save()
    }

    /** すべてのボスの記録を消す */
    fun resetAll() {
        for (key in file.keys()) file.set(key, null)
        file.save()
    }

    /**
     * そのボスの、1人分の記録を消す: 自己ベストと、その人が保持者になっているランキング（ソロ・集団の両方）。
     * @return 消したものがあれば true
     */
    fun resetPlayer(bossId: String, player: UUID, name: String): Boolean {
        var changed = false
        val path = personalPath(bossId, player)
        if (file.contains(path)) {
            file.set(path, null)
            changed = true
        }
        for (kind in Kind.entries) {
            val ranking = ranking(bossId, kind)
            val kept = ranking.filterNot { it.isHolder(player, name) }
            if (kept.size != ranking.size) {
                writeRanking(bossId, kind, kept)
                changed = true
            }
        }
        if (changed) file.save()
        return changed
    }

    /** すべてのボスの、1人分の記録を消す */
    fun resetPlayerAll(player: UUID, name: String) {
        for (bossId in file.keys()) resetPlayer(bossId, player, name)
    }

    private fun writeRanking(bossId: String, kind: Kind, ranking: List<ClearRecord>) {
        file.set(rankingPath(bossId, kind), ranking.map {
            mapOf("time" to it.ticks, "players" to it.players, "uuids" to it.uuids.map(UUID::toString), "date" to it.date)
        })
    }

    private fun rankingPath(bossId: String, kind: Kind) = "$bossId.${kind.path}"
    private fun personalPath(bossId: String, player: UUID) = "$bossId.personal.$player"
}
