package com.eastcompany.eastsub.eastBossPlugin.command

import com.eastcompany.eastsub.eastBossPlugin.EastBossPlugin
import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.BossManager
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSettings
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigEditor
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigFile
import com.eastcompany.eastsub.eastBossPlugin.location.LocationManager
import com.eastcompany.eastsub.eastBossPlugin.location.PointType
import com.eastcompany.eastsub.eastBossPlugin.record.RecordManager
import com.eastcompany.eastsub.eastBossPlugin.record.TimeFormat
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

/**
 * /eastboss コマンド（別名 /eb）
 *
 * - reload                                     設定の再読み込み
 * - debug                                      デバッグモードの ON/OFF（config.yml に保存）
 * - location set <trader|boss> [ox oy oz]      足元のブロック中心＋オフセットを保存（向きは自動でそろえる）
 * - location info                              設定済みの座標一覧
 * - location tp <trader|boss>                  設定した座標へテレポート
 * - trader spawn                               旅商人の座標に旅商人を召喚
 * - trader remove                              旅商人をすべて削除
 * - boss summon <id>                           ボス召喚地点にボスを召喚
 * - boss list                                  登録済みのボスと召喚中の数
 * - boss remove                                召喚中のボスをすべて削除
 * - battle cancel                              進行中の募集・ボス戦を打ち切る（ボスもすぐ消す）
 * - record reset <id|all>                      そのボス（all なら全ボス）のランキングと全員の自己ベストを消す
 * - record reset <id|all> <player>             そのプレイヤーの記録（自己ベストと、載っているランキング）だけを消す
 * - config <config|ボスID> <キー>              設定の値を見る（config = config.yml、ボスID = bosses/<ID>.yml）
 * - config <config|ボスID> <キー> <値>         設定の値を変えて保存する（すぐ反映。ボスの設定は次に召喚したボスから）
 * - progress info <player>                     そのプレイヤーが倒したボス（ボスの解放）
 * - progress grant <player> <id|all>           倒したことにする（次のボスが解放される）
 * - progress revoke <player> <id|all>          倒した記録を消す
 * - ranking <id>                               ボスのクリアタイムランキングと自己ベスト（誰でも使える）
 *
 * ranking 以外は eastboss.admin が必要。
 */
class EastBossCommand(private val plugin: EastBossPlugin) {

    private val messages get() = plugin.messages
    private val locations get() = plugin.locationManager

    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(Names.Command.ROOT)
            .then(Commands.literal(Names.Command.RELOAD).requires(::isAdmin).executes(::reload))
            .then(Commands.literal(Names.Command.DEBUG).requires(::isAdmin).executes(::toggleDebug))
            .then(locationCommand().requires(::isAdmin))
            .then(traderCommand().requires(::isAdmin))
            .then(bossCommand().requires(::isAdmin))
            .then(battleCommand().requires(::isAdmin))
            .then(recordCommand().requires(::isAdmin))
            .then(configCommand().requires(::isAdmin))
            .then(progressCommand().requires(::isAdmin))
            .then(rankingCommand())
            .build()

    private fun isAdmin(source: CommandSourceStack): Boolean = source.sender.hasPermission(Names.Permission.ADMIN)

    // ---- reload ----

    private fun reload(ctx: CommandContext<CommandSourceStack>): Int {
        val ok = plugin.configManager.reloadAll()
        messages.send(ctx.source.sender, if (ok) Names.Message.RELOAD else Names.Message.RELOAD_FAILED)
        return Command.SINGLE_SUCCESS
    }

    // ---- debug ----

    private fun toggleDebug(ctx: CommandContext<CommandSourceStack>): Int {
        val enabled = !plugin.isDebug()
        plugin.setDebug(enabled)
        messages.send(ctx.source.sender, if (enabled) Names.Message.DEBUG_ON else Names.Message.DEBUG_OFF)
        return Command.SINGLE_SUCCESS
    }

    // ---- location ----

    private fun locationCommand() =
        Commands.literal(Names.Command.LOCATION)
            .then(Commands.literal(Names.Command.SET).apply {
                PointType.entries.forEach { type ->
                    // set <type> [x y z]
                    // 位置はブロック中心、向きは自動でそろえる。オフセット省略時は 0
                    val xyz = Commands.argument(Names.Command.ARG_OFFSET_X, DoubleArgumentType.doubleArg())
                        .then(
                            Commands.argument(Names.Command.ARG_OFFSET_Y, DoubleArgumentType.doubleArg())
                                .then(
                                    Commands.argument(Names.Command.ARG_OFFSET_Z, DoubleArgumentType.doubleArg())
                                        .executes { setLocation(it, type, readOffset(it)) }
                                )
                        )
                    then(
                        Commands.literal(type.id)
                            .executes { setLocation(it, type, Vector()) }
                            .then(xyz)
                    )
                }
            })
            .then(Commands.literal(Names.Command.INFO).executes(::locationInfo))
            .then(Commands.literal(Names.Command.TP).apply {
                PointType.entries.forEach { type ->
                    then(Commands.literal(type.id).executes { teleport(it, type) })
                }
            })

    /** コマンド引数から位置のオフセットを読み取る */
    private fun readOffset(ctx: CommandContext<CommandSourceStack>) = Vector(
        DoubleArgumentType.getDouble(ctx, Names.Command.ARG_OFFSET_X),
        DoubleArgumentType.getDouble(ctx, Names.Command.ARG_OFFSET_Y),
        DoubleArgumentType.getDouble(ctx, Names.Command.ARG_OFFSET_Z),
    )

    private fun setLocation(ctx: CommandContext<CommandSourceStack>, type: PointType, offset: Vector): Int {
        val player = requirePlayer(ctx) ?: return 0
        val location = locations.set(type, player.location, offset)
        messages.send(player, Names.Message.LOCATION_SET, *locationPlaceholders(type, location))

        if (plugin.configManager.main.boolean(Names.Config.DEBUG_SHOW_LOCATION)) {
            location.world.spawnParticle(Particle.HAPPY_VILLAGER, location.clone().add(0.0, 0.5, 0.0), 30, 0.2, 0.5, 0.2)
        }
        plugin.debug("${player.name} が ${type.id} の座標を設定: $location (オフセット $offset)")
        return Command.SINGLE_SUCCESS
    }

    private fun locationInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        PointType.entries.forEach { type ->
            when (val result = locations.get(type)) {
                is LocationManager.Result.Found ->
                    messages.send(sender, Names.Message.LOCATION_INFO, *locationPlaceholders(type, result.location))
                else -> sendLocationError(sender, type, result)
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun teleport(ctx: CommandContext<CommandSourceStack>, type: PointType): Int {
        val player = requirePlayer(ctx) ?: return 0
        val location = findLocation(player, type) ?: return 0
        player.teleport(location)
        messages.send(player, Names.Message.LOCATION_TELEPORT, "type" to type.displayName)
        return Command.SINGLE_SUCCESS
    }

    // ---- trader ----

    private fun traderCommand() =
        Commands.literal(Names.Command.TRADER)
            .then(Commands.literal(Names.Command.SPAWN).executes(::spawnTrader))
            .then(Commands.literal(Names.Command.REMOVE).executes(::removeTrader))

    private fun spawnTrader(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val location = findLocation(sender, PointType.TRADER) ?: return 0
        plugin.traderNpc.spawn(location)
        messages.send(sender, Names.Message.TRADER_SPAWNED)
        plugin.debug("旅商人を召喚: $location")
        return Command.SINGLE_SUCCESS
    }

    private fun removeTrader(ctx: CommandContext<CommandSourceStack>): Int {
        val count = plugin.traderNpc.removeAll()
        messages.send(ctx.source.sender, Names.Message.TRADER_REMOVED, "count" to count)
        return Command.SINGLE_SUCCESS
    }

    // ---- boss ----

    private fun bossCommand() =
        Commands.literal(Names.Command.BOSS)
            .then(
                Commands.literal(Names.Command.SUMMON).then(bossIdArgument().executes(::summonBoss))
            )
            .then(Commands.literal(Names.Command.LIST).executes(::listBosses))
            .then(Commands.literal(Names.Command.REMOVE).executes(::removeBosses))

    private fun summonBoss(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val id = StringArgumentType.getString(ctx, Names.Command.ARG_BOSS_ID)
        val type = plugin.bossRegistry.get(id) ?: run {
            messages.send(sender, Names.Message.BOSS_UNKNOWN, "id" to id)
            return 0
        }
        val location = findLocation(sender, PointType.BOSS) ?: return 0

        return when (val result = plugin.bossManager.spawn(type, location)) {
            is BossManager.SpawnResult.Success -> {
                messages.send(sender, Names.Message.BOSS_SUMMONED, "boss" to result.boss.settings.displayName)
                Command.SINGLE_SUCCESS
            }
            is BossManager.SpawnResult.ModelNotFound -> {
                messages.send(sender, Names.Message.BOSS_MODEL_MISSING, "model" to result.model)
                0
            }
        }
    }

    private fun listBosses(ctx: CommandContext<CommandSourceStack>): Int {
        messages.send(
            ctx.source.sender, Names.Message.BOSS_LIST,
            "ids" to plugin.bossRegistry.ids().joinToString(", "),
            "count" to plugin.bossManager.all().size,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun removeBosses(ctx: CommandContext<CommandSourceStack>): Int {
        val count = plugin.bossManager.removeAll()
        messages.send(ctx.source.sender, Names.Message.BOSS_REMOVED, "count" to count)
        return Command.SINGLE_SUCCESS
    }

    // ---- battle ----

    private fun battleCommand() =
        Commands.literal(Names.Command.BATTLE)
            .then(Commands.literal(Names.Command.CANCEL).executes(::cancelBattle))

    private fun cancelBattle(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        if (!plugin.battleManager.forceCancel()) {
            messages.send(sender, Names.Message.BATTLE_NONE)
            return 0
        }
        messages.send(sender, Names.Message.BATTLE_CANCEL_DONE)
        return Command.SINGLE_SUCCESS
    }

    // ---- record ----

    private fun recordCommand() =
        Commands.literal(Names.Command.RECORD)
            .then(Commands.literal(Names.Command.RESET).then(
                progressBossArgument().executes(::resetRecord)
                    .then(playerArgument().executes(::resetPlayerRecord))
            ))

    private fun resetRecord(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val id = StringArgumentType.getString(ctx, Names.Command.ARG_BOSS_ID)
        if (id.equals(Names.Command.ALL, ignoreCase = true)) {
            plugin.recordManager.resetAll()
            messages.send(sender, Names.Message.RECORD_RESET_ALL)
            plugin.debug("${sender.name} が全ボスの記録を消去")
            return Command.SINGLE_SUCCESS
        }
        val type = plugin.bossRegistry.get(id) ?: run {
            messages.send(sender, Names.Message.BOSS_UNKNOWN, "id" to id)
            return 0
        }
        plugin.recordManager.reset(type.id)
        val settings = BossSettings.load(plugin.configManager.file(type.configPath))
        messages.send(sender, Names.Message.RECORD_RESET, "boss" to settings.displayName)
        plugin.debug("${sender.name} が ${type.id} の記録を消去")
        return Command.SINGLE_SUCCESS
    }

    /** 1人分の記録（自己ベストと、その人が載っているランキング）を消す */
    private fun resetPlayerRecord(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val (uuid, name) = findPlayer(ctx) ?: return 0
        val id = StringArgumentType.getString(ctx, Names.Command.ARG_BOSS_ID)
        if (id.equals(Names.Command.ALL, ignoreCase = true)) {
            plugin.recordManager.resetPlayerAll(uuid, name)
            messages.send(sender, Names.Message.RECORD_RESET_PLAYER_ALL, "player" to name)
            plugin.debug("${sender.name} が $name の全ボスの記録を消去")
            return Command.SINGLE_SUCCESS
        }
        val type = plugin.bossRegistry.get(id) ?: run {
            messages.send(sender, Names.Message.BOSS_UNKNOWN, "id" to id)
            return 0
        }
        plugin.recordManager.resetPlayer(type.id, uuid, name)
        val settings = BossSettings.load(plugin.configManager.file(type.configPath))
        messages.send(sender, Names.Message.RECORD_RESET_PLAYER, "boss" to settings.displayName, "player" to name)
        plugin.debug("${sender.name} が ${type.id} の $name の記録を消去")
        return Command.SINGLE_SUCCESS
    }

    // ---- config ----

    private fun configCommand() =
        Commands.literal(Names.Command.CONFIG).then(
            Commands.argument(Names.Command.ARG_FILE, StringArgumentType.word())
                .suggests { _, builder ->
                    (listOf(Names.Command.MAIN_CONFIG) + plugin.bossRegistry.ids())
                        .filter { it.startsWith(builder.remainingLowerCase) }
                        .forEach { builder.suggest(it) }
                    builder.buildFuture()
                }
                .then(
                    Commands.argument(Names.Command.ARG_KEY, StringArgumentType.word())
                        .suggests { ctx, builder ->
                            val file = configFileOf(StringArgumentType.getString(ctx, Names.Command.ARG_FILE))
                            if (file != null) {
                                ConfigEditor.keys(file)
                                    .filter { it.lowercase().startsWith(builder.remainingLowerCase) }
                                    .take(100)
                                    .forEach { builder.suggest(it) }
                            }
                            builder.buildFuture()
                        }
                        .executes(::showConfig)
                        .then(
                            Commands.argument(Names.Command.ARG_VALUE, StringArgumentType.greedyString())
                                .suggests { ctx, builder ->
                                    val file = configFileOf(StringArgumentType.getString(ctx, Names.Command.ARG_FILE))
                                    val key = StringArgumentType.getString(ctx, Names.Command.ARG_KEY)
                                    if (file != null) {
                                        ConfigEditor.suggestions(file, key)
                                            .filter { it.lowercase().startsWith(builder.remainingLowerCase) }
                                            .forEach { builder.suggest(it) }
                                    }
                                    builder.buildFuture()
                                }
                                .executes(::setConfig)
                        )
                )
        )

    /** config = config.yml、ボスID = そのボスの設定ファイル。どちらでもなければ null */
    private fun configFileOf(name: String): ConfigFile? =
        if (name.equals(Names.Command.MAIN_CONFIG, ignoreCase = true)) plugin.configManager.main
        else plugin.bossRegistry.get(name)?.let { plugin.configManager.file(it.configPath) }

    private fun showConfig(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, Names.Command.ARG_FILE)
        val key = StringArgumentType.getString(ctx, Names.Command.ARG_KEY)
        val file = configFileOf(name) ?: run {
            messages.send(sender, Names.Message.CONFIG_UNKNOWN_FILE, "file" to name)
            return 0
        }
        if (!file.contains(key)) {
            messages.send(sender, Names.Message.CONFIG_UNKNOWN_KEY, "file" to file.name, "key" to key)
            return 0
        }
        if (file.yaml.isConfigurationSection(key)) {
            messages.send(sender, Names.Message.CONFIG_SECTION, "file" to file.name, "key" to key)
            return 0
        }
        messages.send(sender, Names.Message.CONFIG_VALUE,
            "file" to file.name, "key" to key, "value" to ConfigEditor.format(file.yaml.get(key)))
        return Command.SINGLE_SUCCESS
    }

    private fun setConfig(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, Names.Command.ARG_FILE)
        val key = StringArgumentType.getString(ctx, Names.Command.ARG_KEY)
        val raw = StringArgumentType.getString(ctx, Names.Command.ARG_VALUE)
        val file = configFileOf(name) ?: run {
            messages.send(sender, Names.Message.CONFIG_UNKNOWN_FILE, "file" to name)
            return 0
        }
        return when (val result = ConfigEditor.set(file, key, raw)) {
            is ConfigEditor.Result.Done -> {
                messages.send(sender, Names.Message.CONFIG_SET,
                    "file" to file.name, "key" to key, "value" to ConfigEditor.format(result.value))
                plugin.debug("${sender.name} が ${file.name} の $key を ${ConfigEditor.format(result.value)} に変更")
                Command.SINGLE_SUCCESS
            }
            ConfigEditor.Result.UnknownKey -> {
                messages.send(sender, Names.Message.CONFIG_UNKNOWN_KEY, "file" to file.name, "key" to key)
                0
            }
            ConfigEditor.Result.Section -> {
                messages.send(sender, Names.Message.CONFIG_SECTION, "file" to file.name, "key" to key)
                0
            }
            is ConfigEditor.Result.Invalid -> {
                messages.send(sender, Names.Message.CONFIG_INVALID, "key" to key, "type" to result.expected)
                0
            }
        }
    }

    // ---- progress ----

    private fun progressCommand() =
        Commands.literal(Names.Command.PROGRESS)
            .then(Commands.literal(Names.Command.INFO).then(playerArgument().executes(::progressInfo)))
            .then(Commands.literal(Names.Command.GRANT).then(playerArgument().then(
                progressBossArgument().executes { changeProgress(it, grant = true) })))
            .then(Commands.literal(Names.Command.REVOKE).then(playerArgument().then(
                progressBossArgument().executes { changeProgress(it, grant = false) })))

    /** プレイヤー名の引数（オンラインの人を補完する。オフラインでも、一度入ったことがあれば使える） */
    private fun playerArgument() =
        Commands.argument(Names.Command.ARG_PLAYER, StringArgumentType.word())
            .suggests { _, builder ->
                plugin.server.onlinePlayers.map { it.name }
                    .filter { it.lowercase().startsWith(builder.remainingLowerCase) }
                    .forEach { builder.suggest(it) }
                builder.buildFuture()
            }

    /** ボスID か all */
    private fun progressBossArgument() =
        Commands.argument(Names.Command.ARG_BOSS_ID, StringArgumentType.word())
            .suggests { _, builder ->
                (plugin.bossRegistry.ids() + Names.Command.ALL)
                    .filter { it.startsWith(builder.remainingLowerCase) }
                    .forEach { builder.suggest(it) }
                builder.buildFuture()
            }

    /** 名前からプレイヤーを探す（UUID と表示名）。見つからなければメッセージを送って null */
    private fun findPlayer(ctx: CommandContext<CommandSourceStack>): Pair<UUID, String>? {
        val name = StringArgumentType.getString(ctx, Names.Command.ARG_PLAYER)
        plugin.server.getPlayerExact(name)?.let { return it.uniqueId to it.name }
        plugin.server.getOfflinePlayerIfCached(name)?.let { return it.uniqueId to (it.name ?: name) }
        messages.send(ctx.source.sender, Names.Message.PLAYER_UNKNOWN, "player" to name)
        return null
    }

    private fun progressInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val (uuid, name) = findPlayer(ctx) ?: return 0
        val progress = plugin.progressManager
        val cleared = progress.cleared(uuid)
        // 解放の順番に並べる（順番に無いボスは後ろ）。倒したものに ✔
        val ids = (progress.order() + plugin.bossRegistry.ids()).distinct()
        val list = ids.joinToString("  ") { if (it in cleared) "✔$it" else "✘$it" }
        messages.send(ctx.source.sender, Names.Message.PROGRESS_INFO, "player" to name, "bosses" to list)
        return Command.SINGLE_SUCCESS
    }

    private fun changeProgress(ctx: CommandContext<CommandSourceStack>, grant: Boolean): Int {
        val sender = ctx.source.sender
        val (uuid, name) = findPlayer(ctx) ?: return 0
        val id = StringArgumentType.getString(ctx, Names.Command.ARG_BOSS_ID)
        val bossId = if (id.equals(Names.Command.ALL, ignoreCase = true)) null else {
            plugin.bossRegistry.get(id)?.id ?: run {
                messages.send(sender, Names.Message.BOSS_UNKNOWN, "id" to id)
                return 0
            }
        }
        val progress = plugin.progressManager
        if (grant) progress.grant(uuid, bossId) else progress.revoke(uuid, bossId)
        messages.send(sender, if (grant) Names.Message.PROGRESS_GRANTED else Names.Message.PROGRESS_REVOKED,
            "player" to name, "boss" to (bossId ?: Names.Command.ALL))
        return Command.SINGLE_SUCCESS
    }

    // ---- ranking ----

    private fun rankingCommand() =
        Commands.literal(Names.Command.RANKING).then(bossIdArgument().executes(::showRanking))

    private fun showRanking(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val id = StringArgumentType.getString(ctx, Names.Command.ARG_BOSS_ID)
        val type = plugin.bossRegistry.get(id) ?: run {
            messages.send(sender, Names.Message.BOSS_UNKNOWN, "id" to id)
            return 0
        }
        val settings = BossSettings.load(plugin.configManager.file(type.configPath))
        val records = plugin.recordManager

        // ソロ記録
        val solo = records.ranking(type.id, RecordManager.Kind.SOLO)
        messages.send(sender, Names.Message.RANKING_HEADER, "boss" to settings.displayName)
        if (solo.isEmpty()) messages.send(sender, Names.Message.RANKING_EMPTY)
        solo.forEachIndexed { i, record ->
            messages.send(sender, Names.Message.RANKING_ENTRY,
                "rank" to i + 1,
                "time" to TimeFormat.format(record.ticks),
                "players" to record.players.joinToString(", "))
        }

        // 集団討伐
        val group = records.ranking(type.id, RecordManager.Kind.GROUP)
        messages.send(sender, Names.Message.RANKING_GROUP_HEADER, "boss" to settings.displayName)
        if (group.isEmpty()) messages.send(sender, Names.Message.RANKING_EMPTY)
        group.forEachIndexed { i, record ->
            messages.send(sender, Names.Message.RANKING_GROUP_ENTRY,
                "rank" to i + 1,
                "time" to TimeFormat.format(record.ticks),
                "players" to record.players.joinToString(", "),
                "count" to record.players.size)
        }

        // プレイヤーが実行したときは自己ベストも出す
        val player = sender as? Player ?: return Command.SINGLE_SUCCESS
        val best = plugin.recordManager.personalBest(type.id, player.uniqueId)
        if (best == null) {
            messages.send(player, Names.Message.RANKING_PERSONAL_NONE)
        } else {
            messages.send(player, Names.Message.RANKING_PERSONAL, "time" to TimeFormat.format(best))
        }
        return Command.SINGLE_SUCCESS
    }

    // ---- 共通処理 ----

    /** ボスIDの引数（登録済みのIDを補完する） */
    private fun bossIdArgument() =
        Commands.argument(Names.Command.ARG_BOSS_ID, StringArgumentType.word())
            .suggests { _, builder ->
                plugin.bossRegistry.ids()
                    .filter { it.startsWith(builder.remainingLowerCase) }
                    .forEach { builder.suggest(it) }
                builder.buildFuture()
            }

    /** 実行者がプレイヤーでなければメッセージを送って null を返す */
    private fun requirePlayer(ctx: CommandContext<CommandSourceStack>): Player? {
        val player = ctx.source.executor as? Player
        if (player == null) messages.send(ctx.source.sender, Names.Message.PLAYER_ONLY)
        return player
    }

    /** 座標を取得する。未設定などで取れなければメッセージを送って null を返す */
    private fun findLocation(sender: CommandSender, type: PointType): Location? =
        when (val result = locations.get(type)) {
            is LocationManager.Result.Found -> result.location
            else -> {
                sendLocationError(sender, type, result)
                null
            }
        }

    private fun sendLocationError(sender: CommandSender, type: PointType, result: LocationManager.Result) {
        when (result) {
            is LocationManager.Result.Found -> Unit
            LocationManager.Result.NotSet ->
                messages.send(sender, Names.Message.LOCATION_NOT_SET, "type" to type.displayName)
            is LocationManager.Result.WorldMissing ->
                messages.send(sender, Names.Message.LOCATION_WORLD_MISSING, "type" to type.displayName, "world" to result.worldName)
        }
    }

    private fun locationPlaceholders(type: PointType, location: Location): Array<Pair<String, Any>> = arrayOf(
        "type" to type.displayName,
        "world" to location.world.name,
        "x" to "%.2f".format(location.x),
        "y" to "%.2f".format(location.y),
        "z" to "%.2f".format(location.z),
        "yaw" to "%.1f".format(location.yaw),
        "pitch" to "%.1f".format(location.pitch),
    )
}
