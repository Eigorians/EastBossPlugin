package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSpawn
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.BladeDanceSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.CloneComboSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.CloneKunaiSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.CloneSurroundSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.DodgeSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.KunaiSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.ShadowBindSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill.ShadowStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.model.BoneKinematics
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/** 影の暗殺者（設定: bosses/assassin.yml） */
object AssassinType : BossType {
    override val id = "assassin"
    override val icon: ItemStack get() = ItemStack.of(Material.ECHO_SHARD)
    override fun create(spawn: BossSpawn): Boss = AssassinBoss(spawn)
}

/**
 * 影の暗殺者。黒装束に覆面、逆手に二本の短刀。**歩かずに、いつも小刻みなステップで動く。**
 *
 * - 技の合間は、標的のまわりを細かく跳ねて回る（遠ければジグザグに詰め、近すぎれば斜め後ろへ跳び退き、
 *   ほどよい距離なら横へ刻む）。壁の方へは跳ばず、召喚地点から離れすぎない
 * - 見切り: 技の合間（ステップ中）に殴られると、dodge-chance の確率ですり抜けて後ろへ跳び、残像を残して苦無を投げ返す。
 *   技を出している最中・出した後の隙に殴れば必ず当たる
 * - 連撃（踏み込みながら3連続の斬撃）/ 苦無（扇状に投げる）/ 影縫い（足元に苦無を投げ、刺さった所にいると動けなくなり、そこへ連撃）
 * - 影渡り: 地面に沈み、黒い影になって這い寄り（この間は攻撃が一切当たらない）、背後から飛び出して斬り上げる。
 *   飛び出す前に、その場所の影が広がって鼓動の音がする。飛び出した直後が殴りどき
 * - 光に弱い: 影は明るい所（松明などブロックの光が light-expose-level 以上）を通ると引きずり出され、
 *   目をかばってよろめく（大きな隙）。明るい所では影に潜れない
 * - HP が rage-health-ratio 以下で本気（目が赤くなり、刃に紫の光が走る）。技が強くなり、
 *   影渡りに分身（偽物の影は先に弾けて、本物だけが背後から出る。本物の影には目の光が浮かぶ）、
 *   影舞（予告の線に沿って何度も一閃 → 跳び上がって突き下ろし → 疲れ果てる）が加わる
 * - 本気の分身技: 残像の連撃（連撃に合わせて左右から分身が斬りかかる）/ 分身の苦無（3方向から一斉に投げる）
 * - HP が final-health-ratio 以下で瀕死になり、分身の囲み（円になって囲む分身の中から本物を見つけて叩く大技）を使う。
 *   分身は殴ると煙になって消える（[AssassinClone]）
 * - 倒されると、膝をつき、影に溶けて沈んでいく
 *
 * エンティティの AI は使わない（[usesEntityAi] = false）。移動はステップと技でテレポートして行う。
 */
class AssassinBoss(spawn: BossSpawn) : Boss(spawn) {

    override val usesEntityAi: Boolean get() = false
    /** AI を切っていると足が地面に着いているかが更新されないので、着地は自分で合わせる（[settle]） */
    override val canStartSkillInAir: Boolean get() = true
    override val hurtSounds: List<SoundCue> get() = AssassinSounds.HURT
    override val isIntangible: Boolean get() = submerged

    /** 投げた苦無（技が終わっても飛び続ける） */
    val kunai = BulletField(this)

    /** 本気になったか（第2フェーズ） */
    var isRaging = false
        private set

    /** 影に潜っているか（モデルを隠し、攻撃がすり抜ける） */
    var submerged = false
        private set

    /** 空中にいる技の最中（[settle] で地面へ落とさない） */
    var floating = false

    /** 左手に苦無を持っているか（投げ技の間だけ。左の短刀と持ち替える） */
    private var kunaiInHand = false

    /** 出している分身（技が消し忘れても、ボスが消えるときにまとめて消す） */
    private val clones = mutableListOf<AssassinClone>()

    // ===== ステップ（extra） =====
    private val stepMin = config.double(Names.BossConfig.STEP_DISTANCE_MIN, 1.6).coerceAtLeast(0.3)
    private val stepMax = config.double(Names.BossConfig.STEP_DISTANCE_MAX, 2.6).coerceAtLeast(stepMin)
    private val stepTicks = config.int(Names.BossConfig.STEP_TICKS, 3).coerceAtLeast(1)
    private val pauseMin = config.int(Names.BossConfig.STEP_PAUSE_MIN, 4).coerceAtLeast(0)
    private val pauseMax = config.int(Names.BossConfig.STEP_PAUSE_MAX, 9).coerceAtLeast(pauseMin)
    private val preferredDistance = config.double(Names.BossConfig.PREFERRED_DISTANCE, 4.5)
    private val closeInDistance = config.double(Names.BossConfig.CLOSE_IN_DISTANCE, 7.0)
    private val backOffDistance = config.double(Names.BossConfig.BACK_OFF_DISTANCE, 2.2)
    private val homeRadius = config.double(Names.BossConfig.HOME_RADIUS, 14.0).coerceAtLeast(2.0)

    // ===== 見切り・影・目 =====
    private val dodgeChance = config.double(Names.BossConfig.DODGE_CHANCE, 0.45).coerceIn(0.0, 1.0)
    private val dodgeCooldown = config.int(Names.BossConfig.DODGE_COOLDOWN_TICKS, 80).coerceAtLeast(0)
    private val dodgeMessage: Component = config.component(Names.BossConfig.DODGE_MESSAGE, def = "<gray>見切られた")
    /** 影がこの明るさ（ブロックの光）以上の所に入ると引きずり出される。0 以下か 16 以上なら光に弱くない */
    private val lightExposeLevel = config.int(Names.BossConfig.LIGHT_EXPOSE_LEVEL, 13)
    private val eyeColor = colorOf(Names.BossConfig.EYE_COLOR, 0xB27CFF)
    private val rageEyeColor = colorOf(Names.BossConfig.RAGE_EYE_COLOR, 0xFF2A3A)

    /** 戦いの中心（召喚地点） */
    lateinit var home: Location
        private set
    private var age = 0

    // ステップの状態
    private var stepLeft = 0
    private var stepVec = Vector()
    private var pause = 0
    private var strafeSign = if (Random.nextBoolean()) 1.0 else -1.0
    private var zigSign = 1.0
    private var footTarget: LivingEntity? = null

    // 見切り
    private var dodgeCooldownLeft = 0
    private var dodgeFrom: LivingEntity? = null

    // ===== フェーズ・技 =====

    override fun createPhases(): List<BossPhase> {
        val rageRatio = config.double(Names.BossConfig.RAGE_HEALTH_RATIO, 0.5)
        val finalRatio = config.double(Names.BossConfig.FINAL_HEALTH_RATIO, 0.25)
        return listOf(
            BossPhase(NORMAL, 1.0, listOf(
                combo(COMBO), shadowStrike(SHADOW_STRIKE), kunaiThrow(KUNAI), shadowBind(SHADOW_BIND),
            )),
            // 本気: 強化版の技と、影舞・残像の連撃・分身の苦無
            BossPhase(RAGE, rageRatio, rageSkills()),
            // 瀕死: 本気の技に、分身の囲みが加わる（入った瞬間に1回出す）
            BossPhase(FINAL, finalRatio, rageSkills() + cloneSurround()),
        )
    }

    private fun rageSkills(): List<BossSkill> = listOf(
        combo(COMBO_RAGE), shadowStrike(SHADOW_STRIKE_RAGE), kunaiThrow(KUNAI_RAGE), shadowBind(SHADOW_BIND_RAGE),
        BladeDanceSkill(BLADE_DANCE, BladeDanceSkill.Settings.load(skillSection(BLADE_DANCE))),
        CloneComboSkill(CLONE_COMBO, CloneComboSkill.Settings.load(skillSection(CLONE_COMBO)), combo(CLONE_COMBO)),
        CloneKunaiSkill(CLONE_KUNAI, CloneKunaiSkill.Settings.load(skillSection(CLONE_KUNAI))),
    )

    private fun cloneSurround() = CloneSurroundSkill(CLONE_SURROUND, CloneSurroundSkill.Settings.load(skillSection(CLONE_SURROUND)))

    /** 連撃: ステップで詰め寄り、1振りごとに踏み込んで斬る */
    private fun combo(id: String): BoneStrikeSkill {
        val section = skillSection(id)
        val s = BoneStrikeSkill.Settings.load(section)
        val knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.35)
        return BoneStrikeSkill(id, s, AssassinSounds.combo(s)) { boss, victim ->
            val away = victim.location.toVector().subtract(boss.entity.location.toVector()).setY(0)
            if (away.lengthSquared() > 1e-4) victim.velocity = away.normalize().multiply(knockback).setY(0.15)
            victim.world.spawnParticle(Particle.DUST, victim.location.add(0.0, victim.height * 0.6, 0.0), 6, 0.2, 0.3, 0.2, 0.0, AssassinFx.AURA)
        }
    }

    private fun shadowStrike(id: String) = ShadowStrikeSkill(id, ShadowStrikeSkill.Settings.load(skillSection(id)))
    private fun kunaiThrow(id: String) = KunaiSkill(id, KunaiSkill.Settings.load(skillSection(id)))
    private fun shadowBind(id: String) = ShadowBindSkill(id, ShadowBindSkill.Settings.load(skillSection(id)))

    /** 影縫いで捕まえた相手への追い打ち（今のフェーズの連撃） */
    fun followUpCombo(): BossSkill = combo(if (isRaging) COMBO_RAGE else COMBO)

    // ===== 召喚・毎tick =====

    override fun onSpawn() {
        entity.setAI(false)
        home = entity.location.clone()
        applyParts()
        tintBones(eyeColor, EYES)
        playLoop(STANCE, lerpTicks = 4)
        queueSkill(spawnIntro())
    }

    override fun onTick() {
        age++
        // AI は常に切っておく（ほかの仕組みで戻されても）
        if (entity.hasAI()) entity.setAI(false)
        // 召喚直後はモデルの表示がまだ届いていないことがあるので、少し後にもう一度合わせる
        if (age == 5 || age == 40) {
            applyParts()
            tintBones(if (isRaging) rageEyeColor else eyeColor, EYES)
        }
        kunai.tick()
        clones.removeAll { it.isRemoved }
        clones.forEach { it.tick() }
        if (dodgeCooldownLeft > 0) dodgeCooldownLeft--
        if (!submerged && !floating) settle()
        if (isRaging && !submerged && age % 4 == 0) AssassinFx.rageAura(entity)

        // 見切り（攻撃をかわした直後）: 後ろへ跳んで苦無を投げ返す
        val from = dodgeFrom
        if (from != null) {
            dodgeFrom = null
            stepLeft = 0
            interruptWith(dodge(from))
            return
        }

        if (currentSkill == null && !hasQueuedSkill) footwork() else stepLeft = 0
    }

    override fun onPhaseChange(from: BossPhase?, to: BossPhase) {
        if (from == null) return
        if (to.id == FINAL) {
            // 瀕死: すぐに分身の囲み（本気の演出より先にここまで削られたら、その場で本気になる）
            if (!isRaging) enrage(4.0, 0.8)
            queueSkill(cloneSurround())
            return
        }
        if (to.id != RAGE) return
        // 本気: 腕を交差して身を沈め、change-tick で影がはじける（目が赤くなり、刃に光が走る）
        val section = skillSection(RAGE_SHIFT)
        val s = PerformSkill.Settings.load(section)
        val changeTick = section.int(Names.SkillConfig.CHANGE_TICK, 24)
        val pushRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 6.0)
        val push = section.double(Names.SkillConfig.KNOCKBACK, 1.2)
        queueSkill(PerformSkill(RAGE_SHIFT, s, AssassinSounds.rageShift(changeTick)) { boss, tick ->
            val a = boss as AssassinBoss
            if (tick < changeTick && tick % 2 == 0) a.gatherEffect(tick.toDouble() / changeTick)
            if (tick == changeTick) a.enrage(pushRadius, push)
        })
    }

    /**
     * 見切り: 技の合間（ステップ中）に近接攻撃を受けると、dodge-chance の確率でかわす（ダメージなし）。
     * 次の tick に後ろへ跳んで苦無を投げ返す。飛び道具は見切らない
     */
    override fun modifyIncomingDamage(event: EntityDamageByEntityEvent) {
        super.modifyIncomingDamage(event)
        if (event.isCancelled) return
        val attacker = event.damager as? LivingEntity ?: return
        if (currentSkill != null || hasQueuedSkill || isDying || submerged || dodgeCooldownLeft > 0) return
        if (Random.nextDouble() >= dodgeChance) return
        event.isCancelled = true
        dodgeCooldownLeft = dodgeCooldown
        dodgeFrom = attacker
        AssassinFx.parry(entity.location.add(0.0, entity.height * 0.6, 0.0))
        AssassinSounds.dodge(entity)
        (attacker as? Player)?.sendActionBar(dodgeMessage)
    }

    /** 倒しそうな一撃を当てたら、次の技は挑発（ほかに決まった技がなければ） */
    override fun onDamagePlayer(player: Player, event: EntityDamageByEntityEvent) {
        if (isDying || hasQueuedSkill) return
        if (event.finalDamage < player.health + player.absorptionAmount) return
        val s = PerformSkill.Settings.load(skillSection(TAUNT))
        queueSkill(PerformSkill(TAUNT, s, AssassinSounds.taunt()))
    }

    override fun playVanishEffect() {
        surface()
        AssassinFx.inkBurst(entity.location, 1.5)
        AssassinSounds.dissolve(entity)
    }

    // ===== 死亡: 膝をつき、影に溶けて沈んでいく =====

    override fun onDyingTick(deathAge: Int) {
        val at = entity.location
        val world = at.world
        if (deathAge == 1) {
            clearClones()
            surface()
            kunaiInHand = false
            applyParts()
            AssassinSounds.death(entity)
        }
        if (deathAge in 30..56) {
            // 足元から影が広がり、体が溶けていく
            val f = (deathAge - 30) / 26.0
            AssassinFx.shadow(at, 0.6 + 1.4 * f)
            world.spawnParticle(Particle.SQUID_INK, at.clone().add(0.0, 0.6 * (1 - f) + 0.1, 0.0), 3, 0.35, 0.4, 0.35, 0.02)
            world.spawnParticle(Particle.DUST, at.clone().add(0.0, 1.0 * (1 - f) + 0.2, 0.0), 6, 0.3, 0.5, 0.3, 0.0, AssassinFx.AFTERIMAGE)
        }
        if (deathAge == 44) AssassinSounds.dissolve(entity)
        if (deathAge > 56) AssassinFx.shadow(at, 2.0 * (1 - (deathAge - 56) / 8.0).coerceAtLeast(0.1))
    }

    override fun onDispose() {
        kunai.clear()
        clearClones()
    }

    /** 分身の当たり判定を殴られた → その分身に知らせる */
    override fun onDecoyAttacked(player: Player, decoy: Entity) {
        clones.firstOrNull { it.owns(decoy) }?.onHit?.invoke(player)
    }

    // ===== 分身 =====

    /** 今の目の色 */
    val currentEyeColor: Int get() = if (isRaging) rageEyeColor else eyeColor

    /**
     * at に分身を出す（黒いしぶきと一緒に）。モデルが無ければ null。
     * @param hittable 殴れる（殴ると [AssassinClone.onHit]）
     */
    fun spawnClone(at: Location, hittable: Boolean, effect: Boolean = true): AssassinClone? {
        val clone = AssassinClone.create(this, at, hittable) ?: return null
        clone.matchLook(currentEyeColor, isRaging)
        clone.playLoop(STANCE, lerpTicks = 2)
        clones += clone
        if (effect) {
            AssassinFx.inkBurst(at, 0.8)
            AssassinSounds.decoyBurst(at)
        }
        return clone
    }

    /** 分身を消す。effect なら煙になって消える */
    fun removeClone(clone: AssassinClone, effect: Boolean = true) {
        if (effect && !clone.isRemoved) {
            AssassinFx.inkBurst(clone.location, 1.0)
            clone.location.world.spawnParticle(Particle.LARGE_SMOKE, clone.location.clone().add(0.0, 1.0, 0.0), 14, 0.3, 0.6, 0.3, 0.03)
            AssassinSounds.decoyBurst(clone.location)
        }
        clone.close()
        clones.remove(clone)
    }

    /**
     * p0 → p1 の線分から width 以内にいる相手を斬る（分身の突進など）。hit に入っている相手には当てない（当てたら足す）
     */
    fun hitAlongLine(p0: Location, p1: Location, width: Double, damage: Double, knockback: Double, hit: MutableSet<UUID>) {
        val seg = Vector(p1.x - p0.x, 0.0, p1.z - p0.z)
        val segLen2 = seg.lengthSquared()
        val mid = p0.clone().add(seg.clone().multiply(0.5))
        val r = Math.sqrt(segLen2) / 2 + width + 1.0
        for (e in mid.world.getNearbyEntities(mid, r, 3.0, r)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(this, e) || e.uniqueId in hit) continue
            val dy = e.location.y - p0.y
            if (dy < -1.5 || dy > 2.5) continue
            val w = Vector(e.location.x - p0.x, 0.0, e.location.z - p0.z)
            val t = if (segLen2 < 1e-6) 0.0 else (w.dot(seg) / segLen2).coerceIn(0.0, 1.0)
            if (w.subtract(seg.clone().multiply(t)).length() > width + e.width / 2) continue
            hit += e.uniqueId
            e.noDamageTicks = 0
            e.damage(damage, entity)
            if (e is Player && status.justGuard.guardedNow(e)) continue
            val push = if (segLen2 > 1e-6) seg.clone().normalize().multiply(knockback) else Vector()
            e.velocity = push.setY(0.25)
            e.world.spawnParticle(Particle.CRIT, e.location.add(0.0, e.height * 0.6, 0.0), 10, 0.2, 0.3, 0.2, 0.3)
        }
    }

    /**
     * center から forward の向きの扇（degrees 度・半径 radius）の中にいる相手を斬る（分身の斬撃など）。
     * hit に入っている相手には当てない（当てたら足す）
     */
    fun hitArc(center: Location, forward: Vector, radius: Double, degrees: Double, damage: Double, knockback: Double, hit: MutableSet<UUID>) {
        val f = forward.clone().setY(0)
        if (f.lengthSquared() < 1e-6) return
        f.normalize()
        val cosLimit = Math.cos(Math.toRadians(degrees / 2))
        for (e in center.world.getNearbyEntities(center, radius + 1.0, 3.0, radius + 1.0)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(this, e) || e.uniqueId in hit) continue
            val v = Vector(e.location.x - center.x, 0.0, e.location.z - center.z)
            val dy = e.location.y - center.y
            val d = v.length()
            if (d > radius + e.width / 2 || dy < -1.5 || dy > 2.5) continue
            if (d > 0.6 && v.clone().multiply(1 / d).dot(f) < cosLimit) continue
            hit += e.uniqueId
            e.noDamageTicks = 0
            e.damage(damage, entity)
            if (e is Player && status.justGuard.guardedNow(e)) continue
            e.velocity = (if (d > 1e-3) v.multiply(knockback / d) else Vector()).setY(0.2)
        }
    }

    private fun clearClones() {
        clones.toList().forEach { removeClone(it, effect = false) }
    }

    // ===== 登場・本気 =====

    /** 登場: 足元に影が広がり、そこから浮かび上がって、両手の短刀を回して構える */
    private fun spawnIntro(): BossSkill {
        val s = PerformSkill.Settings.load(skillSection(SPAWN_INTRO))
        return PerformSkill(SPAWN_INTRO, s, AssassinSounds.spawn(s)) { boss, tick ->
            val at = boss.entity.location
            if (tick < 24) AssassinFx.shadow(at, 0.6 + tick / 24.0 * 1.2)
            if (tick == 6) AssassinFx.inkBurst(at, 0.6)
        }
    }

    /** 本気の溜め: 周りから影が吸い寄せられる（progress = 0〜1） */
    fun gatherEffect(progress: Double) {
        val center = entity.location.add(0.0, entity.height * 0.45, 0.0)
        val world = center.world
        val radius = 3.5 - 2.0 * progress
        repeat(8) {
            val a = Random.nextDouble(Math.PI * 2)
            val p = center.clone().add(kotlin.math.cos(a) * radius, Random.nextDouble(-0.8, 0.8), kotlin.math.sin(a) * radius)
            world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, AssassinFx.AURA)
        }
        AssassinFx.shadow(entity.location, 1.0 + progress)
    }

    /** 本気になる: 目が赤く、刃に紫の光。影がはじけて、まわりの相手を吹き飛ばす */
    fun enrage(pushRadius: Double, push: Double) {
        isRaging = true
        tintBones(rageEyeColor, EYES)
        applyParts()
        val at = entity.location
        AssassinFx.inkBurst(at, 2.0)
        AssassinFx.ring(at, pushRadius, AssassinFx.WARN_HOT, 60)
        at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, entity.height * 0.85, 0.0), 30, 0.3, 0.2, 0.3, 0.0, AssassinFx.EYE_RAGE)
        for (e in entity.getNearbyEntities(pushRadius, 3.0, pushRadius)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(this, e)) continue
            val away = e.location.toVector().subtract(at.toVector()).setY(0)
            if (away.lengthSquared() < 1e-4) continue
            e.velocity = away.normalize().multiply(push).setY(0.45)
        }
    }

    // ===== 技の合間: 小刻みなステップ =====

    /** 足元が空いていたら落とす（AI を切っているので重力が効かない）。地面より上へは動かさない */
    private fun settle() {
        val loc = entity.location
        val ground = Ground.groundY(loc.world, loc.x, loc.y + 0.5, loc.z, 6)
        if (ground < loc.y - 0.05) {
            loc.y = maxOf(ground, loc.y - 0.6)
            entity.teleport(loc)
        }
    }

    private fun footwork() {
        if (stepLeft > 0) {
            stepTick()
            return
        }
        val t = TargetFinder.findNearest(this, settings.searchRange)
        footTarget = t
        if (t != null) Facing.faceTarget(this, t)
        if (pause > 0) {
            pause--
            return
        }
        val dir = chooseStep(t)
        if (dir == null) {
            pause = 10
            return
        }
        val distance = Random.nextDouble(stepMin, stepMax + 1e-6)
        stepVec = dir.clone().multiply(distance / stepTicks)
        stepLeft = stepTicks
        playOnce(stepAnimation(dir))
        AssassinSounds.step(entity)
        pause = Random.nextInt(pauseMin, pauseMax + 1)
        stepTick()
    }

    /** ステップを1tick分進める（標的の方を向いたまま） */
    private fun stepTick() {
        val from = entity.location
        val to = Ground.step(from.clone().add(stepVec))
        if (to == null) {
            stepLeft = 0
            return
        }
        val t = footTarget?.takeIf { TargetFinder.isValidTarget(this, it) }
        to.yaw = t?.let { Facing.horizontalDir(entity, it) }?.let { Facing.yawOf(it) } ?: from.yaw
        to.pitch = 0f
        entity.teleport(to)
        stepLeft--
        AssassinFx.stepDust(from)
    }

    /** 次のステップの向き。跳べる所がなければ null */
    private fun chooseStep(t: LivingEntity?): Vector? {
        val from = entity.location
        if (t == null) {
            // 標的がいなければ、召喚地点の近くへ戻る
            val back = Vector(home.x - from.x, 0.0, home.z - from.z)
            if (home.world != from.world || back.length() < 3.0) return null
            return pickDir(back.normalize(), stepMax)
        }
        val to = Vector(t.location.x - from.x, 0.0, t.location.z - from.z)
        val dist = to.length()
        if (dist < 1e-3) return pickDir(Facing.forward(entity).rotateAroundY(Math.PI * 0.75), stepMax)
        val fwd = to.multiply(1 / dist)
        val side = Vector(-fwd.z, 0.0, fwd.x)
        val wanted = when {
            // 遠い: ジグザグに詰め寄る
            dist > closeInDistance -> {
                zigSign = -zigSign
                fwd.clone().rotateAroundY(Math.toRadians(zigSign * Random.nextDouble(20.0, 40.0)))
            }
            // 近すぎる: 斜め後ろへ跳び退く
            dist < backOffDistance -> fwd.clone().multiply(-1).rotateAroundY(Math.toRadians(Random.nextDouble(-50.0, 50.0)))
            // ほどよい: 横へ刻みながら距離を保つ（ときどき向きを変える）
            else -> {
                if (Random.nextDouble() < 0.3) strafeSign = -strafeSign
                val inOut = ((dist - preferredDistance) / preferredDistance).coerceIn(-0.7, 0.7)
                side.clone().multiply(strafeSign).add(fwd.clone().multiply(inOut * 1.5)).normalize()
            }
        }
        return pickDir(wanted, stepMax)
    }

    /**
     * preferred に近く、distance ブロック先まで地面が続いていて、召喚地点から離れすぎない向きを選ぶ
     * （16方向から。行き止まりなら横や斜めへ抜ける）。どこへも跳べなければ null
     */
    fun pickDir(preferred: Vector, distance: Double): Vector? {
        val pref = preferred.clone().setY(0).let { if (it.lengthSquared() < 1e-6) Facing.forward(entity) else it.normalize() }
        val from = entity.location
        val pull = homePull()
        var best: Vector? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until 16) {
            val dir = pref.clone().rotateAroundY(Math.PI * 2 * i / 16)
            val free = Ground.freeLength(from, dir, distance)
            if (free < 0.5) continue
            val score = free / distance * 3.0 + dir.dot(pref) * 1.5 + dir.dot(pull) * 1.5
            if (score > bestScore) {
                bestScore = score
                best = dir
            }
        }
        return best
    }

    /** 召喚地点へ戻る向き（home-radius 以内なら長さ 0。離れるほど長く、最大 1.5） */
    private fun homePull(): Vector {
        val from = entity.location
        if (home.world != from.world) return Vector()
        val v = Vector(home.x - from.x, 0.0, home.z - from.z)
        val dist = v.length()
        if (dist <= homeRadius || dist < 1e-3) return Vector()
        return v.multiply(((dist - homeRadius) / homeRadius).coerceAtMost(1.5) / dist)
    }

    /** 進む向きに合ったステップのアニメ（標的の方を向いたまま、前後左右のどれに跳ぶか） */
    private fun stepAnimation(dir: Vector): String {
        val face = footTarget?.let { Facing.horizontalDir(entity, it) } ?: Facing.forward(entity)
        val right = Vector(-face.z, 0.0, face.x)
        val f = dir.dot(face)
        val r = dir.dot(right)
        return if (abs(r) >= abs(f)) (if (r > 0) STEP_RIGHT else STEP_LEFT) else (if (f > 0) STEP_FRONT else STEP_BACK)
    }

    private fun dodge(from: LivingEntity): BossSkill {
        val id = if (isRaging) DODGE_RAGE else DODGE
        return DodgeSkill(id, DodgeSkill.Settings.load(skillSection(id)), from)
    }

    // ===== 影 =====

    /** 影に潜る: モデルを隠し、攻撃も飛び道具もすり抜けるようにする */
    fun submerge() {
        if (submerged) return
        submerged = true
        setPartVisible(ROOT_BONE, false)
        entity.isInvulnerable = true
        entity.isCollidable = false
    }

    /** 影から出る: モデルを戻し、当たるようにする */
    fun surface() {
        if (!submerged) return
        submerged = false
        setPartVisible(ROOT_BONE, true)
        entity.isInvulnerable = false
        entity.isCollidable = true
        applyParts()
    }

    /** その場所が影でいられないほど明るいか（ブロックの光。日光は数えない） */
    fun isLit(at: Location): Boolean =
        lightExposeLevel in 1..15 && at.block.lightFromBlocks >= lightExposeLevel

    /**
     * 影を from から dest へ、地面に沿って speed ブロック進めた位置。
     * 壁があれば斜め・横へずれて回り込む。どこへも進めなければ from のまま
     */
    fun moveShadow(from: Location, dest: Location, speed: Double): Location {
        val v = Vector(dest.x - from.x, 0.0, dest.z - from.z)
        val d = v.length()
        if (d < 1e-3) return from.clone()
        val dir = v.multiply(1 / d)
        val len = minOf(speed, d)
        for (angle in SLIDE_ANGLES) {
            val step = dir.clone().rotateAroundY(Math.toRadians(angle)).multiply(len)
            val to = Ground.step(from.clone().add(step), maxDrop = 4) ?: continue
            to.yaw = Facing.yawOf(dir)
            to.pitch = 0f
            return to
        }
        return from.clone()
    }

    /** 影の位置を光らせる対象（参加者、いなければ近くのプレイヤー）にアクションバーで知らせる */
    fun announce(message: Component) {
        val players: List<Player> = battle?.onlineParticipants(plugin.server)
            ?: entity.getNearbyEntities(40.0, 24.0, 40.0).filterIsInstance<Player>()
        players.forEach { it.sendActionBar(message) }
    }

    // ===== 苦無 =====

    /** 左手に苦無を持つ・しまう（左の短刀と持ち替える） */
    fun holdKunai(hold: Boolean) {
        kunaiInHand = hold
        applyParts()
    }

    /**
     * 苦無を count 本、扇状（fanDegrees）に投げる。leadRatio > 0 なら、標的の動きを見越して狙う
     */
    fun throwKunai(target: LivingEntity?, count: Int, fanDegrees: Double, spec: BulletField.Spec, leadRatio: Double = 0.0) {
        val from = bonePoint(KUNAI_BONE) ?: entity.location.add(0.0, entity.height * 0.6, 0.0).add(Facing.forward(entity).multiply(0.6))
        val t = target?.takeIf { TargetFinder.isValidTarget(this, it) }
        val base = if (t != null) {
            val aim = t.location.add(0.0, t.height * 0.5, 0.0)
            if (leadRatio > 0) {
                val flight = aim.distance(from) / spec.speed
                aim.add(t.velocity.clone().setY(0).multiply(flight * leadRatio))
            }
            aim.toVector().subtract(from.toVector())
        } else Facing.forward(entity)
        if (base.lengthSquared() < 1e-4) return
        val n = count.coerceAtLeast(1)
        for (i in 0 until n) {
            val angle = if (n == 1) 0.0 else -fanDegrees / 2 + fanDegrees * i / (n - 1)
            kunai.fire(from, base.clone().rotateAroundY(Math.toRadians(angle)), spec)
        }
        from.world.spawnParticle(Particle.CRIT, from, 6, 0.1, 0.1, 0.1, 0.2)
        AssassinSounds.throwKunai(entity)
    }

    // ===== 見た目 =====

    /** 苦無・短刀・刃の光の表示を今の状態に合わせる（潜っている間は何もしない。体ごと隠しているので） */
    private fun applyParts() {
        if (submerged) return
        setPartVisible("dagger_l", !kunaiInHand)
        setPartVisible("kunai_fan", kunaiInHand)
        setPartVisible("edge_r", isRaging)
        setPartVisible("edge_l", isRaging && !kunaiInHand)
    }

    /** ボーンの位置（見つからなければ null） */
    fun bonePoint(name: String): Location? {
        val bone = findBone(name) ?: return null
        val p = BoneKinematics.worldPose(bone, entity.location).position
        return Location(entity.world, p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
    }

    private fun colorOf(key: String, def: Int): Int {
        val raw = config.string(key).trim().removePrefix("#")
        return raw.toIntOrNull(16) ?: def
    }

    companion object {
        const val NORMAL = "normal"
        const val RAGE = "rage"
        const val FINAL = "final"

        /** モデルのルート（体全体を隠すとき） */
        const val ROOT_BONE = "assassin"
        /** 目のボーン（glow_ を除いた名前） */
        const val EYES = "eyes"
        /** 苦無を投げ出す所 */
        const val KUNAI_BONE = "kunai_fan"

        // アニメ
        const val STANCE = "stance"
        const val STEP_LEFT = "step_left"
        const val STEP_RIGHT = "step_right"
        const val STEP_FRONT = "step_front"
        const val STEP_BACK = "step_back"

        // 技ID（設定ファイルの skills.<ID>）
        const val COMBO = "combo"
        const val COMBO_RAGE = "combo_rage"
        const val SHADOW_STRIKE = "shadow_strike"
        const val SHADOW_STRIKE_RAGE = "shadow_strike_rage"
        const val KUNAI = "kunai"
        const val KUNAI_RAGE = "kunai_rage"
        const val SHADOW_BIND = "shadow_bind"
        const val SHADOW_BIND_RAGE = "shadow_bind_rage"
        const val BLADE_DANCE = "blade_dance"
        const val DODGE = "dodge"
        const val DODGE_RAGE = "dodge_rage"
        const val SPAWN_INTRO = "spawn_intro"
        const val RAGE_SHIFT = "rage_shift"
        const val TAUNT = "taunt"
        const val CLONE_SURROUND = "clone_surround"
        const val CLONE_COMBO = "clone_combo"
        const val CLONE_KUNAI = "clone_kunai"

        /** 影が壁に当たったときに回り込む角度（まっすぐ → 斜め → 横） */
        private val SLIDE_ANGLES = listOf(0.0, 35.0, -35.0, 70.0, -70.0)
    }
}
