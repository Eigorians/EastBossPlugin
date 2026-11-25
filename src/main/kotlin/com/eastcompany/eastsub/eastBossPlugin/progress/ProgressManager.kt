package com.eastcompany.eastsub.eastBossPlugin.progress

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.BossRegistry
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSettings
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigFile
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * ボスの解放（順番に倒していく進行）。プレイヤーごとに倒したボスを progress.yml に残す。
 *
 * - config.yml の progression.order の順に挑む。前のボスを倒すまで、次のボスのボス戦は始められない
 *   （ほかの人が始めたボス戦に参加するのは自由。参加して勝てば、その人も倒したことになる）
 * - order に無いボスは、最初から挑める
 * - progression.enabled が false なら、全部最初から挑める
 * - 権限 eastboss.progress.bypass（OP）を持つ人は、いつでも全部選べる
 *
 * 倒したことになるのは、旅商人からのボス戦で勝ったときの参加者全員（ソロでも複数人でも）。
 *
 * progress.yml の形:
 * ```
 * <UUID>: [tutorial_alpha, gunner]
 * ```
 */
class ProgressManager(private val configManager: ConfigManager, private val registry: BossRegistry) {

    private val file: ConfigFile get() = configManager.file(Names.File.PROGRESS)
    private val config get() = configManager.main

    /** 進行（順番に倒す）が有効か */
    val enabled: Boolean get() = config.boolean(Names.Config.PROGRESSION_ENABLED, true)

    /** 挑む順番（登録されているボスだけ） */
    fun order(): List<String> =
        config.stringList(Names.Config.PROGRESSION_ORDER).map { it.lowercase() }.filter { registry.get(it) != null }

    /** 倒したボスの ID */
    fun cleared(player: UUID): Set<String> = file.stringList(player.toString()).map { it.lowercase() }.toSet()

    fun hasCleared(player: UUID, bossId: String): Boolean = bossId.lowercase() in cleared(player)

    /** 順番に関係なく選べる人か（OP） */
    fun canBypass(player: Player): Boolean =
        player.hasPermission(Names.Permission.PROGRESS_BYPASS) || player.hasPermission(Names.Permission.ADMIN)

    /**
     * このボスの戦いを始められるか（解放されているか）。
     * 進行が無効・OP・順番に無いボス・最初のボス・前のボスを倒している、のどれかなら true
     */
    fun isUnlocked(player: Player, type: BossType): Boolean {
        if (!enabled || canBypass(player)) return true
        val prev = previousOf(type) ?: return true
        return hasCleared(player.uniqueId, prev)
    }

    /** 順番で1つ前のボスの ID（最初のボス・順番に無いボスなら null） */
    fun previousOf(type: BossType): String? {
        val order = order()
        val index = order.indexOf(type.id.lowercase())
        return if (index <= 0) null else order[index - 1]
    }

    /** 順番で1つ後のボス（最後・順番に無いなら null） */
    fun nextOf(bossId: String): BossType? {
        val order = order()
        val index = order.indexOf(bossId.lowercase())
        if (index < 0 || index + 1 >= order.size) return null
        return registry.get(order[index + 1])
    }

    /**
     * 倒したことにする。
     * @return 初めて倒した人
     */
    fun markCleared(players: Collection<UUID>, bossId: String): List<UUID> {
        val id = bossId.lowercase()
        val first = players.filter { id !in cleared(it) }
        for (uuid in first) file.set(uuid.toString(), (cleared(uuid) + id).toList())
        if (first.isNotEmpty()) file.save()
        return first
    }

    /** まだ解放されていないボスの名前の代わり（progression.hidden-name） */
    fun hiddenName(): Component = config.component(Names.Config.PROGRESSION_HIDDEN_NAME, def = "<dark_gray>???")

    /** まだ解放されていないボスのアイコン（progression.hidden-icon） */
    fun hiddenIcon(): Material =
        Material.matchMaterial(config.string(Names.Config.PROGRESSION_HIDDEN_ICON, "GRAY_DYE"))?.takeIf { it.isItem }
            ?: Material.GRAY_DYE

    /** そのプレイヤーに見せるボスの名前（解放されていなければ伏せる） */
    fun visibleName(player: Player, type: BossType): Component =
        if (isUnlocked(player, type)) displayName(type) else hiddenName()

    /**
     * 前のボスの名前（そのプレイヤーに見せる形）。前のボスも解放されていなければ伏せる。
     * 前のボスが無ければ null
     */
    fun previousName(player: Player, type: BossType): Component? {
        val prev = previousOf(type)?.let { registry.get(it) } ?: return null
        return visibleName(player, prev)
    }

    fun displayName(type: BossType): Component = BossSettings.load(configManager.file(type.configPath)).displayName

    /** 倒したことにする（コマンド用）。bossId が null なら、順番の全部 */
    fun grant(player: UUID, bossId: String?) {
        val ids = bossId?.let { listOf(it.lowercase()) } ?: order()
        file.set(player.toString(), (cleared(player) + ids).toList())
        file.save()
    }

    /** 倒した記録を消す。bossId が null なら全部 */
    fun revoke(player: UUID, bossId: String?) {
        val rest = if (bossId == null) emptyList() else (cleared(player) - bossId.lowercase()).toList()
        file.set(player.toString(), rest.ifEmpty { null })
        file.save()
    }
}
