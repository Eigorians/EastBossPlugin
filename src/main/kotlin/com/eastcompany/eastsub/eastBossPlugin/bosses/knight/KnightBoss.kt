package com.eastcompany.eastsub.eastBossPlugin.bosses.knight

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSpawn
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightCircleSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightFloatSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightImpaleSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightLastStandSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightLaunchSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightLightningSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightWraithSkill
import com.eastcompany.eastsub.eastBossPlugin.model.SwordSweep
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightLeapSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill.KnightStrike
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.ChainSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.RandomChainSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.random.Random

/** 髑髏騎士（設定: bosses/knight.yml） */
object KnightType : BossType {
    override val id = "knight"
    override val icon: ItemStack get() = ItemStack.of(Material.WITHER_SKELETON_SKULL)
    override fun create(spawn: BossSpawn): Boss = KnightBoss(spawn)
}

/**
 * 髑髏騎士。ぼろぼろの赤いマントを引きずる、人の倍ほどの背の骸骨の騎士。大剣を両手で振るう。
 *
 * - 歩きはエンティティの AI（BetterModel の idle / walk が流れる）。速さは extra.move-speed
 * - 技: 薙ぎ払い（踏み込んでから振る）/ 突き（遠くから一気に踏み込む）/ 振り下ろし（前へ地割れが走る）/
 *   溜め斬り（震えながら溜めて、大きく振り抜く。基本の技）/
 *   連携（溜め斬りで始め、技をランダムに 3〜4 回続けた後、大剣にもたれて疲れる＝殴りどき）/
 *   跳躍斬り（離れた相手の所へ跳んで叩きつける。予告の輪）
 * - 浮遊: 跳躍斬り以外の技は、ほとんど（float-chance）宙に浮き上がって寄ってから、降りながら打つ（[KnightFloatSkill]。
 *   アニメは float と *_air）
 * - 体勢崩し: ジャストガードを extra.guard-break-count 回受けると、よろめいて大きな隙をさらす（被ダメ増加）
 * - HP rage-health-ratio 以下で本気（rage_shift: 震えて力を溜め、天を仰いで吠える。目が赤く、大剣が赤熱）→
 *   薙ぎ払いが深紅の斬撃波を放つ・振り下ろしの地割れが3本・連撃が長い・跳躍斬りが2連続。歩きも速くなる
 * - 地割れと斬撃波は [KnightHazards]（技が終わっても走り続ける）
 * - 瀕死: 致命の一撃を HP 1 で耐える → 全員をノーダメージで吹き飛ばし、怨霊の弾幕で寄せ付けない（[KnightLastStandSkill]）。
 *   飛び道具などは一切効かず、ジャストガードしながら近づいて入れた近接の一撃でだけ倒れる
 * - プレイヤーを倒すと、首をかしげて剣先を向ける（taunt）
 * - 死ぬと両膝をつき、大剣にすがったまま目の光が消え、灰になって崩れる
 *
 * 光るボーン: glow_eyes（目。色は tint）・glow_edge（本気の刃）。setPartVisible / tintBones では glow_ を除いた名前で呼ぶ
 */
class KnightBoss(spawn: BossSpawn) : Boss(spawn) {

    override val hurtSounds: List<SoundCue> get() = KnightSounds.HURT

    val hazards = KnightHazards(this)

    private val eyeColor = colorOf(Names.BossConfig.EYE_COLOR, 0xFFFFFF)
    private val rageEyeColor = colorOf(Names.BossConfig.RAGE_EYE_COLOR, 0xFF3030)
    private val moveSpeed = config.double(Names.BossConfig.MOVE_SPEED, 0.24)
    private val rageMoveSpeed = config.double(Names.BossConfig.RAGE_MOVE_SPEED, 0.28)

    // ---- 体勢崩し ----
    private val guardBreakCount = config.int(Names.BossConfig.GUARD_BREAK_COUNT, 3)
    private val guardBreakResetTicks = config.int(Names.BossConfig.GUARD_BREAK_RESET_TICKS, 200)
    private val guardBreakMessage: Component =
        config.component(Names.BossConfig.GUARD_BREAK_MESSAGE, def = "<red>体勢を崩した！ 今だ！")
    private var guardCount = 0
    private var lastGuardAge = -100000
    /** 次の tick によろめかせる（interruptWith は onTick から呼ぶので、ここで予約する） */
    private var breakBy: Player? = null

    /** 本気になったか */
    var isRaging = false
        private set

    private var age = 0

    // ---- 間合いを測る（技の合間に相手の周りを回り、ときどき横ステップ） ----
    private val circleChance = config.double(Names.BossConfig.CIRCLE_CHANCE, 0.7).coerceIn(0.0, 1.0)
    private val circle = KnightCircleSkill(CIRCLE, KnightCircleSkill.Settings.load(skillSection(CIRCLE)))
    // ---- 瀕死 ----
    private val lastStandEnabled = config.boolean(Names.BossConfig.LAST_STAND_ENABLED, true)
    private val lastStandTitle: Component =
        config.component(Names.BossConfig.LAST_STAND_TITLE, def = "<dark_red>髑髏騎士は倒れない")
    private val lastStandSubtitle: Component =
        config.component(Names.BossConfig.LAST_STAND_SUBTITLE, def = "<gray>怨霊をジャストガードして近づき、斬れ")
    private val lastStandBlockedMessage: Component =
        config.component(Names.BossConfig.LAST_STAND_BLOCKED_MESSAGE, def = "<gray>届かない… 近づいて直接斬れ")
    private val lastStand = KnightLastStandSkill(
        LAST_STAND, KnightLastStandSkill.Settings.load(skillSection(LAST_STAND)), KnightSounds.lastStand())

    /** HP 1 で耐えている（倒されるまで戻らない） */
    private var inLastStand = false
    private var lastWardedAge = -100

    /** 前の tick に出していた技（技が終わったのを見つける） */
    private var lastSkill: BossSkill? = null
    private var lastStepAt: Location? = null
    private var stepDistance = 0.0

    // ===== フェーズ・技 =====

    override fun createPhases(): List<BossPhase> {
        val rageRatio = config.double(Names.BossConfig.RAGE_HEALTH_RATIO, 0.5)
        return listOf(
            BossPhase(NORMAL, 1.0, listOf(
                floated(CHARGE_SLASH), floated(SLASH), floated(THRUST), floated(SLAM), floatedChain(COMBO), leap(LEAP),
                launch(LAUNCH), impale(IMPALE), wraith(WRAITH), lightning(LIGHTNING),
            )),
            BossPhase(RAGE, rageRatio, listOf(
                floated(CHARGE_SLASH_RAGE), floated(SLASH_RAGE), floated(THRUST_RAGE), floated(SLAM_RAGE), floatedChain(COMBO_RAGE),
                leapTwice(LEAP_RAGE),
                launch(LAUNCH_RAGE), impale(IMPALE_RAGE), wraith(WRAITH_RAGE), lightning(LIGHTNING_RAGE),
            )),
        )
    }

    /**
     * 宙に浮いてから打つ一振り（skills.<id>）。float-chance の割合で浮き上がって寄り、降りながら打つ（[KnightFloatSkill]）。
     * float-chance が無い（0）なら地上の一振りのまま
     */
    private fun floated(id: String): BossSkill {
        val section = skillSection(id)
        val ground = strike(id)
        if (section.double(Names.SkillConfig.FLOAT_CHANCE, 0.0) <= 0) return ground
        val s = BoneStrikeSkill.Settings.load(section)
        val firstHit = s.hitWindows.firstOrNull()?.first ?: 12
        val settings = KnightFloatSkill.Settings.load(section, s.range, firstHit)
        return KnightFloatSkill(id, settings, ground, strike(id, fromAir = true))
    }

    /** 宙に浮いてから始める連携（浮く設定は skills.<id> に書く。宙から打つのは1つ目の技＝steps の先頭だけ） */
    private fun floatedChain(id: String): BossSkill {
        val section = skillSection(id)
        val ground = chain(id)
        val firstId = section.stringList(Names.SkillConfig.STEPS).firstOrNull()
        if (firstId == null || section.double(Names.SkillConfig.FLOAT_CHANCE, 0.0) <= 0) return ground
        val first = BoneStrikeSkill.Settings.load(skillSection(firstId))
        val firstHit = first.hitWindows.firstOrNull()?.first ?: 12
        val settings = KnightFloatSkill.Settings.load(section, first.range, firstHit)
        return KnightFloatSkill(id, settings, ground, chain(id, firstFromAir = true))
    }

    /**
     * 大剣の一振り（skills.<id>）。BoneStrikeSkill に、yml に書いてあれば地割れ（crack-tick）と斬撃波（crescent-tick）を足す。
     * 効果音はアニメ（slash / thrust / slam）で決める。
     * fromAir: 宙から打つ版（アニメは air-animation。書かなければ <アニメ>_air。浮いて寄るので、振る前のダッシュはしない）
     */
    private fun strike(id: String, weightOverride: Int? = null, fromAir: Boolean = false): BossSkill {
        val section = skillSection(id)
        val loaded = BoneStrikeSkill.Settings.load(section)
        val base = if (weightOverride != null) loaded.copy(weight = weightOverride) else loaded
        val s = if (!fromAir) base else base.copy(
            animation = section.string(Names.SkillConfig.AIR_ANIMATION).ifEmpty { "${base.animation}_air" },
            approachSpeed = 0.0,
        )
        val hitTick = s.hitWindows.firstOrNull()?.first ?: 12
        val events = HashMap<Int, (Boss) -> Unit>()
        if (section.contains(Names.SkillConfig.CRACK_TICK)) {
            events[section.int(Names.SkillConfig.CRACK_TICK, hitTick)] = { (it as KnightBoss).slamGround(section) }
        }
        if (section.contains(Names.SkillConfig.CRESCENT_TICK)) {
            events[section.int(Names.SkillConfig.CRESCENT_TICK, hitTick)] = { (it as KnightBoss).releaseCrescent(section) }
        }
        // 溜め: charge-windows の間、大剣に赤い光が集まっていく
        val charge = section.intRanges(Names.SkillConfig.CHARGE_WINDOWS)
        val chargeFrom = charge.minOfOrNull { it.first } ?: 0
        val chargeTo = charge.maxOfOrNull { it.last } ?: 0
        for (range in charge) for (t in range) {
            val progress = (t - chargeFrom + 1).toDouble() / (chargeTo - chargeFrom + 1)
            val before = events[t]
            events[t] = { boss -> before?.invoke(boss); KnightFx.charge(boss.entity, progress) }
        }
        val sounds = when (base.animation) {
            THRUST_ANIM -> KnightSounds.thrust(hitTick)
            SLAM_ANIM -> KnightSounds.slam(section.int(Names.SkillConfig.CRACK_TICK, hitTick))
            CHARGE_ANIM -> KnightSounds.chargeSlash(chargeFrom, hitTick)
            else -> KnightSounds.slash(hitTick)
        }
        return KnightStrike(BoneStrikeSkill(id, s, sounds), events)
    }

    /**
     * 連携（skills.<id>）。steps の技で必ず始め、chain-pool からランダムに足して、合わせて chain-min 〜 chain-max 個つなぐ
     * （chain-pool が無ければ steps の順のまま）。最後に finisher（疲れなど。演出の技）を出す。
     * それぞれの技は skills.<技ID> に書く。抽選されないよう weight は連携の方で決める。
     * firstFromAir: 1つ目の技を宙から打つ版にする
     */
    private fun chain(id: String, firstFromAir: Boolean = false): BossSkill {
        val section = skillSection(id)
        val head = section.stringList(Names.SkillConfig.STEPS).mapIndexed { i, step ->
            strike(step, weightOverride = 0, fromAir = firstFromAir && i == 0)
        }
        val pool = section.stringList(Names.SkillConfig.CHAIN_POOL).map { strike(it, weightOverride = 0) }
        val finisherId = section.string(Names.SkillConfig.FINISHER)
        val finisher = if (finisherId.isEmpty()) null else perform(finisherId, KnightSounds.tired())
        val weight = section.int(Names.SkillConfig.WEIGHT, 1)
        if (head.isEmpty() && pool.isEmpty()) {
            plugin.logger.warning("[knight] skills.$id の steps と chain-pool が空です")
            return strike(SLASH, weightOverride = 0)
        }
        if (pool.isEmpty()) return ChainSkill(id, head + listOfNotNull(finisher), weight)
        val min = section.int(Names.SkillConfig.CHAIN_MIN, 3).coerceAtLeast(1)
        val max = section.int(Names.SkillConfig.CHAIN_MAX, 4).coerceAtLeast(min)
        return RandomChainSkill(id, head, pool, min, max, finisher, weight)
    }

    /** 打ち上げ → 空中連撃（skills.<id>） */
    private fun launch(id: String): BossSkill {
        val s = KnightLaunchSkill.Settings.load(skillSection(id))
        return KnightLaunchSkill(id, s, KnightSounds.launch(s.hitWindow.first))
    }

    /** 溜め刺し → 振り回して投げる（skills.<id>） */
    private fun impale(id: String): BossSkill {
        val s = KnightImpaleSkill.Settings.load(skillSection(id))
        return KnightImpaleSkill(id, s, KnightSounds.impaleCharge(s.thrustTick))
    }

    /** 怨霊（skills.<id>。離れているときだけ） */
    private fun wraith(id: String): BossSkill = KnightWraithSkill(id, KnightWraithSkill.Settings.load(skillSection(id)))

    /** 雷（skills.<id>。離れているときだけ） */
    private fun lightning(id: String): BossSkill = KnightLightningSkill(id, KnightLightningSkill.Settings.load(skillSection(id)))

    private fun leap(id: String): KnightLeapSkill {
        val section = skillSection(id)
        val s = KnightLeapSkill.Settings.load(section)
        return KnightLeapSkill(id, s, KnightSounds.leap(s.jumpStartTicks, s.landTicks)) { boss, at, forward ->
            val k = boss as KnightBoss
            if (section.contains(Names.SkillConfig.CRACK_LENGTH)) k.cracks(section, at, forward)
        }
    }

    /** 本気の跳躍斬り: 2回続けて跳ぶ（2回目は1回目の着地から。近くても跳ぶ） */
    private fun leapTwice(id: String): BossSkill {
        val first = leap(id)
        val section = skillSection(id)
        val s = KnightLeapSkill.Settings.load(section)
        val second = KnightLeapSkill(id, s.copy(minRange = 0.0), KnightSounds.leap(s.jumpStartTicks, s.landTicks)) { boss, at, forward ->
            if (section.contains(Names.SkillConfig.CRACK_LENGTH)) (boss as KnightBoss).cracks(section, at, forward)
        }
        return ChainSkill(id, listOf(first, second), s.weight)
    }

    // ===== 技の追加の効果 =====

    /** 大剣を叩きつけた所（前方 forward ブロック）から地割れを走らせる */
    fun slamGround(section: ConfigSection) {
        val forward = Facing.forward(entity)
        val at = entity.location.add(forward.clone().multiply(section.double(Names.SkillConfig.FORWARD, 2.6)))
        KnightFx.groundBurst(at, 1.6)
        cracks(section, at, forward)
    }

    /** at から地割れを crack-count 本、forward を中心に crack-spread-degrees の範囲に広げて走らせる */
    fun cracks(section: ConfigSection, at: Location, forward: Vector) {
        val count = section.int(Names.SkillConfig.CRACK_COUNT, 1).coerceAtLeast(1)
        val spread = section.double(Names.SkillConfig.CRACK_SPREAD_DEGREES, 0.0)
        val length = section.double(Names.SkillConfig.CRACK_LENGTH, 8.0)
        val width = section.double(Names.SkillConfig.CRACK_WIDTH, 1.4)
        val stepTicks = section.int(Names.SkillConfig.CRACK_STEP_TICKS, 1)
        val damage = section.double(Names.SkillConfig.CRACK_DAMAGE, 8.0)
        for (i in 0 until count) {
            val angle = if (count == 1) 0.0 else -spread / 2 + spread * i / (count - 1)
            val dir = forward.clone().rotateAroundY(Math.toRadians(angle))
            hazards.crack(at, dir, length, width, stepTicks, damage)
        }
    }

    /** 振り抜いた大剣から、前へ深紅の斬撃波を放つ */
    fun releaseCrescent(section: ConfigSection) {
        val forward = Facing.forward(entity)
        val from = entity.location.add(0.0, 1.0, 0.0).add(forward.clone().multiply(1.4))
        hazards.crescent(
            from, forward,
            speed = section.double(Names.SkillConfig.CRESCENT_SPEED, 0.9),
            range = section.double(Names.SkillConfig.CRESCENT_RANGE, 14.0),
            width = section.double(Names.SkillConfig.CRESCENT_WIDTH, 5.0),
            damage = section.double(Names.SkillConfig.CRESCENT_DAMAGE, 8.0),
        )
    }

    // ===== 召喚・毎tick =====

    override fun onSpawn() {
        setMoveSpeed(moveSpeed)
        applyLook()
        queueSkill(perform(SPAWN_INTRO, KnightSounds.spawn()) { boss, tick ->
            if (tick < 40 && tick % 2 == 0) KnightFx.rise(boss.entity.location, tick / 40.0)
        })
    }

    override fun onTick() {
        age++
        // 召喚直後はモデルの表示がまだ届いていないことがあるので、少し後にもう一度合わせる
        if (age == 5 || age == 40) applyLook()
        hazards.tick()
        if (isRaging && age % 3 == 0) KnightFx.rageAura(entity)
        if (inLastStand) {
            // 回復しても 1 のまま。技の途中で耐えても、次の tick には今の技を止めて瀕死に入る
            if (!entity.isDead && entity.health > LAST_HEALTH) entity.health = LAST_HEALTH
            if (!isDying && currentSkill !== lastStand) interruptWith(lastStand)
            return
        }
        footsteps()
        circleAfterSkill()

        val by = breakBy
        if (by != null) {
            breakBy = null
            val running = currentSkill?.id
            if (!isDying && (running == null || running !in UNBREAKABLE)) {
                KnightSounds.guardBroken(entity)
                by.sendActionBar(guardBreakMessage)
                interruptWith(perform(STAGGER, KnightSounds.stagger()))
            }
        }
    }

    /**
     * 技が終わったら、circle-chance の割合で相手の周りを回って間合いを測る（すり足・横ステップ）。
     * 相手が遠すぎる・近すぎるときや、次の技が決まっているときは回らない
     */
    private fun circleAfterSkill() {
        val running = currentSkill
        val ended = lastSkill
        lastSkill = running
        if (running != null || ended == null || ended.id in NO_CIRCLE_AFTER || hasQueuedSkill || isDying) return
        if (Random.nextDouble() >= circleChance || !circle.canStart(this)) return
        queueSkill(circle)
    }

    override fun onPhaseChange(from: BossPhase?, to: BossPhase) {
        if (from == null || to.id != RAGE || inLastStand) return
        val section = skillSection(RAGE_SHIFT)
        val changeTick = section.int(Names.SkillConfig.CHANGE_TICK, 24)
        val pushRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 6.0)
        val push = section.double(Names.SkillConfig.KNOCKBACK, 1.2)
        queueSkill(perform(RAGE_SHIFT, KnightSounds.rageShift(changeTick)) { boss, tick ->
            val k = boss as KnightBoss
            if (tick < changeTick && tick % 2 == 0) KnightFx.gather(k.entity, tick.toDouble() / changeTick)
            if (tick == changeTick) k.enrage(pushRadius, push)
        })
    }

    /** ジャストガードを数え、guard-break-count 回たまったら次の tick によろめく */
    override fun onJustGuard(player: Player) {
        if (isDying || inLastStand || guardBreakCount <= 0) return
        if (age - lastGuardAge > guardBreakResetTicks) guardCount = 0
        lastGuardAge = age
        guardCount++
        if (guardCount >= guardBreakCount) {
            guardCount = 0
            breakBy = player
        }
    }

    /** プレイヤーを倒したら、首をかしげて剣先を向ける（ほかに決まった技がなければ） */
    override fun onKillPlayer(player: Player) {
        if (isDying || inLastStand || hasQueuedSkill) return
        queueSkill(perform(TAUNT, KnightSounds.taunt()))
    }

    override fun playVanishEffect() {
        KnightFx.crumble(entity, 0.5)
        KnightSounds.crumble(entity)
    }

    // ===== 死亡: 両膝をつき、大剣にすがったまま目の光が消え、灰になって崩れる =====

    override fun onDyingTick(deathAge: Int) {
        if (deathAge == 1) {
            hazards.clear()
            KnightSounds.death(entity)
        }
        if (deathAge == 22) KnightSounds.kneel(entity)
        if (deathAge == 60) KnightSounds.crumble(entity)
        if (deathAge >= 56) KnightFx.crumble(entity, ((deathAge - 56) / 16.0).coerceIn(0.0, 1.0))
    }

    override fun onDispose() {
        hazards.clear()
    }

    // ===== 瀕死 =====

    /**
     * 致命のダメージを HP 1 で耐える。耐えた後は、吹き飛ばしが終わってからの近接の一撃だけが届く
     * （飛び道具・とげ・燃焼・爆発などは全部効かない。遠くでとげが怨霊に反撃して倒してしまう、も起きない）
     */
    override fun interceptDamage(event: EntityDamageEvent): Boolean {
        if (!lastStandEnabled) return false
        if (!inLastStand) {
            if (entity.health - event.finalDamage >= LAST_HEALTH) return false
            beginLastStand()
            return true
        }
        val hit = event as? EntityDamageByEntityEvent
        if (lastStand.exposed && hit?.damager is Player && event.cause in MELEE) {
            // 届いた一撃で必ず倒れる（クールダウン中の弱い一撃でも）
            event.damage = maxOf(event.damage, KILLING_BLOW)
            return false
        }
        // とげの反撃は怨霊を受けるたびに起きるので、知らせない
        if (hit != null && event.cause != DamageCause.THORNS) warded(hit)
        return true
    }

    private fun beginLastStand() {
        inLastStand = true
        breakBy = null
        entity.health = LAST_HEALTH
        isRaging = true
        applyLook()
        val title = Title.title(lastStandTitle, lastStandSubtitle)
        for (p in entity.world.players) {
            if (p.location.distanceSquared(entity.location) <= settings.barRange * settings.barRange) p.showTitle(title)
        }
    }

    /** 効かなかったことを、音と煙と、攻撃した人への知らせで伝える */
    private fun warded(event: EntityDamageByEntityEvent) {
        if (age - lastWardedAge >= WARDED_INTERVAL) {
            lastWardedAge = age
            KnightFx.warded(entity)
            KnightSounds.warded(entity)
        }
        if (!lastStand.exposed) return
        val by = event.damager
        val player = (if (by is Projectile) by.shooter else by) as? Player
        player?.sendActionBar(lastStandBlockedMessage)
    }

    // ===== 大剣・標的 =====

    /** 大剣の柄（ボーンのピボット）と切っ先のワールド座標（ボーンが見つからなければ null） */
    fun bladeSegment(): Pair<Vector, Vector>? {
        val bone = findBone(SWORD_BONE) ?: return null
        val frame = SwordSweep.frameOf(bone, entity.location) ?: return null
        val pivot = frame.position
        val a = frame.pointA()
        val b = frame.pointB()
        // 刃の両端のうち、柄（ピボット）から遠い方が切っ先
        val tip = if (a.distance(pivot) >= b.distance(pivot)) a else b
        return Vector(pivot.x.toDouble(), pivot.y.toDouble(), pivot.z.toDouble()) to
            Vector(tip.x.toDouble(), tip.y.toDouble(), tip.z.toDouble())
    }

    /** 大剣の柄（0）〜 切っ先（1）の間の点 */
    fun bladePoint(f: Double): Location? {
        val (grip, tip) = bladeSegment() ?: return null
        return grip.add(tip.subtract(grip).multiply(f)).toLocation(entity.world)
    }

    /** range 以内の狙える相手（TargetFinder.isTargetable）からランダムに1人（いなければ null） */
    fun randomTarget(range: Double): LivingEntity? =
        entity.getNearbyEntities(range, range, range)
            .filterIsInstance<LivingEntity>()
            .filter { TargetFinder.isTargetable(this, it) && it.location.distanceSquared(entity.location) <= range * range }
            .randomOrNull()

    // ===== 見た目・動き =====

    /** 本気になる: 目が赤く、大剣が赤熱し、周りを吹き飛ばす。歩きも速くなる */
    fun enrage(pushRadius: Double, push: Double) {
        isRaging = true
        applyLook()
        setMoveSpeed(rageMoveSpeed)
        KnightFx.rageBurst(entity, pushRadius)
        val at = entity.location
        for (e in entity.getNearbyEntities(pushRadius, 3.0, pushRadius)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(this, e)) continue
            val away = e.location.toVector().subtract(at.toVector()).setY(0)
            if (away.lengthSquared() < 1e-4) continue
            e.velocity = away.normalize().multiply(push).setY(0.45)
        }
    }

    /** 目の色と、刃の赤熱を今の状態に合わせる */
    private fun applyLook() {
        tintBones(if (isRaging) rageEyeColor else eyeColor, EYES)
        setPartVisible(EDGE, isRaging)
    }

    private fun setMoveSpeed(speed: Double) {
        if (speed > 0) entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = speed
    }

    /** AI で歩いている間の足音（歩いた距離で鳴らす） */
    private fun footsteps() {
        val now = entity.location
        val last = lastStepAt
        lastStepAt = now
        if (last == null || last.world != now.world || currentSkill != null) return
        val d = Math.hypot(now.x - last.x, now.z - last.z)
        if (d > 1.0) return  // テレポートなど
        stepDistance += d
        if (stepDistance >= STEP_LENGTH) {
            stepDistance = 0.0
            KnightSounds.step(entity)
        }
    }

    private fun perform(id: String, sounds: List<SoundCue>, onTick: (Boss, Int) -> Unit = { _, _ -> }): BossSkill =
        PerformSkill(id, PerformSkill.Settings.load(skillSection(id)), sounds, onTick)

    private fun colorOf(key: String, def: Int): Int {
        val raw = config.string(key).trim().removePrefix("#")
        return raw.toIntOrNull(16) ?: def
    }

    companion object {
        const val NORMAL = "normal"
        const val RAGE = "rage"

        /** 目のボーン（glow_ を除いた名前） */
        const val EYES = "eyes"
        /** 本気の刃の赤熱（glow_edge の glow_ を除いた名前） */
        const val EDGE = "edge"

        const val THRUST_ANIM = "thrust"
        const val SLAM_ANIM = "slam"
        const val CHARGE_ANIM = "charge_slash"

        /** 足音の間隔（歩いた距離。ブロック） */
        private const val STEP_LENGTH = 1.6

        // 技ID（設定ファイルの skills.<ID>）
        const val SLASH = "slash"
        const val SLASH_RAGE = "slash_rage"
        const val CHARGE_SLASH = "charge_slash"
        const val CHARGE_SLASH_RAGE = "charge_slash_rage"
        const val THRUST = "thrust"
        const val THRUST_RAGE = "thrust_rage"
        const val SLAM = "slam"
        const val SLAM_RAGE = "slam_rage"
        const val COMBO = "combo"
        const val COMBO_RAGE = "combo_rage"
        const val LEAP = "leap"
        const val LEAP_RAGE = "leap_rage"
        const val SPAWN_INTRO = "spawn_intro"
        const val RAGE_SHIFT = "rage_shift"
        const val TAUNT = "taunt"
        const val STAGGER = "stagger"
        const val CIRCLE = "circle"
        const val LAST_STAND = "last_stand"
        const val LAUNCH = "launch"
        const val LAUNCH_RAGE = "launch_rage"
        const val IMPALE = "impale"
        const val IMPALE_RAGE = "impale_rage"
        const val WRAITH = "wraith"
        const val WRAITH_RAGE = "wraith_rage"
        const val LIGHTNING = "lightning"
        const val LIGHTNING_RAGE = "lightning_rage"
        /** 大剣のボーン */
        const val SWORD_BONE = "greatsword"

        private const val LAST_HEALTH = 1.0
        /** 瀕死にとどめを刺す一撃のダメージ（防具で減っても 1 より大きければよい） */
        private const val KILLING_BLOW = 20.0
        private const val WARDED_INTERVAL = 6
        private val MELEE = setOf(DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_SWEEP_ATTACK)

        /** これが終わった後は回らない（回り終えた直後・演出の後） */
        private val NO_CIRCLE_AFTER = setOf(CIRCLE, SPAWN_INTRO, RAGE_SHIFT, TAUNT)

        /** 体勢崩しで止めない技（演出） */
        private val UNBREAKABLE = setOf(SPAWN_INTRO, RAGE_SHIFT, STAGGER)
    }
}
