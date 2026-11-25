package com.eastcompany.eastsub.eastBossPlugin.battle

import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 旅商人から始めた1回分のボス戦。参加者と、1人ごとの倒れた回数を持つ。
 *
 * - 参加者は1人 [deathLimit] 回倒れると脱落する（このボス戦にはもう加われない。ボスに狙われず、ボスを攻撃もできない）
 * - 参加者が全員脱落したら敗北
 * - ボスはこれを持っている間、脱落していない参加者だけを狙う（[isParticipant]）
 */
class BossBattle(
    /** 1人あたり、この回数倒れると脱落する */
    val deathLimit: Int,
    /** 募集を始めた人（旅商人からボス戦を中止できる） */
    val leader: UUID,
) {

    /** 参加した人全員（脱落した人も含む・参加順） */
    private val members = LinkedHashSet<UUID>()

    /** 1人ごとの倒れた回数 */
    private val deathCounts = HashMap<UUID, Int>()

    /** 脱落した人 */
    private val eliminated = HashSet<UUID>()

    /** 参加者ごとの、ボスに与えたダメージ（実際に減った HP。BossListener が数える） */
    private val damageDealt = HashMap<UUID, Double>()
    /** 与ダメージの表示に使う名前（参加したときの名前） */
    private val names = HashMap<UUID, String>()

    /** ボス出現からの経過 tick（クリアタイム。BattleManager が毎tick進める） */
    var elapsedTicks = 0
        internal set

    /** 1人の死亡を数えた結果 */
    data class DeathResult(
        /** この人の倒れた回数 */
        val count: Int,
        /** この死亡で脱落したか */
        val eliminated: Boolean,
        /** 全員が脱落したか（敗北） */
        val allEliminated: Boolean,
    )

    /** @return 新しく参加したら true（参加済みなら false） */
    fun join(player: Player): Boolean {
        names[player.uniqueId] = player.name
        return members.add(player.uniqueId)
    }

    /** 参加者がボスに与えたダメージを足す（参加者でなければ数えない） */
    fun addDamage(player: UUID, amount: Double) {
        if (amount <= 0 || player !in members) return
        damageDealt.merge(player, amount, Double::plus)
    }

    /** 与ダメージの1行 */
    data class DamageEntry(val uuid: UUID, val name: String, val damage: Double, val ratio: Double)

    /** 参加者全員の与ダメージ（多い順。与えていない人も 0 で入る） */
    fun damageRanking(): List<DamageEntry> {
        val total = damageDealt.values.sum()
        return members.map { id ->
            val d = damageDealt[id] ?: 0.0
            DamageEntry(id, names[id] ?: id.toString().take(8), d, if (total > 0) d / total else 0.0)
        }.sortedByDescending { it.damage }
    }

    /**
     * 戦っている参加者か（参加していて、脱落していない）。
     * ボスの標的・ダメージのやり取り・装備の耐久などはすべてこれで判定する
     */
    fun isParticipant(entity: Entity): Boolean = isParticipant(entity.uniqueId)

    /** 同上（火を付けた後にログアウトした人など、Player が手に入らないとき用） */
    fun isParticipant(uuid: UUID): Boolean = uuid in members && uuid !in eliminated

    /** 参加したことがあるか（脱落した人も含む。死亡ログ・知らせを届ける相手） */
    fun isMember(entity: Entity): Boolean = entity.uniqueId in members

    fun isEliminated(uuid: UUID): Boolean = uuid in eliminated

    fun isLeader(entity: Entity): Boolean = entity.uniqueId == leader

    /** 参加した人数（脱落した人も含む） */
    val participantCount: Int get() = members.size

    /** 参加した人全員の UUID（オフライン・脱落した人も含む・参加順） */
    val participantIds: List<UUID> get() = members.toList()

    /** 脱落していない人の UUID（勝ったときに討伐した扱いにする人） */
    val survivorIds: List<UUID> get() = members.filter { it !in eliminated }

    /** オンラインの参加者（脱落した人も含む。知らせを送る相手） */
    fun onlineParticipants(server: Server): List<Player> = members.mapNotNull { server.getPlayer(it) }

    /** オンラインで、まだ戦っている参加者 */
    fun activeParticipants(server: Server): List<Player> =
        members.filter { it !in eliminated }.mapNotNull { server.getPlayer(it) }

    /** その人の倒れた回数 */
    fun deathsOf(uuid: UUID): Int = deathCounts[uuid] ?: 0

    /** 参加者の死亡を1回数える */
    fun addDeath(player: Player): DeathResult {
        val id = player.uniqueId
        val count = deathsOf(id) + 1
        deathCounts[id] = count
        val out = count >= deathLimit && eliminated.add(id)
        return DeathResult(count, out, members.all { it in eliminated })
    }
}
