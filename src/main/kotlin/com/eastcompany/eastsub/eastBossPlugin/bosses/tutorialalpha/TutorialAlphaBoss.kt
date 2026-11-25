package com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSpawn
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha.skill.LeapSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha.skill.PetalOrbSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.DashSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.random.Random

/** チュートリアルアルファ（設定: bosses/tutorial_alpha.yml） */
object TutorialAlphaType : BossType {
    override val id = "tutorial_alpha"
    override val icon: ItemStack get() = ItemStack.of(Material.DRAGON_BREATH)
    override fun create(spawn: BossSpawn): Boss = TutorialAlphaBoss(spawn)
}

/**
 * チュートリアルアルファ。剣を持ったメイド風のボス。最初に戦うボスなので、**ボスとの戦い方を教える**作りにしている。
 *
 * - 技: 突進 / 剣振り / 跳躍 / ジャブ / 溜め斬り。どの技も予告がはっきりしていて、終わった後に大きな隙がある
 * - 教える: それぞれの技を初めて使うとき、参加者に「どう避けて、いつ殴ればいいか」をチャットで教える（技の yml の hint）。
 *   突進は溜めている間、進む向きに赤い線が出る（避け方を目で覚えられる）
 * - 最初の教え（resource_pack）: 見た目が反映されていないときの直し方を伝える。その間は止まっていて、
 *   動かない・攻撃しない・ダメージを受けない・寿命とクリアタイムも進まない
 * - 登場の挨拶（花びらと一緒にお辞儀）と、HP が rage-health-ratio 以下での「少しだけ本気」
 *   （ジャブの後にときどき溜め斬りを続けて出す＝連携を教える。強さはほとんど変わらない）
 * - ダメージを与えたプレイヤーに回復不可を付ける（全ボス共通の heal-lock-ticks）
 * - プレイヤーを1人倒すと、満足して花びらと共に消える（コマンドで召喚した場合）
 * - ジャストガードの練習（extra.lesson）: 登場の後、誰かがジャストガードに成功するまで、
 *   わかりやすい素振り（lesson_swing。合図のカウントダウン付き）だけを繰り返し、ダメージを一切受けない。
 *   殴られたら叱り、素振りが当たったら「盾が無い / 構えるのが早い / 遅い」を見分けて叱る。成功したら褒めて本番へ
 * - 弾き返しを教える: 練習が終わると、すぐに花びらの玉（petal_orb）を投げる。ジャストガードで弾き返せる玉で、
 *   初めて投げたときにやり方を、初めて弾き返されたときに「ほかのボスの飛んでくる攻撃にも弾き返せるものがある」と教える
 */
class TutorialAlphaBoss(spawn: BossSpawn) : Boss(spawn) {

    override val hurtSounds: List<SoundCue> get() = TutorialAlphaSounds.HURT

    /** 技の説明を出すか（extra.hints） */
    private val hintsEnabled = config.boolean(Names.BossConfig.HINTS, true)
    /** 同じ技の説明を毎回出すか（false なら技ごとに最初の1回だけ） */
    private val hintRepeat = config.boolean(Names.BossConfig.HINT_REPEAT, false)
    /** 少しだけ本気になってから、ジャブの後に溜め斬りを続ける確率 */
    private val chainChance = config.double(Names.BossConfig.CHAIN_CHANCE, 0.5).coerceIn(0.0, 1.0)

    private val dashSettings = DashSkill.Settings.load(skillSection(DASH))

    /** 説明を出した技 */
    private val hinted = HashSet<String>()
    /** 前の tick の技（技が変わったのを見つける） */
    private var lastSkillId: String? = null
    /** 今の技が始まってからの tick */
    private var skillAge = 0
    /** 少しだけ本気になったか */
    private var serious = false

    /** 最初の教え（リソースパックの案内）の最中か */
    private var briefing = false
    override val isPaused: Boolean get() = briefing

    // ---- ジャストガードの練習 ----
    /** 練習が終わったか（誰かがジャストガードに成功した。練習を使わない設定なら最初から true） */
    private var lessonDone = !config.boolean(Names.BossConfig.LESSON_ENABLED, true)
    private val scoldCooldown = config.int(Names.BossConfig.LESSON_SCOLD_COOLDOWN_TICKS, 60)
    /** 人ごとの、最後に叱った時刻（[clock]） */
    private val scoldedAt = HashMap<UUID, Int>()
    private var clock = 0
    private val countdown = config.stringList(Names.BossConfig.LESSON_COUNTDOWN)
    private val countdownTicks = config.stringList(Names.BossConfig.LESSON_COUNTDOWN_TICKS).mapNotNull { it.trim().toIntOrNull() }

    override fun createPhases(): List<BossPhase> {
        val rageRatio = config.double(Names.BossConfig.RAGE_HEALTH_RATIO, 0.5)
        return listOf(
            BossPhase(NORMAL, 1.0, skills() + lessonSwing()),
            // 少しだけ本気: 技はそのまま（ジャブの後の溜め斬りは onTick で続ける）
            BossPhase(SERIOUS, rageRatio, skills()),
        )
    }

    private fun skills(): List<BossSkill> {
        val swordSlash = BoneStrikeSkill.Settings.load(skillSection(SWORD_SLASH))
        val leap = LeapSkill.Settings.load(skillSection(LEAP))
        val jab = BoneStrikeSkill.Settings.load(skillSection(JAB))
        return listOf<BossSkill>(
            DashSkill(DASH, dashSettings, TutorialAlphaSounds.dash(dashSettings)),
            BoneStrikeSkill(SWORD_SLASH, swordSlash, TutorialAlphaSounds.swordSlash(swordSlash)) { _, victim ->
                if (victim is Player) victim.world.playSound(victim.location, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.8f)
            },
            LeapSkill(LEAP, leap, TutorialAlphaSounds.leap(leap)),
            BoneStrikeSkill(JAB, jab, TutorialAlphaSounds.jab(jab)),
            chargeSlash(),
            petalOrb(),
        ).map { AfterLesson(it) }
    }

    /** 花びらの玉（弾き返せる） */
    private fun petalOrb(): PetalOrbSkill = PetalOrbSkill(PETAL_ORB, PetalOrbSkill.Settings.load(skillSection(PETAL_ORB)))

    /** 練習の素振り: 歩いて近づき、合図に合わせてゆっくり1回振る。練習が終わるまでは、これしか出さない */
    private fun lessonSwing(): BossSkill {
        val s = BoneStrikeSkill.Settings.load(skillSection(LESSON_SWING))
        return InLesson(BoneStrikeSkill(LESSON_SWING, s, TutorialAlphaSounds.lessonSwing(countdownTicks, s)))
    }

    /** 練習が終わるまで出さない技 */
    private inner class AfterLesson(private val inner: BossSkill) : BossSkill by inner {
        override fun canStart(boss: Boss): Boolean = lessonDone && inner.canStart(boss)
    }

    /** 練習の間だけ出す技 */
    private inner class InLesson(private val inner: BossSkill) : BossSkill by inner {
        override fun canStart(boss: Boss): Boolean = !lessonDone && inner.canStart(boss)
    }

    private fun chargeSlash(): BoneStrikeSkill {
        val s = BoneStrikeSkill.Settings.load(skillSection(CHARGE_SLASH))
        return BoneStrikeSkill(CHARGE_SLASH, s, TutorialAlphaSounds.chargeSlash(s))
    }

    // ===== 召喚・毎tick =====

    override fun onSpawn() {
        val section = skillSection(RESOURCE_PACK)
        val s = PerformSkill.Settings.load(section)
        briefing = hintsEnabled && s.endTicks > 0 && section.contains(Names.SkillConfig.HINT)
        if (!briefing) {
            queueSkill(greeting())
            return
        }
        // 技が始まる前の 1tick にも歩き出さないよう、ここで止める
        entity.setAI(false)
        queueSkill(resourcePackNotice(s))
    }

    override fun onTick() {
        val id = currentSkill?.id
        if (id != lastSkillId) {
            val ended = lastSkillId
            lastSkillId = id
            skillAge = 0
            if (id != null) showHint(id)
            // 少しだけ本気: ジャブの後に、ときどき溜め斬りを続ける（連携）
            if (ended == JAB && id == null && serious && !isDying && !hasQueuedSkill && Random.nextDouble() < chainChance) {
                queueSkill(chargeSlash())
                showHint(CHAIN)
            }
        } else {
            skillAge++
        }
        if (id == DASH) drawDashWarning()
        clock++
        if (id == LESSON_SWING) lessonCountdown()
    }

    override fun onPhaseChange(from: BossPhase?, to: BossPhase) {
        if (from == null || to.id != SERIOUS) return
        queueSkill(getSerious())
    }

    /** プレイヤーを1人倒したら消える（旅商人から召喚した場合は呼ばれず、参加者の死亡回数で消える） */
    override fun onKillPlayer(player: Player) {
        vanish()
    }

    // ===== ジャストガードの練習 =====

    /** 素振りの合図: 振り始めから決まった tick に、アクションバーのカウントダウンと剣の光 */
    private fun lessonCountdown() {
        val t = currentSkill?.soundTick ?: return
        val index = countdownTicks.indexOf(t)
        if (index < 0) return
        val text = countdown.getOrNull(index) ?: return
        val message = MiniMessage.miniMessage().deserialize(text)
        audience().forEach { it.sendActionBar(message) }
        val at = entity.location.add(0.0, entity.height * 0.7, 0.0).add(Facing.forward(entity).multiply(0.7))
        val last = index == countdownTicks.lastIndex
        at.world.spawnParticle(if (last) Particle.END_ROD else Particle.WAX_OFF, at, if (last) 12 else 5, 0.2, 0.2, 0.2, if (last) 0.08 else 0.0)
    }

    /** 最初の教えの間は、火・毒なども含めて効かない */
    override fun interceptDamage(event: EntityDamageEvent): Boolean = briefing

    /** 練習が終わるまでは、どんな攻撃も効かない。殴ってきた人を叱る */
    override fun modifyIncomingDamage(event: EntityDamageByEntityEvent) {
        // 最初の教えの間は叱らない（案内がチャットで流れてしまう）
        if (briefing) {
            event.isCancelled = true
            return
        }
        if (lessonDone) {
            super.modifyIncomingDamage(event)
            return
        }
        event.isCancelled = true
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        } ?: return
        val at = entity.location.add(0.0, entity.height + 0.4, 0.0)
        at.world.spawnParticle(Particle.ANGRY_VILLAGER, at, 1, 0.2, 0.1, 0.2, 0.0)
        at.world.playSound(at, Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 0.8f, 1.8f)
        scold(attacker, Names.BossConfig.LESSON_SCOLD_ATTACK, force = false)
    }

    /** 練習の素振りが当たった: 何がいけなかったかを見分けて叱る */
    override fun onDamagePlayer(player: Player, event: EntityDamageByEntityEvent) {
        if (lessonDone || currentSkill?.id != LESSON_SWING) return
        val inv = player.inventory
        val hasShield = inv.itemInMainHand.type == Material.SHIELD || inv.itemInOffHand.type == Material.SHIELD
        val key = when {
            !hasShield -> Names.BossConfig.LESSON_SCOLD_NO_SHIELD
            player.isHandRaised -> Names.BossConfig.LESSON_SCOLD_EARLY
            else -> Names.BossConfig.LESSON_SCOLD_LATE
        }
        scold(player, key, force = true)
    }

    /** 練習の素振りを、ジャストガードではないふつうのガードで受けた（構えるのが早すぎる） */
    override fun onLateGuard(player: Player) {
        if (lessonDone || currentSkill?.id != LESSON_SWING) return
        scold(player, Names.BossConfig.LESSON_SCOLD_EARLY, force = true)
    }

    /** 花びらの玉を弾き返された: 初めてなら、ほかのボスにも弾き返せる攻撃があると教える */
    override fun onReflect(player: Player) {
        showHint(REFLECT)
    }

    /** ジャストガードに成功: 練習はおしまい。褒めて本番へ */
    override fun onJustGuard(player: Player) {
        if (lessonDone) return
        lessonDone = true
        // 次は弾き返しの練習: 今の素振りが終わったら、すぐに花びらの玉を投げる
        queueSkill(petalOrb())
        val text = config.string(Names.BossConfig.LESSON_PASS)
        if (text.isNotEmpty()) {
            val body = MiniMessage.miniMessage().deserialize(text, Placeholder.unparsed("player", player.name))
            val message = lessonPrefix().append(body)
            audience().forEach { it.sendMessage(message) }
        }
        val at = entity.location.add(0.0, 1.2, 0.0)
        at.world.spawnParticle(Particle.CHERRY_LEAVES, at, 40, 0.8, 0.8, 0.8, 0.0)
        at.world.spawnParticle(Particle.HAPPY_VILLAGER, at, 16, 0.6, 0.8, 0.6, 0.0)
        audience().forEach { it.playSound(it.location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.HOSTILE, 0.8f, 1.4f) }
    }

    /**
     * 叱る（extra.lesson の言葉のどれか1つを、その人のチャットに）。
     * force でなければ、同じ人を scold-cooldown-ticks の間は叱らない
     */
    private fun scold(player: Player, key: String, force: Boolean) {
        val last = scoldedAt[player.uniqueId]
        if (!force && last != null && clock - last < scoldCooldown) return
        val lines = config.stringList(key)
        if (lines.isEmpty()) return
        scoldedAt[player.uniqueId] = clock
        player.sendMessage(lessonPrefix().append(MiniMessage.miniMessage().deserialize(lines.random())))
        player.playSound(entity.location, Sound.ENTITY_VILLAGER_NO, SoundCategory.HOSTILE, 1.0f, 1.5f)
    }

    private fun lessonPrefix(): Component = config.component(Names.BossConfig.LESSON_PREFIX, def = "<light_purple>[アルファ] ")

    /** 消えるときの演出：花びらとキラキラ */
    override fun playVanishEffect() {
        val world = entity.world
        val loc = entity.location.add(0.0, 1.0, 0.0)
        world.spawnParticle(Particle.CHERRY_LEAVES, loc, 60, 0.6, 1.0, 0.6, 0.0)
        world.spawnParticle(Particle.END_ROD, loc, 30, 0.5, 0.8, 0.5, 0.05)
        world.playSound(loc, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, SoundCategory.HOSTILE, 1.0f, 1.5f)
        world.playSound(loc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.HOSTILE, 1.0f, 1.4f)
    }

    // ===== 登場・少しだけ本気 =====

    /** 最初の教え: 案内（hint）が読まれる間じっとしていて、終わったら挨拶へ */
    private fun resourcePackNotice(s: PerformSkill.Settings): BossSkill =
        PerformSkill(RESOURCE_PACK, s) { _, tick ->
            if (tick == s.endTicks - 1) {
                briefing = false
                queueSkill(greeting())
            }
        }

    /** 登場の挨拶: 花びらを舞わせてお辞儀する（その間は殴られても少ししか減らない） */
    private fun greeting(): BossSkill {
        val s = PerformSkill.Settings.load(skillSection(GREETING))
        return PerformSkill(GREETING, s, TutorialAlphaSounds.greeting()) { boss, tick ->
            val at = boss.entity.location.add(0.0, 1.2, 0.0)
            if (tick % 3 == 0) at.world.spawnParticle(Particle.CHERRY_LEAVES, at, 6, 0.8, 0.8, 0.8, 0.0)
            if (tick == 10) at.world.spawnParticle(Particle.END_ROD, at, 20, 0.4, 0.6, 0.4, 0.04)
        }
    }

    /** 少しだけ本気: 剣を構え直し、まわりに花びらの渦。ここからジャブの後に溜め斬りが続くことがある */
    private fun getSerious(): BossSkill {
        val section = skillSection(SERIOUS_SHIFT)
        val s = PerformSkill.Settings.load(section)
        val changeTick = section.int(Names.SkillConfig.CHANGE_TICK, 20)
        return PerformSkill(SERIOUS_SHIFT, s, TutorialAlphaSounds.serious(changeTick)) { boss, tick ->
            val center = boss.entity.location.add(0.0, 1.0, 0.0)
            val world = center.world
            // 花びらが渦を巻いて集まる
            val r = 2.5 - 1.8 * (tick.toDouble() / changeTick).coerceAtMost(1.0)
            for (k in 0 until 3) {
                val a = tick * 0.45 + k * Math.PI * 2 / 3
                world.spawnParticle(Particle.CHERRY_LEAVES, center.clone().add(Math.cos(a) * r, 0.2 * k, Math.sin(a) * r), 1, 0.0, 0.0, 0.0, 0.0)
            }
            if (tick == changeTick) {
                (boss as TutorialAlphaBoss).serious = true
                world.spawnParticle(Particle.CHERRY_LEAVES, center, 80, 1.2, 1.0, 1.2, 0.0)
                world.spawnParticle(Particle.END_ROD, center, 30, 0.6, 0.8, 0.6, 0.08)
            }
        }
    }

    // ===== 教える =====

    /** その技の説明（技の yml の hint）を参加者のチャットに出す。技ごとに最初の1回だけ（hint-repeat で毎回） */
    private fun showHint(skillId: String) {
        if (!hintsEnabled) return
        if (!hintRepeat && !hinted.add(skillId)) return
        val section = skillSection(skillId)
        if (!section.contains(Names.SkillConfig.HINT)) return
        val text = config.component(section.fullKey(Names.SkillConfig.HINT))
        val prefix = config.component(Names.BossConfig.HINT_PREFIX, def = "<light_purple>[アルファの教え] ")
        val message: Component = prefix.append(text)
        for (p in audience()) {
            p.sendMessage(message)
            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.HOSTILE, 0.6f, 1.8f)
        }
    }

    /** 説明を届ける相手（参加者。コマンドで召喚したときは近くのプレイヤー） */
    private fun audience(): List<Player> = battle?.onlineParticipants(plugin.server)
        ?: entity.getNearbyEntities(32.0, 16.0, 32.0).filterIsInstance<Player>()

    /** 突進の溜めの間、進む向きに赤い線（もうすぐ飛び出すときは濃く） */
    private fun drawDashWarning() {
        val s = dashSettings
        if (skillAge >= s.windupTicks || skillAge % 2 != 0) return
        val from = entity.location.add(0.0, 0.15, 0.0)
        val dir = Facing.forward(entity)
        val length = s.speed * (s.hitEndTicks - s.windupTicks)
        val dust = if (skillAge >= s.windupTicks - 6) DASH_WARN_HOT else DASH_WARN
        var d = 0.8
        while (d <= length) {
            from.world.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(d)), 1, 0.0, 0.0, 0.0, 0.0, dust)
            d += 0.5
        }
    }

    private companion object {
        const val NORMAL = "normal"
        const val SERIOUS = "serious"

        // 技ID（設定ファイルの skills.<ID>）
        const val DASH = "dash"
        const val SWORD_SLASH = "sword_slash"
        const val LEAP = "leap"
        const val JAB = "jab"
        const val CHARGE_SLASH = "charge_slash"
        /** 最初の教え（リソースパックの案内） */
        const val RESOURCE_PACK = "resource_pack"
        const val GREETING = "greeting"
        const val SERIOUS_SHIFT = "serious_shift"
        /** ジャブ → 溜め斬りの連携（説明だけの ID。skills.chain.hint） */
        const val CHAIN = "chain"
        /** ジャストガードの練習の素振り（成功するまでこれだけ） */
        const val LESSON_SWING = "lesson_swing"
        /** 花びらの玉（弾き返せる） */
        const val PETAL_ORB = "petal_orb"
        /** 初めて弾き返されたときの説明（説明だけの ID。skills.reflect.hint） */
        const val REFLECT = "reflect"

        val DASH_WARN = Particle.DustOptions(Color.fromRGB(255, 120, 150), 1.0f)
        val DASH_WARN_HOT = Particle.DustOptions(Color.fromRGB(255, 40, 60), 1.3f)
    }
}
