package com.eastcompany.eastsub.eastBossPlugin.menu

import com.eastcompany.eastsub.eastBossPlugin.EastBossPlugin
import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSettings
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.record.RecordManager
import com.eastcompany.eastsub.eastBossPlugin.record.TimeFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 旅商人を右クリックしたときに開く、ボス選択メニュー。
 * 登録済みのボスを解放の順番（progression.order）にアイコンで並べ、クリックするとそのボスの募集を始める（[MenuListener]）。
 * 開くたびに作り直すので、設定の変更（名前・HP）や記録もすぐ反映される。
 * 説明にはソロ記録・集団討伐ベスト（それぞれ記録の保持者つき）と、開いた人（[viewer]）の自己ベストも載せる。
 * 開いた人がまだ解放していないボスは、名前・アイコン・説明を伏せる（前のボスを倒すと解放。OP は全部見える）。
 *
 * ボス戦が進行中（募集中・戦闘中）なら、最後のスロットに「ボス戦をやめる」ボタンを置く。
 * 押せるのは募集を始めた人と管理者だけで、誤って押さないよう2回クリックで中止する。
 */
class BossSelectMenu(
    private val plugin: EastBossPlugin,
    private val viewer: Player,
) : InventoryHolder {

    /** スロット番号 → ボスの種類 */
    private val slots = HashMap<Int, BossType>()

    /** 「ボス戦をやめる」ボタンのスロット（進行中のボス戦が無ければ null） */
    val cancelSlot: Int?

    /** 1回目のクリックで「もう一度押すと中止」になっているか */
    private var cancelArmed = false

    private val inventory: Inventory

    init {
        // 解放の順番（config.yml の progression.order）に並べる。順番に無いボスは後ろ（登録順）
        val order = plugin.progressManager.order()
        val types = plugin.bossRegistry.all().toList()
            .sortedBy { type -> order.indexOf(type.id.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
        val busyBoss = plugin.battleManager.currentBossName()
        // 中止ボタンの分も入るように行を決める
        val needed = types.size + if (busyBoss != null) 1 else 0
        val rows = ((needed + 8) / 9).coerceIn(1, 6)
        inventory = plugin.server.createInventory(
            this, rows * 9, plugin.configManager.main.component(Names.Config.MENU_TITLE, def = "ボスを選ぶ")
        )
        types.take(rows * 9).forEachIndexed { slot, type ->
            inventory.setItem(slot, iconOf(type))
            slots[slot] = type
        }
        cancelSlot = if (busyBoss != null) rows * 9 - 1 else null
        cancelSlot?.let { inventory.setItem(it, cancelIcon(busyBoss!!)) }
    }

    /** 中止ボタンを押したときの結果 */
    enum class CancelClick { ARMED, CANCELLED, DENIED, GONE }

    /**
     * 中止ボタンを押した。1回目は「もう一度押すと中止」に変わり、2回目で中止する。
     * 押す権利が無ければ DENIED、もうボス戦が終わっていれば GONE
     */
    fun clickCancel(): CancelClick {
        val battles = plugin.battleManager
        val busyBoss = battles.currentBossName() ?: return CancelClick.GONE
        if (!battles.canCancel(viewer)) return CancelClick.DENIED
        if (!cancelArmed) {
            cancelArmed = true
            cancelSlot?.let { inventory.setItem(it, cancelIcon(busyBoss)) }
            return CancelClick.ARMED
        }
        return if (battles.cancelBy(viewer)) CancelClick.CANCELLED else CancelClick.GONE
    }

    /** 「ボス戦をやめる」ボタン。押せない人には、押せないことを書く */
    private fun cancelIcon(boss: Component): ItemStack {
        val messages = plugin.messages
        val canCancel = plugin.battleManager.canCancel(viewer)
        val lore = when {
            !canCancel -> messages.component(Names.Message.MENU_CANCEL_DENIED)
            cancelArmed -> messages.component(Names.Message.MENU_CANCEL_CONFIRM)
            else -> messages.component(Names.Message.MENU_CANCEL_CLICK)
        }
        return ItemStack.of(if (cancelArmed) Material.RED_CONCRETE else Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(noItalic(messages.component(Names.Message.MENU_CANCEL_NAME)))
                meta.lore(listOf(
                    messages.component(Names.Message.MENU_CANCEL_BOSS, "boss" to boss),
                    lore,
                ).map(::noItalic))
            }
        }
    }

    override fun getInventory(): Inventory = inventory

    /** そのスロットのボス（空きスロットなら null） */
    fun typeAt(slot: Int): BossType? = slots[slot]

    fun open() {
        viewer.openInventory(inventory)
    }

    /** ボスのアイコンに、名前と説明を付ける */
    private fun iconOf(type: BossType): ItemStack {
        if (!plugin.progressManager.isUnlocked(viewer, type)) return lockedIcon(type)
        val settings = BossSettings.load(plugin.configManager.file(type.configPath))
        val messages = plugin.messages
        return type.icon.apply {
            editMeta { meta ->
                meta.displayName(noItalic(settings.displayName))
                meta.lore(listOf(
                    messages.component(Names.Message.MENU_BOSS_HEALTH, "health" to "%.0f".format(settings.maxHealth)),
                    recordLine(type),
                    groupRecordLine(type),
                    personalLine(type),
                    messages.component(Names.Message.MENU_BOSS_CLICK),
                ).map(::noItalic))
            }
        }
    }

    /** まだ解放していないボス: 名前とアイコンを伏せ、前のボスを倒すと解放されることだけ書く */
    private fun lockedIcon(type: BossType): ItemStack {
        val progress = plugin.progressManager
        return ItemStack.of(progress.hiddenIcon()).apply {
            editMeta { meta ->
                meta.displayName(noItalic(progress.hiddenName()))
                meta.lore(listOf(
                    plugin.messages.component(Names.Message.MENU_BOSS_LOCKED,
                        "prev" to (progress.previousName(viewer, type) ?: progress.hiddenName())),
                ).map(::noItalic))
            }
        }
    }

    /** ソロ記録の行（保持者つき） */
    private fun recordLine(type: BossType): Component {
        val best = plugin.recordManager.best(type.id, RecordManager.Kind.SOLO)
            ?: return plugin.messages.component(Names.Message.MENU_BOSS_RECORD_NONE)
        return plugin.messages.component(Names.Message.MENU_BOSS_RECORD,
            "time" to TimeFormat.format(best.ticks), "players" to best.players.joinToString(", "))
    }

    /** 集団討伐ベストの行（保持者と人数つき） */
    private fun groupRecordLine(type: BossType): Component {
        val best = plugin.recordManager.best(type.id, RecordManager.Kind.GROUP)
            ?: return plugin.messages.component(Names.Message.MENU_BOSS_GROUP_RECORD_NONE)
        return plugin.messages.component(Names.Message.MENU_BOSS_GROUP_RECORD,
            "time" to TimeFormat.format(best.ticks), "players" to best.players.joinToString(", "),
            "count" to best.players.size)
    }

    /** 開いた人の自己ベストの行 */
    private fun personalLine(type: BossType): Component {
        val best = plugin.recordManager.personalBest(type.id, viewer.uniqueId)
            ?: return plugin.messages.component(Names.Message.MENU_BOSS_PERSONAL_NONE)
        return plugin.messages.component(Names.Message.MENU_BOSS_PERSONAL, "time" to TimeFormat.format(best))
    }

    /** アイテム名・説明はデフォルトで斜体になるので消す */
    private fun noItalic(component: Component): Component =
        component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
}
