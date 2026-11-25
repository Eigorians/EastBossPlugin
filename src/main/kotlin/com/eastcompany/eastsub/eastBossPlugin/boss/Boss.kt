package com.eastcompany.eastsub.eastBossPlugin.boss

import com.eastcompany.eastsub.eastBossPlugin.EastBossPlugin
import com.eastcompany.eastsub.eastBossPlugin.battle.BossBattle
import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.display.BossBarDisplay
import com.eastcompany.eastsub.eastBossPlugin.boss.minion.MinionGroup
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigFile
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.SkillSelector
import com.eastcompany.eastsub.eastBossPlugin.status.StatusManager
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bone.RenderedBone
import kr.toxicity.model.api.tracker.EntityTracker
import kr.toxicity.model.api.tracker.TrackerUpdateAction
import kr.toxicity.model.api.util.function.BonePredicate
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import java.util.UUID

/**
 * すべてのボスの土台。各ボスはこれを継承し、[createPhases] で技とフェーズを決める。
 *
 * 毎tickの流れ:
 *   寿命チェック → フェーズ更新 → ボスバー更新 → [onTick] → 技の実行
 *
 * 技は1つずつ実行し、終わったら同じtickのうちに次の技を選ぶ（クールダウンなし）。
 * どの技も始められない間は、エンティティ本来のAIで歩いて標的に近づく
 * （[usesEntityAi] が false のボスは AI を使わず、onTick で自分で動く。空を飛ぶドラゴンなど）。
 *
 * ボス固有の振る舞いは open な onXxx を override して足す。
 */
abstract class Boss(spawn: BossSpawn) {

    val type: BossType = spawn.type
    val manager: BossManager = spawn.manager
    /** このボスの設定ファイル */
    val config: ConfigFile = spawn.config
    val settings: BossSettings = spawn.settings
    val entity: Mob = spawn.entity

    /** プレイヤーが付けた火（燃焼ダメージを受けるか・誰の分に数えるか） */
    internal val playerBurns = PlayerBurns()
    val tracker: EntityTracker = spawn.tracker

    /**
     * 旅商人から召喚したときの戦闘。参加者だけを狙い、参加者の死亡回数で消える。
     * コマンドで召喚したときは null（周りの誰でも狙う）。
     */
    val battle: BossBattle? = spawn.battle

    val id: UUID = entity.uniqueId

    val plugin: EastBossPlugin get() = manager.plugin

    /** プレイヤーへの状態異常（スタン・回復不可） */
    val status: StatusManager get() = plugin.status

    /** 手下・コンパニオン。ボスが倒された・消えたときに自動で削除される */
    val minions = MinionGroup(this)

    // ===== フェーズ・技 =====

    /**
     * フェーズの一覧を作る（HP の高い順、最初は healthRatio = 1.0）。
     * 技はボスごとに状態を持つので、ここで毎回新しく作ること。
     */
    protected abstract fun createPhases(): List<BossPhase>

    /** コンストラクタ内で abstract を呼ばないよう、最初に使うときに作る */
    private val phases: List<BossPhase> by lazy {
        createPhases().sortedByDescending { it.healthRatio }.also {
            require(it.isNotEmpty()) { "${type.id} のフェーズが1つもありません" }
        }
    }

    private var phaseIndex = -1

    /** 今のフェーズ */
    val currentPhase: BossPhase get() = phases[phaseIndex.coerceAtLeast(0)]

    private var current: BossSkill? = null

    /** 実行中の技の経過tick（効果音のタイミング用） */
    private var skillTick = 0

    /** 技と技の間で、何もせずに待つ残りの tick（[BossSettings.skillGapTicks]） */
    private var gapTicks = 0

    /** 実行中の技（無ければ null） */
    val currentSkill: BossSkill? get() = current

    /** 次に必ず出す技（[queueSkill]）。今の技が終わったら、抽選せずにこれを始める */
    private var queued: BossSkill? = null

    /** 次に出す技が決まっているか（[queueSkill] 済みで、まだ始まっていない） */
    val hasQueuedSkill: Boolean get() = queued != null

    // ===== 状態 =====

    private val bar by lazy { BossBarDisplay(this, plugin.server) }

    private var age = 0
    /** 寿命に数える tick（[isPaused] の間は進まない） */
    private var livedTicks = 0

    /** 死亡演出中（または消滅処理中）か */
    var isDying = false
        private set

    /**
     * プレイヤーに倒されたとき、とどめを刺した人（旅商人からのボス戦では参加者だけ）。
     * リロード・コマンドでの削除・時間切れ・/kill などで消えたときは null のまま（討伐にならない）。
     * BossListener が死亡イベントで決める
     */
    var slainBy: UUID? = null
        internal set
    private var deathAge = 0

    // ===== ボスごとに変えられる部分 =====

    /** ダメージを受けたときに鳴らす声（tick は使わない） */
    protected open val hurtSounds: List<SoundCue> get() = emptyList()

    /**
     * エンティティ本来の AI を使うか。false のボス（空を飛ぶドラゴンなど）は、技が終わっても AI を戻さない
     * （技は [resumeEntityAi] で戻す）。移動はすべてボス自身が行う
     */
    open val usesEntityAi: Boolean get() = true

    /** 空中（足が地面に着いていない）でも技を始めてよいか。常に飛んでいるボスは true にする */
    protected open val canStartSkillInAir: Boolean get() = false

    /**
     * 当たり判定が無い状態か（影に潜っている間など）。true の間は、攻撃も飛び道具もすり抜け、ダメージを一切受けない
     * （BossListener が止める）
     */
    open val isIntangible: Boolean get() = false

    /**
     * 戦いを止めているか（戦う前の案内など）。true の間は寿命とクリアタイムが進まない。
     * 動き・攻撃・受けるダメージはボス側で止める
     */
    open val isPaused: Boolean get() = false

    /** これ以上高い所にいる相手は狙わない（ブロック） */
    open val targetMaxHeight: Double get() = settings.targetMaxHeight

    /** 召喚直後に1回呼ばれる */
    open fun onSpawn() {}

    /** 毎tick、技の実行より前に呼ばれる */
    protected open fun onTick() {}

    /** フェーズが変わったとき（召喚直後の最初のフェーズでは from = null） */
    protected open fun onPhaseChange(from: BossPhase?, to: BossPhase) {}

    /**
     * 近接攻撃などでダメージを受ける直前。event.damage を書き換えれば受けるダメージが変わる。
     * デフォルトでは実行中の技に任せる（技ごとの被ダメージ軽減など）。
     */
    open fun modifyIncomingDamage(event: EntityDamageByEntityEvent) {
        current?.modifyIncomingDamage(this, event)
    }

    /**
     * どんな原因のダメージでも、倍率と上限を全部掛けた後に呼ばれる（/kill・奈落は除く。HP 1 で耐えるなど）。
     * event.damage は書き換えてよい。true を返すとダメージを止める（その間に自分で減らした HP は与ダメージに数える）
     */
    open fun interceptDamage(event: EntityDamageEvent): Boolean = false

    /** ダメージを受けた後 */
    open fun onHurt() {
        hurtSounds.forEach { it.play(entity) }
    }

    /** このボスがプレイヤーにダメージを与えた後 */
    open fun onDamagePlayer(player: Player, event: EntityDamageByEntityEvent) {}

    /** このボスがプレイヤーを倒したとき */
    open fun onKillPlayer(player: Player) {}

    /**
     * このボスの分身（おとり）の当たり判定（Names.Key.DECOY の付いたエンティティ）をプレイヤーが殴ったとき。
     * 攻撃そのものは BossListener が止める。旅商人からのボス戦では参加者の攻撃だけが届く
     */
    open fun onDecoyAttacked(player: Player, decoy: Entity) {}

    /**
     * プレイヤーがこのボスの攻撃をジャストガードしたとき（盾を構えた直後に受けた。ダメージは BossListener が消す）
     */
    open fun onJustGuard(player: Player) {}

    /**
     * 構えた直後（盾が効き始める前）や、ボスが重なるほど近くてバニラの盾が効かなかった攻撃を、
     * BossListener がふつうのガードとして受けたとき（ダメージは無し。ジャストガードではない）
     */
    open fun onLateGuard(player: Player) {}

    /** プレイヤーがこのボスの弾をジャストガードで弾き返したとき（skill.util.Reflect。onJustGuard の後に呼ばれる） */
    open fun onReflect(player: Player) {}

    /** true の間に与えたダメージは、盾で防げない（[unblockable]） */
    internal var guardPiercing = false
        private set

    /**
     * 盾を貫く攻撃のダメージを与える（狙撃など）。ジャストガードも、ふつうのガードも効かない。
     * 盾を構えている相手は、当たる前に盾を下ろさせる（バニラの盾に防がせない）
     */
    fun unblockable(victim: LivingEntity, block: () -> Unit) {
        if (victim is Player && victim.isHandRaised) victim.clearActiveItem()
        val before = guardPiercing
        guardPiercing = true
        try {
            block()
        } finally {
            guardPiercing = before
        }
    }

    /** 死亡演出の間、毎tick呼ばれる（deathAge = 死んでからの tick） */
    protected open fun onDyingTick(deathAge: Int) {}

    /** 消える直前（倒された・時間切れ・コマンドで消したとき）。技の外で出したエンティティなどを片付ける */
    protected open fun onDispose() {}

    // ===== 便利メソッド =====

    fun maxHealth(): Double = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: settings.maxHealth

    /** 残り HP の割合（0〜1） */
    fun healthRatio(): Double = (entity.health / maxHealth()).coerceIn(0.0, 1.0)

    /** 設定ファイル内のセクション（技やボス固有の値を読む用） */
    fun section(path: String): ConfigSection = ConfigSection(config, path)

    /** 技の設定セクション（skills.<技ID>） */
    fun skillSection(skillId: String): ConfigSection = section(Names.BossConfig.skill(skillId))

    /**
     * ボーンを名前で探す。"b_" などのタグ付きの名前でも見つかるよう、
     * BetterModel が付ける前の元の名前（rawName）で比較する。
     */
    fun findBone(name: String): RenderedBone? =
        tracker.bone { it.name().rawName().equals(name, ignoreCase = true) }

    /**
     * 当たり判定をパーティクルで表示するか。
     * デバッグモード中（/eb debug）で、config.yml の debug.show-hitbox が true のときだけ表示する。
     * 毎回読み直すので、技の途中で切り替えてもすぐ反映される。
     */
    fun debugHitbox(): Boolean =
        plugin.isDebug() && plugin.configManager.main.boolean(Names.Config.DEBUG_SHOW_HITBOX, true)

    /**
     * 指定したボーンに色を付ける（目の色で表情を変えるときなど）。
     * BetterModel の tint で、テクスチャの色に掛け算で効く（白に近いテクスチャほどその色になる）。
     * ボーン名はタグを除いた名前（glow_eye_r なら "eye_r"）。[rgb] に 0xFFFFFF を渡すと元の色に戻る
     */
    fun tintBones(rgb: Int, vararg boneNames: String) {
        for (name in boneNames) {
            tracker.update(TrackerUpdateAction.tint(rgb), BonePredicate.name(name).withoutChildren())
        }
    }

    /**
     * ボーン（とその子）を表示・非表示にする（武器の持ち替えなど）。
     * ボーン名はタグを除いた名前（glow_heat なら "heat"）
     */
    fun setPartVisible(boneName: String, visible: Boolean) {
        tracker.update(TrackerUpdateAction.togglePart(visible), BonePredicate.name(boneName).withChildren())
    }

    /**
     * 次に出す技を決める（フェーズ移行の演出など）。今の技は止めず、終わってから始める。
     * 技を出せない空中でも、着地を待たずに始める
     */
    fun queueSkill(skill: BossSkill) {
        queued = skill
    }

    /**
     * 今の技をその場で止めて、すぐに skill を始める（撃ち落とされてよろめくなど）。
     * 技の途中（tick の中）からは呼ばず、[onTick] から呼ぶこと
     */
    protected fun interruptWith(skill: BossSkill) {
        current?.stop(this)
        current = null
        gapTicks = 0
        queued = skill
    }

    /** 技の終わりに、エンティティ本来の AI を戻す（[usesEntityAi] が false のボスでは何もしない） */
    fun resumeEntityAi() {
        if (usesEntityAi) entity.setAI(true)
    }

    /** アニメーションを1回だけ再生する（speed で再生の速さ。1 が元の速さ） */
    fun playOnce(animation: String, speed: Float = 1f) {
        if (animation.isEmpty()) return
        val modifier = if (speed == 1f) AnimationModifier.DEFAULT_WITH_PLAY_ONCE
        else AnimationModifier.builder().type(AnimationIterator.Type.PLAY_ONCE).speed(speed).build()
        tracker.animate(animation, modifier)
    }

    /**
     * アニメーションを繰り返し再生する（[stopAnimation] で止める）。
     * @param lerpTicks 今の姿勢からこの tick かけてなめらかに移る
     */
    fun playLoop(animation: String, lerpTicks: Int = 1) {
        if (animation.isEmpty()) return
        tracker.animate(
            animation,
            AnimationModifier.builder().type(AnimationIterator.Type.LOOP).start(lerpTicks.coerceAtLeast(1)).build(),
        )
    }

    /** 再生中のアニメーションを止める */
    fun stopAnimation(animation: String) {
        if (animation.isNotEmpty()) tracker.stopAnimation(animation)
    }

    /** その場で消す（演出なし） */
    fun despawn() {
        manager.remove(this)
    }

    /** 消滅の演出をしてから消す（プレイヤーを倒して満足したとき・参加者の死亡上限など） */
    fun vanish() {
        if (!markDying()) return
        playVanishEffect()
        despawn()
    }

    /** [vanish] のときの演出 */
    protected open fun playVanishEffect() {}

    // ===== BossManager から呼ばれる =====

    internal fun tick() {
        age++
        if (!isPaused) livedTicks++
        if (settings.lifetimeTicks > 0 && livedTicks >= settings.lifetimeTicks) {
            despawn()
            return
        }
        minions.tick()
        updatePhase()
        bar.update(age)
        onTick()
        tickSkill()
    }

    /** 死亡イベントで呼ぶ。死亡アニメを流し、deathTicks 後に消える */
    internal fun die() {
        if (!markDying()) return
        playOnce(settings.deathAnimation)
    }

    /** 死亡演出中に毎tick呼ばれる */
    internal fun tickDying() {
        deathAge++
        onDyingTick(deathAge)
        if (deathAge >= settings.deathTicks) despawn()
    }

    /**
     * 死亡・消滅の処理を始める。技を止めてボスバーと手下を消す。
     * @return すでに始まっていたら false
     */
    protected fun markDying(): Boolean {
        if (isDying) return false
        isDying = true
        stopSkill()
        bar.close()
        minions.removeAll()
        return true
    }

    /** 後始末。BossManager.remove からだけ呼ぶ */
    internal fun dispose() {
        stopSkill()
        onDispose()
        bar.close()
        minions.removeAll()
        tracker.close()
        entity.remove()
    }

    // ===== 内部処理 =====

    private fun updatePhase() {
        val ratio = healthRatio()
        // 条件を満たす一番後ろのフェーズ（ただし戻らない）
        val target = phases.indexOfLast { ratio <= it.healthRatio }.coerceAtLeast(0)
        if (target <= phaseIndex) return

        val from = phases.getOrNull(phaseIndex)
        phaseIndex = target
        val to = phases[target]
        plugin.debug("${type.id}: フェーズ ${from?.id ?: "-"} → ${to.id} (HP ${"%.0f".format(ratio * 100)}%)")
        to.onEnter(this)
        onPhaseChange(from, to)
    }

    private fun tickSkill() {
        val skill = current
        if (skill != null) {
            val soundTick = skill.soundTick ?: skillTick
            skill.sounds.forEach { if (it.tick == soundTick) it.play(entity) }
            skillTick++
            if (!skill.tick(this)) return
            skill.stop(this)
            current = null
            gapTicks = settings.skillGapTicks
        }

        // 技と技の間の隙。決められた技（フェーズ移行の演出など）は待たずに出す
        if (queued == null && gapTicks > 0) {
            gapTicks--
            return
        }

        val forced = queued
        queued = null
        // 空中にいる間は技を出さない（着地してから）。決められた技は別
        if (forced == null && !canStartSkillInAir && !entity.isOnGround && !entity.isInWater) return

        val next = forced ?: SkillSelector.pick(this, currentPhase.skills) ?: return
        current = next
        skillTick = 0
        plugin.debug("${type.id}: 技 ${next.id} を開始")
        next.start(this)
    }

    private fun stopSkill() {
        current?.stop(this)
        current = null
        queued = null
    }
}
