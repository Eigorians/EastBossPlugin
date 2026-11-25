package com.eastcompany.eastsub.eastBossPlugin.battle

import com.eastcompany.eastsub.eastBossPlugin.EastBossPlugin
import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossManager
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSettings
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.location.LocationManager
import com.eastcompany.eastsub.eastBossPlugin.location.PointType
import com.eastcompany.eastsub.eastBossPlugin.record.TimeFormat
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.time.Duration
import java.util.UUID

/**
 * 旅商人から始めるボス戦の進行役。同時に進行できる戦闘は1つだけ。
 *
 *   待機 → [start] で募集開始（周囲のプレイヤーに参加ボタン付きの通知、召喚地点にカウントダウン表示）
 *        → カウント 0 でボスを召喚（参加者だけを狙う）
 *        → ボスの討伐・全員の脱落・時間切れ・中止などで終了 → 待機
 *
 * 参加者は1人 battle.death-limit 回倒れると脱落し、そのボス戦にはもう加われない（ボスに狙われず、攻撃も届かない）。
 * 全員が脱落したら敗北。勝ったときに「倒した」ことになる（次のボスが解放される）のは、脱落していない人だけ。
 * 募集を始めた人（と管理者）は、旅商人のメニューからボス戦を中止できる（[cancelBy]）。
 *
 * ボスには解放の順番がある（ProgressManager）。まだ解放していないボスの戦いは始められないが、
 * ほかの人が始めた戦いには参加できる。勝つと参加者全員がそのボスを倒したことになり、次のボスが解放される。
 */
class BattleManager(private val plugin: EastBossPlugin) {

    private sealed interface State

    private data object Idle : State

    private class Recruiting(
        val type: BossType,
        val settings: BossSettings,
        val battle: BossBattle,
        val location: Location,
        val display: TextDisplay,
        var remaining: Int,
        var ticks: Int = 0,
    ) : State

    private class Fighting(
        val battle: BossBattle,
        val boss: Boss,
        /** ボスの召喚地点（参加者が離れたかの判定に使う） */
        val location: Location,
        /** 参加者が全員脱落して消えたか */
        var failed: Boolean = false,
        /** 旅商人のメニューから中止した人の名前（中止されていなければ null） */
        var cancelledBy: String? = null,
        /** 参加者が全員いなくなって消えたか */
        var abandoned: Boolean = false,
        /** 参加者が誰もいない状態が続いている tick */
        var absentTicks: Int = 0,
    ) : State

    private var state: State = Idle

    private val messages get() = plugin.messages
    private val config get() = plugin.configManager.main
    private val bossManager: BossManager get() = plugin.bossManager

    /** 募集中・戦闘中なら true */
    val isBusy: Boolean get() = state !is Idle

    // ===== 募集 =====

    /** 募集を始める。始められないときは initiator に理由を送る */
    fun start(initiator: Player, type: BossType) {
        if (isBusy) {
            messages.send(initiator, Names.Message.BATTLE_BUSY)
            return
        }
        // 前のボスを倒していなければ始められない（OP は自由）
        val progress = plugin.progressManager
        if (!progress.isUnlocked(initiator, type)) {
            messages.send(initiator, Names.Message.BATTLE_LOCKED,
                "prev" to (progress.previousName(initiator, type) ?: progress.hiddenName()))
            return
        }

        val location = when (val result = plugin.locationManager.get(PointType.BOSS)) {
            is LocationManager.Result.Found -> result.location
            LocationManager.Result.NotSet -> {
                messages.send(initiator, Names.Message.LOCATION_NOT_SET, "type" to PointType.BOSS.displayName)
                return
            }
            is LocationManager.Result.WorldMissing -> {
                messages.send(initiator, Names.Message.LOCATION_WORLD_MISSING,
                    "type" to PointType.BOSS.displayName, "world" to result.worldName)
                return
            }
        }

        // カウントダウンの後で失敗しないよう、モデルがあるか先に確かめる
        val settings = BossSettings.load(plugin.configManager.file(type.configPath))
        if (plugin.betterModel.model(settings.model) == null) {
            messages.send(initiator, Names.Message.BOSS_MODEL_MISSING, "model" to settings.model)
            return
        }

        val seconds = config.int(Names.Config.BATTLE_COUNTDOWN_SECONDS, 5).coerceIn(1, 120)
        val battle = BossBattle(config.int(Names.Config.BATTLE_DEATH_LIMIT, 2).coerceAtLeast(1), initiator.uniqueId)
        battle.join(initiator)

        val recruiting = Recruiting(type, settings, battle, location, spawnDisplay(location), seconds)
        state = recruiting
        updateDisplay(recruiting)
        showCountdownTitle(recruiting)

        notifyNearby(initiator, recruiting, seconds)
        messages.send(initiator, Names.Message.BATTLE_JOINED)
        plugin.debug("${initiator.name} が ${type.id} の募集を開始")
    }

    /** 周囲のプレイヤーに、参加ボタン付きの通知を送る */
    private fun notifyNearby(initiator: Player, recruiting: Recruiting, seconds: Int) {
        val radius = config.double(Names.Config.BATTLE_NOTIFY_RADIUS, 100.0)
        val center = initiator.location
        val targets = initiator.world.players.filter { it.location.distanceSquared(center) <= radius * radius }

        for (player in targets) {
            messages.send(
                player, Names.Message.BATTLE_RECRUIT,
                "player" to initiator.name,
                "boss" to recruiting.settings.displayName,
                "seconds" to seconds,
                "join" to joinButton(seconds),
            )
        }
    }

    /** クリックで参加できるボタン（1人1回・募集時間だけ有効） */
    private fun joinButton(seconds: Int): Component =
        messages.component(Names.Message.BATTLE_JOIN_BUTTON).clickEvent(
            ClickEvent.callback({ audience -> onJoinClick(audience) }) {
                it.uses(1).lifetime(Duration.ofSeconds(seconds + 5L))
            }
        )

    private fun onJoinClick(audience: Audience) {
        // 念のためメインスレッドで処理する
        if (!plugin.server.isPrimaryThread) {
            plugin.server.scheduler.runTask(plugin, Runnable { onJoinClick(audience) })
            return
        }
        val player = audience as? Player ?: return
        val recruiting = state as? Recruiting ?: run {
            messages.send(player, Names.Message.BATTLE_JOIN_CLOSED)
            return
        }

        if (recruiting.battle.join(player)) {
            messages.send(player, Names.Message.BATTLE_JOINED)
            updateDisplay(recruiting)
        } else {
            messages.send(player, Names.Message.BATTLE_ALREADY_JOINED)
        }
    }

    // ===== カウントダウン表示 =====

    private fun spawnDisplay(location: Location): TextDisplay {
        val height = config.double(Names.Config.BATTLE_DISPLAY_HEIGHT, 2.5)
        return location.world.spawn(location.clone().add(0.0, height, 0.0), TextDisplay::class.java) { display ->
            display.isPersistent = false
            display.billboard = Display.Billboard.CENTER
        }
    }

    private fun updateDisplay(recruiting: Recruiting) {
        recruiting.display.text(
            messages.component(
                Names.Message.BATTLE_COUNTDOWN_DISPLAY,
                "boss" to recruiting.settings.displayName,
                "count" to recruiting.remaining,
                "players" to recruiting.battle.participantCount,
            )
        )
    }

    /** 参加者の画面にカウントダウンをタイトルで出す（1秒ごとに呼ぶ） */
    private fun showCountdownTitle(recruiting: Recruiting) {
        if (!config.boolean(Names.Config.BATTLE_COUNTDOWN_TITLE, true)) return
        val placeholders = arrayOf<Pair<String, Any>>(
            "boss" to recruiting.settings.displayName,
            "count" to recruiting.remaining,
        )
        val title = Title.title(
            messages.component(Names.Message.BATTLE_COUNTDOWN_TITLE, *placeholders),
            messages.component(Names.Message.BATTLE_COUNTDOWN_SUBTITLE, *placeholders),
            // 次の1秒の表示に切り替わるまで出し続ける（フェードインなし）
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200)),
        )
        recruiting.battle.onlineParticipants(plugin.server).forEach { it.showTitle(title) }
    }

    /**
     * 戦闘開始時に参加者を万全にする: 体力・満腹度を満タンにし、燃えているのを消し、
     * 前の戦いの状態異常（スタン・回復不可・盾のガード破壊）を解く
     */
    private fun restore(player: Player) {
        if (player.isDead) return
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health
        player.foodLevel = 20
        player.saturation = 20f
        player.exhaustion = 0f
        player.fireTicks = 0
        player.freezeTicks = 0
        plugin.status.release(player)
        player.world.spawnParticle(Particle.HEART, player.location.add(0.0, 2.0, 0.0), 5, 0.4, 0.2, 0.4, 0.0)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.6f, 1.6f)
    }

    /** ボス出現時のタイトル */
    private fun showStartTitle(participants: List<Player>, settings: BossSettings) {
        if (!config.boolean(Names.Config.BATTLE_COUNTDOWN_TITLE, true)) return
        val title = Title.title(
            messages.component(Names.Message.BATTLE_START_TITLE, "boss" to settings.displayName),
            messages.component(Names.Message.BATTLE_START_SUBTITLE, "boss" to settings.displayName),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500)),
        )
        participants.forEach { it.showTitle(title) }
    }

    // ===== 毎tick =====

    fun tick() {
        when (val s = state) {
            Idle -> Unit
            is Recruiting -> tickRecruiting(s)
            is Fighting -> tickFighting(s)
        }
    }

    private fun tickRecruiting(recruiting: Recruiting) {
        recruiting.ticks++
        if (recruiting.ticks % 20 != 0) return

        recruiting.remaining--
        if (recruiting.remaining > 0) {
            updateDisplay(recruiting)
            showCountdownTitle(recruiting)
            return
        }

        // カウント 0：ボスを召喚
        recruiting.display.remove()
        val participants = recruiting.battle.onlineParticipants(plugin.server)
        if (participants.isEmpty()) {
            // 参加者が全員ログアウトしていたら、ボスを出さずに終わる
            state = Idle
            plugin.debug("参加者がいないため ${recruiting.type.id} の召喚を中止")
            return
        }

        when (val result = bossManager.spawn(recruiting.type, recruiting.location, recruiting.battle)) {
            is BossManager.SpawnResult.Success -> {
                state = Fighting(recruiting.battle, result.boss, recruiting.location)
                messages.send(participants, Names.Message.BATTLE_START,
                    "boss" to recruiting.settings.displayName,
                    "limit" to recruiting.battle.deathLimit)
                showStartTitle(participants, recruiting.settings)
                participants.forEach(::restore)
            }
            is BossManager.SpawnResult.ModelNotFound -> {
                state = Idle
                messages.send(participants, Names.Message.BOSS_MODEL_MISSING, "model" to result.model)
            }
        }
    }

    private fun tickFighting(fighting: Fighting) {
        val boss = fighting.boss
        // 死亡演出が始まった・消えた時点で終了とする
        if (!boss.isDying && bossManager.isBoss(boss.id)) {
            if (!boss.isPaused) fighting.battle.elapsedTicks++
            dismountParticipants(fighting)
            checkAbsent(fighting)
            return
        }

        // 討伐は、参加者がとどめを刺したときだけ（リロード・コマンドでの削除・時間切れ・/kill などは討伐にしない）
        val victory = !fighting.failed && !fighting.abandoned && fighting.cancelledBy == null && boss.slainBy != null
        val participants = fighting.battle.onlineParticipants(plugin.server)
        fighting.cancelledBy?.let { name ->
            messages.send(participants, Names.Message.BATTLE_PLAYER_CANCELLED, "player" to name, "boss" to boss.settings.displayName)
            state = Idle
            plugin.debug("ボス戦終了: $name が中止（${fighting.battle.elapsedTicks} tick）")
            return
        }
        val key = when {
            fighting.failed -> Names.Message.BATTLE_FAILED
            fighting.abandoned -> Names.Message.BATTLE_ABANDONED
            victory -> Names.Message.BATTLE_VICTORY
            else -> Names.Message.BATTLE_ENDED
        }
        messages.send(participants, key, "boss" to boss.settings.displayName)
        if (victory) {
            showDamage(fighting, participants)
            recordClear(fighting, participants)
            unlockNext(fighting, participants)
        }
        state = Idle
        plugin.debug("ボス戦終了: $key（${fighting.battle.elapsedTicks} tick）")
    }

    /** 2人以上で討伐したとき、参加者ごとの与ダメージを多い順に出す（脱落した人も含む） */
    private fun showDamage(fighting: Fighting, online: List<Player>) {
        val battle = fighting.battle
        if (battle.participantCount < 2) return
        val ranking = battle.damageRanking()
        val lines = mutableListOf(messages.component(Names.Message.BATTLE_DAMAGE_HEADER))
        ranking.forEachIndexed { i, entry ->
            lines += messages.component(
                Names.Message.BATTLE_DAMAGE_LINE,
                "rank" to (i + 1),
                "player" to entry.name,
                "damage" to "%.0f".format(entry.damage),
                "percent" to "%.1f".format(entry.ratio * 100),
            )
        }
        online.forEach { p -> lines.forEach { p.sendMessage(it) } }
    }

    /** 戦っている間、参加者は乗り物（馬・ボート・トロッコなど）に乗れない。乗っていたら降ろす */
    private fun dismountParticipants(fighting: Fighting) {
        for (player in fighting.battle.onlineParticipants(plugin.server)) {
            if (!player.isInsideVehicle) continue
            player.leaveVehicle()
            messages.send(player, Names.Message.BATTLE_DISMOUNTED)
        }
    }

    /**
     * このプレイヤーが今戦っているボス戦の参加者（脱落した人も含む）なら、そのボス戦の（オンラインの）参加者全員。違えば null
     */
    fun fellowParticipants(player: Player): List<Player>? {
        val s = state as? Fighting ?: return null
        if (!s.battle.isMember(player)) return null
        return deathAudience(s)
    }

    /**
     * 参加者の死亡ログ・倒れた回数・脱落の知らせを届ける相手:
     * 参加者（脱落した人も含む）と、ボスの近く（config.yml の battle.death-log-radius ブロック以内）にいる人
     */
    private fun deathAudience(fighting: Fighting): List<Player> {
        val out = LinkedHashSet(fighting.battle.onlineParticipants(plugin.server))
        val radius = plugin.configManager.main.double(Names.Config.BATTLE_DEATH_LOG_RADIUS, 64.0)
        val boss = fighting.boss.entity
        if (radius > 0 && boss.isValid) out += boss.world.getNearbyPlayers(boss.location, radius)
        return out.toList()
    }

    /** このプレイヤーが、今戦っているボス戦の参加者か */
    fun isFighting(player: Player): Boolean {
        val s = state as? Fighting ?: return false
        return !s.boss.isDying && s.battle.isParticipant(player)
    }

    /**
     * 脱落していない参加者が誰も戦っていない（全員ログアウト・別ワールド・召喚地点から battle.leave-radius 以上離れた）状態が
     * battle.absent-seconds 秒続いたら、ボスを撤退させる。倒れて復活待ちの人は「いる」扱い
     */
    private fun checkAbsent(fighting: Fighting) {
        val radius = config.double(Names.Config.BATTLE_LEAVE_RADIUS, 64.0)
        val present = fighting.battle.activeParticipants(plugin.server).any { player ->
            player.isDead || (player.world == fighting.location.world &&
                player.location.distanceSquared(fighting.location) <= radius * radius)
        }
        if (present) {
            fighting.absentTicks = 0
            return
        }
        fighting.absentTicks++
        val limit = config.int(Names.Config.BATTLE_ABSENT_SECONDS, 15).coerceAtLeast(1) * 20
        if (fighting.absentTicks < limit) return

        fighting.abandoned = true
        fighting.boss.vanish()
        plugin.debug("参加者がいないため ${fighting.boss.type.id} が撤退")
    }

    // ===== クリアタイム =====

    /**
     * 討伐したときにクリアタイムを知らせ、記録に残す。
     * 参加者が1人ならソロ記録（自己ベストも）、2人以上なら集団討伐の記録（保持者は参加した全員）
     */
    private fun recordClear(fighting: Fighting, online: List<Player>) {
        val bossId = fighting.boss.type.id
        val ticks = fighting.battle.elapsedTicks
        val time = TimeFormat.format(ticks)
        messages.send(online, Names.Message.BATTLE_CLEAR_TIME, "time" to time)

        val holders = LinkedHashMap<UUID, String>()
        for (uuid in fighting.battle.participantIds) {
            holders[uuid] = plugin.server.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)
        }
        val solo = holders.size <= 1
        val result = plugin.recordManager.submit(bossId, ticks, holders)

        result.rank?.let { rank ->
            messages.send(online, if (solo) Names.Message.RECORD_RANKED else Names.Message.RECORD_GROUP_RANKED, "rank" to rank)
        }
        if (!solo) return

        for (player in online) {
            if (player.uniqueId !in result.newBests) continue
            val previous = result.newBests[player.uniqueId]
            if (previous == null) {
                messages.send(player, Names.Message.RECORD_FIRST_CLEAR, "time" to time)
            } else {
                messages.send(player, Names.Message.RECORD_NEW_BEST,
                    "time" to time, "previous" to TimeFormat.format(previous))
            }
        }
    }

    // ===== ボスの解放 =====

    /** 勝ったとき脱落していなかった参加者を「倒した」ことにして、初めて倒した人に次のボスの解放を知らせる */
    private fun unlockNext(fighting: Fighting, online: List<Player>) {
        val progress = plugin.progressManager
        val bossId = fighting.boss.type.id
        val first = progress.markCleared(fighting.battle.survivorIds, bossId).toSet()
        if (!progress.enabled) return
        val next = progress.nextOf(bossId)
        for (player in online) {
            if (player.uniqueId !in first) continue
            if (next != null) {
                messages.send(player, Names.Message.PROGRESS_UNLOCKED, "boss" to progress.displayName(next))
                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.0f)
            } else if (bossId.lowercase() == progress.order().lastOrNull()) {
                messages.send(player, Names.Message.PROGRESS_COMPLETE)
                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 0.8f)
            }
        }
    }

    // ===== 参加者の死亡 =====

    /** プレイヤーが死んだとき（BattleListener から呼ぶ） */
    fun onPlayerDeath(player: Player) {
        val fighting = state as? Fighting ?: return
        val battle = fighting.battle
        if (!battle.isParticipant(player) || fighting.boss.isDying) return

        val result = battle.addDeath(player)
        val everyone = battle.onlineParticipants(plugin.server)
        val audience = deathAudience(fighting)
        messages.send(audience, Names.Message.BATTLE_DEATH,
            "player" to player.name, "count" to result.count, "limit" to battle.deathLimit)

        val times = Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(800))
        if (result.eliminated) {
            messages.send(audience, Names.Message.BATTLE_ELIMINATED, "player" to player.name)
            // 最後の1人が倒れて全滅したときは「脱落」ではなく、全員の画面に「全滅」を出す
            if (!result.allEliminated) {
                player.showTitle(Title.title(
                    messages.component(Names.Message.BATTLE_ELIMINATED_TITLE),
                    messages.component(Names.Message.BATTLE_ELIMINATED_SUBTITLE),
                    times,
                ))
            }
            plugin.debug("${player.name} が脱落（${result.count} 回）")
        }
        if (result.allEliminated) {
            val title = Title.title(
                messages.component(Names.Message.BATTLE_WIPED_TITLE),
                messages.component(Names.Message.BATTLE_WIPED_SUBTITLE),
                times,
            )
            everyone.forEach { it.showTitle(title) }
            fighting.failed = true
            fighting.boss.vanish()
        }
    }

    // ===== 旅商人からの中止 =====

    /** 進行中のボス戦（募集中・戦闘中）のボスの名前。何も無ければ null */
    fun currentBossName(): Component? = when (val s = state) {
        Idle -> null
        is Recruiting -> s.settings.displayName
        is Fighting -> s.boss.settings.displayName
    }

    /** このプレイヤーが今のボス戦を中止できるか（募集を始めた人か、管理者） */
    fun canCancel(player: Player): Boolean {
        val battle = when (val s = state) {
            Idle -> return false
            is Recruiting -> s.battle
            is Fighting -> if (s.boss.isDying) return false else s.battle
        }
        return battle.isLeader(player) || player.hasPermission(Names.Permission.ADMIN)
    }

    /**
     * 旅商人のメニューから、募集を始めた人（か管理者）がボス戦を中止する。
     * 戦闘中ならボスは煙のように去る（記録・解放なし）。
     * @return 中止できたら true
     */
    fun cancelBy(player: Player): Boolean {
        if (!canCancel(player)) return false
        when (val s = state) {
            Idle -> return false
            is Recruiting -> {
                s.display.remove()
                state = Idle
                messages.send(s.battle.onlineParticipants(plugin.server), Names.Message.BATTLE_PLAYER_CANCELLED,
                    "player" to player.name, "boss" to s.settings.displayName)
                plugin.debug("${player.name} が ${s.type.id} の募集を中止")
            }
            is Fighting -> {
                // 終わりの知らせは tickFighting がまとめて出す
                s.cancelledBy = player.name
                s.boss.vanish()
            }
        }
        return true
    }

    /**
     * 管理者が進行中の募集・戦闘を打ち切る（/eb battle cancel）。ボスもすぐ消す。
     * @return 打ち切るものがあったら true
     */
    fun forceCancel(): Boolean {
        val battle = when (val s = state) {
            Idle -> return false
            is Recruiting -> s.battle
            is Fighting -> {
                bossManager.remove(s.boss)
                s.battle
            }
        }
        cancel()
        messages.send(battle.onlineParticipants(plugin.server), Names.Message.BATTLE_CANCELLED)
        return true
    }

    /** 募集・戦闘を打ち切る（onDisable など）。ボス自体の削除は BossManager が行う */
    fun cancel() {
        (state as? Recruiting)?.display?.remove()
        state = Idle
    }
}
