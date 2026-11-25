package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSpawn
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill.DiveSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill.FireballSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill.FlameBreathSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill.InfernoRainSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill.KnockdownSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill.TailSwipeSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill.WingGustSkill
import com.eastcompany.eastsub.eastBossPlugin.model.BoneKinematics
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Ghast
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 焔竜（設定: bosses/dragon.yml） */
object DragonType : BossType {
    override val id = "dragon"
    override val icon: ItemStack get() = ItemStack.of(Material.DRAGON_HEAD)

    /** 横に長い体なので、立方体の当たり判定を持つガストを本体にする（AI と重力は切る） */
    override val entityClass: Class<out Mob> get() = Ghast::class.java
    override fun create(spawn: BossSpawn): Boss = DragonBoss(spawn)
}

/**
 * 焔竜。紅いうろこに覆われた、体の中に炎を宿すドラゴン。**一度も地面に降りず、常に飛んでいる。**
 *
 * - 技の合間は、標的のまわりを高く旋回する（cruise-height）。この間は弓などの飛び道具でしか届かない
 * - 技は低く降りてくるものが多く、そのときが殴りどき:
 *   尻尾の薙ぎ払い（低く浮いて一回転。真下にもぐり込めば当たらない）/ 急降下（着地の後に低く浮いて立て直す）/
 *   ブレス（低めに浮いて前方を焼き払う。腹の下は安全）
 * - 高い所から: 火球（着く所に予告の輪。燃える地面が残る）/ 翼の突風（真下に来た相手を吹き飛ばす）
 * - 撃ち落とし: 高い所にいる間に受けたダメージが最大 HP の knockdown-ratio たまると、体勢を崩して落ち、
 *   しばらく低く浮いたまま動けない（弓で狙う意味を作る）
 * - HP が rage-health-ratio 以下で本気（翼で体を包んで力を溜め、吠えて周りを吹き飛ばす。目と体の炎が赤くなり、
 *   翼から火の粉が散る）。技が強くなり、業火の雨（高く昇って火球を降らせ続け、その後は疲れ果てる）が加わる
 * - 倒されると、空中で体の中の炎に焼かれて灰になって崩れる
 *
 * エンティティの AI は使わない（[usesEntityAi] = false）。移動はすべて [flight] で行う。
 */
class DragonBoss(spawn: BossSpawn) : Boss(spawn) {

    override val usesEntityAi: Boolean get() = false
    override val canStartSkillInAir: Boolean get() = true
    override val hurtSounds: List<SoundCue> get() = DragonSounds.HURT

    val flight = DragonFlight(entity)
    val fireField = DragonFireField(this)
    val fireballs = DragonFireballs(this, fireField)

    /** 本気になったか（第2フェーズ） */
    var isRaging = false
        private set

    // ===== 飛び回り方（extra） =====
    private val cruiseHeight = config.double(Names.BossConfig.CRUISE_HEIGHT, 7.0)
    private val cruiseSpeed = config.double(Names.BossConfig.CRUISE_SPEED, 0.55).coerceAtLeast(0.05)
    private val orbitRadius = config.double(Names.BossConfig.ORBIT_RADIUS, 11.0).coerceAtLeast(2.0)
    private val homeRadius = config.double(Names.BossConfig.HOME_RADIUS, 24.0).coerceAtLeast(4.0)
    private val spawnHeight = config.double(Names.BossConfig.SPAWN_HEIGHT, 22.0)

    /** 戦いの中心（召喚地点） */
    lateinit var home: Location
        private set
    private var orbitAngle = 0.0
    private var orbitDir = 1.0
    /** 前の tick も旋回していたか（技の後に旋回を始めるとき、今の位置から回り始める） */
    private var cruising = false

    // ===== 撃ち落とし =====
    private val knockdownRatio = config.double(Names.BossConfig.KNOCKDOWN_RATIO, 0.04)
    private val knockdownMinHeight = config.double(Names.BossConfig.KNOCKDOWN_MIN_HEIGHT, 4.5)
    private val knockdownCooldown = config.int(Names.BossConfig.KNOCKDOWN_COOLDOWN_TICKS, 400)
    private var knockGauge = 0.0
    private var knockIdle = 0
    private var knockCooldownLeft = 0
    private var knockRequested = false

    private val rageEyeColor = colorOf(Names.BossConfig.RAGE_EYE_COLOR, 0xFF3020)
    private val rageCoreColor = colorOf(Names.BossConfig.RAGE_CORE_COLOR, 0xFF5A3C)

    // ===== チャーミングな振る舞い（extra） =====
    private val sneezeMin = config.int(Names.BossConfig.SNEEZE_MIN_TICKS, 300).coerceAtLeast(20)
    private val sneezeMax = config.int(Names.BossConfig.SNEEZE_MAX_TICKS, 600).coerceAtLeast(sneezeMin)
    /** 次のくしゃみまで（旋回している間だけ減る） */
    private var sneezeIn = Random.nextInt(sneezeMin, sneezeMax + 1)
    /** くしゃみの「へっくしゅん」の瞬間（age。無ければ -1） */
    private var sneezeAt = -1
    /** 相手を倒して喜ぶのを、技が終わるまで待っている */
    private var happyPending = false

    /** 下地として流している繰り返しのアニメ（hover / fly） */
    private var baseAnimation: String? = null
    private var age = 0
    private var lastFlapPhase = 0

    // ===== フェーズ・技 =====

    override fun createPhases(): List<BossPhase> {
        val rageRatio = config.double(Names.BossConfig.RAGE_HEALTH_RATIO, 0.5)
        return listOf(
            BossPhase(NORMAL, 1.0, listOf(
                breath(BREATH), dive(DIVE), fireball(FIREBALL), tailSwipe(TAIL_SWIPE), gust(WING_GUST),
            )),
            // 本気: 強化版の技と、業火の雨
            BossPhase(RAGE, rageRatio, listOf(
                breath(BREATH_RAGE), dive(DIVE_RAGE), fireball(FIREBALL_RAGE), tailSwipe(TAIL_SWIPE_RAGE), gust(WING_GUST_RAGE),
                InfernoRainSkill(INFERNO_RAIN, InfernoRainSkill.Settings.load(skillSection(INFERNO_RAIN))),
            )),
        )
    }

    private fun breath(id: String) = FlameBreathSkill(id, FlameBreathSkill.Settings.load(skillSection(id)))
    private fun dive(id: String) = DiveSkill(id, DiveSkill.Settings.load(skillSection(id)))
    private fun fireball(id: String) = FireballSkill(id, FireballSkill.Settings.load(skillSection(id)))
    private fun tailSwipe(id: String) = TailSwipeSkill(id, TailSwipeSkill.Settings.load(skillSection(id)))
    private fun gust(id: String) = WingGustSkill(id, WingGustSkill.Settings.load(skillSection(id)))

    // ===== 召喚・毎tick =====

    override fun onSpawn() {
        entity.setAI(false)
        entity.setGravity(false)
        home = entity.location.clone()
        flight.yaw = entity.location.yaw

        // 空の上から降りてくる（天井があれば、ぶつからない高さまで）
        val start = entity.location.clone()
        var up = 0.0
        while (up < spawnHeight && !entity.collidesAt(start.clone().add(0.0, up + 1.0, 0.0))) up += 1.0
        flight.warp(start.add(0.0, up, 0.0))

        applyParts()
        setBaseAnimation(HOVER)
        queueSkill(spawnIntro())
    }

    override fun onTick() {
        age++
        // AI と重力は常に切っておく（ほかの仕組みで戻されても）
        if (entity.hasAI()) entity.setAI(false)
        if (entity.hasGravity()) entity.setGravity(false)
        entity.fallDistance = 0f
        // 召喚直後はモデルの表示がまだ届いていないことがあるので、少し後にもう一度合わせる
        if (age == 5 || age == 40) applyParts()

        fireballs.tick()
        fireField.tick()
        tickKnockdown()
        ambience()

        if (currentSkill == null && !hasQueuedSkill) cruise() else cruising = false
        charm()
    }

    // ===== チャーミングな振る舞い =====

    /**
     * 旋回中にときどきくしゃみをする（鼻から煙と小さな火がぽふっ。害は無い）。
     * 相手を倒すと空中でくるっと回って大喜び。撃ち落とされている間は頭の上に星が回る
     */
    private fun charm() {
        if (isDying) return
        if (age == sneezeAt) sneezePuff()
        if (currentSkill?.id == KNOCKDOWN && age % 3 == 0) dizzyStars()
        if (!cruising) return
        if (happyPending) {
            happyPending = false
            beHappy()
            return
        }
        if (--sneezeIn <= 0) {
            sneezeIn = Random.nextInt(sneezeMin, sneezeMax + 1)
            playOnce(SNEEZE)
            sneezeAt = age + SNEEZE_TICK
            mouthPoint()?.let { DragonSounds.play(it, Sound.ENTITY_PANDA_PRE_SNEEZE, 2.0f, 0.5f) }
        }
    }

    /** へっくしゅん: 鼻から煙の輪と小さな火（害は無い） */
    private fun sneezePuff() {
        val at = mouthOrFront()
        val world = at.world
        world.spawnParticle(Particle.CLOUD, at, 14, 0.4, 0.3, 0.4, 0.06)
        world.spawnParticle(Particle.FLAME, at, 10, 0.3, 0.2, 0.3, 0.05)
        world.spawnParticle(Particle.LARGE_SMOKE, at, 6, 0.3, 0.2, 0.3, 0.03)
        DragonSounds.play(at, Sound.ENTITY_PANDA_SNEEZE, 3.0f, 0.5f)
        DragonSounds.play(at, Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.6f)
    }

    /** 相手を倒して大喜び: くるっと回って尻尾をぶんぶん。ハートが飛ぶ */
    private fun beHappy() {
        playOnce(HAPPY)
        val at = entity.location.add(0.0, entity.height * 0.8, 0.0)
        at.world.spawnParticle(Particle.HEART, at, 8, 1.5, 0.8, 1.5, 0.0)
        DragonSounds.play(at, Sound.ENTITY_PANDA_AMBIENT, 3.0f, 0.6f)
        DragonSounds.play(at, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 1.0f, 1.8f)
    }

    /** 撃ち落とされて目を回している: 頭の上を星が回る */
    private fun dizzyStars() {
        val head = bonePoint(HEAD) ?: entity.location.add(0.0, entity.height, 0.0)
        val c = head.add(0.0, 1.4, 0.0)
        for (k in 0 until 3) {
            val a = age * 0.35 + k * Math.PI * 2 / 3
            c.world.spawnParticle(Particle.CRIT, c.clone().add(Math.cos(a) * 1.0, 0.0, Math.sin(a) * 1.0), 1, 0.0, 0.0, 0.0, 0.0)
        }
        if (age % 30 == 0) DragonSounds.play(c, Sound.ENTITY_PANDA_WORRIED_AMBIENT, 1.5f, 0.6f)
    }

    /** 相手を倒したら、技が終わってから喜ぶ */
    override fun onKillPlayer(player: Player) {
        happyPending = true
    }

    override fun onPhaseChange(from: BossPhase?, to: BossPhase) {
        if (from == null || to.id != RAGE) return
        // 本気モード: 翼で体を包んで力を溜め、change-tick で翼を広げて吠える（目と体の炎が赤くなる）
        val section = skillSection(RAGE_SHIFT)
        val s = PerformSkill.Settings.load(section)
        val changeTick = section.int(Names.SkillConfig.CHANGE_TICK, 28)
        val pushRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 9.0)
        val push = section.double(Names.SkillConfig.KNOCKBACK, 1.5)
        queueSkill(PerformSkill(RAGE_SHIFT, s, DragonSounds.rageShift(s, changeTick)) { boss, tick ->
            val d = boss as DragonBoss
            if (tick == 0) d.setBaseAnimation(HOVER)
            d.flight.brake()
            if (tick < changeTick && tick % 2 == 0) d.gatherEffect(tick.toDouble() / changeTick)
            if (tick == changeTick) d.enrage(pushRadius, push)
        })
    }

    override fun modifyIncomingDamage(event: EntityDamageByEntityEvent) {
        super.modifyIncomingDamage(event)
        chargeKnockdown(event.damage)
    }

    override fun playVanishEffect() {
        val at = entity.location.add(0.0, entity.height * 0.5, 0.0)
        val world = entity.world
        world.spawnParticle(Particle.LARGE_SMOKE, at, 60, 2.0, 1.0, 2.0, 0.05)
        world.spawnParticle(Particle.FLAME, at, 40, 2.0, 1.0, 2.0, 0.05)
        DragonSounds.flap(entity, heavy = true)
        DragonSounds.growl(entity, 1.1f)
    }

    // ===== 死亡: 空中で体の中の炎に焼かれ、灰になって崩れる =====

    override fun onDyingTick(deathAge: Int) {
        val at = entity.location.add(0.0, entity.height * 0.5, 0.0)
        val world = at.world
        if (deathAge == 1) {
            DragonSounds.death(entity)
            tintBones(0xFFF0C0, *CORE_PARTS)
        }
        // だんだん激しく燃える
        val heat = (deathAge / 40.0).coerceIn(0.1, 1.0)
        world.spawnParticle(Particle.FLAME, at, (12 * heat).toInt() + 2, 2.2, 1.2, 2.2, 0.03 + 0.05 * heat)
        world.spawnParticle(Particle.LARGE_SMOKE, at, (6 * heat).toInt() + 1, 2.0, 1.0, 2.0, 0.02)
        if (deathAge % 3 == 0) world.spawnParticle(Particle.LAVA, at, 3, 1.5, 0.8, 1.5, 0.0)
        if (deathAge > 20) world.spawnParticle(Particle.DUST, at, 10, 2.5, 1.2, 2.5, 0.0, DragonFx.ASH)
        if (deathAge == 38) {
            DragonSounds.burnOut(at)
            world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1)
            world.spawnParticle(Particle.FLAME, at, 120, 2.5, 1.5, 2.5, 0.25)
            world.spawnParticle(Particle.WHITE_ASH, at, 200, 3.0, 2.0, 3.0, 0.02)
        }
        if (deathAge > 38) world.spawnParticle(Particle.WHITE_ASH, at.clone().add(0.0, -1.0, 0.0), 20, 3.0, 1.0, 3.0, 0.0)
    }

    override fun onDispose() {
        fireballs.clear()
        fireField.clear()
    }

    // ===== 技の合間: 標的のまわりを高く旋回する =====

    private fun cruise() {
        val focus = TargetFinder.findNearest(this, settings.searchRange)?.location ?: home
        val center = clampToHome(focus)
        if (!cruising) {
            // 今いる所から回り始める（技の後に反対側へ横切らない）
            val pos = entity.location
            orbitAngle = atan2(pos.z - center.z, pos.x - center.x)
            if (Random.nextInt(3) == 0) orbitDir = -orbitDir
            cruising = true
        }
        orbitAngle += orbitDir * cruiseSpeed * 0.85 / orbitRadius
        val px = center.x + cos(orbitAngle) * orbitRadius
        val pz = center.z + sin(orbitAngle) * orbitRadius
        val dest = flight.above(px, pz, cruiseHeight, maxOf(center.y, entity.location.y - cruiseHeight))
        flight.turnToVelocity(6f)
        flight.steer(dest, cruiseSpeed, accel = 0.05, arriveRadius = 3.0)
        setBaseAnimation(if (flight.horizontalSpeed > 0.28) FLY else HOVER)
    }

    /** 召喚地点から homeRadius より遠い点は、召喚地点の方へ寄せる（逃げた相手を追って遠くへ行かない） */
    fun clampToHome(at: Location): Location {
        if (at.world != home.world) return home
        val v = Vector(at.x - home.x, 0.0, at.z - home.z)
        val dist = v.length()
        if (dist <= homeRadius) return at
        return home.clone().add(v.multiply(homeRadius / dist)).also { it.y = at.y }
    }

    // ===== アニメ・見た目 =====

    /** 下地の繰り返しアニメ（hover / fly）を切り替える。技の once アニメが終わると、これに戻る */
    fun setBaseAnimation(name: String) {
        if (baseAnimation == name) return
        baseAnimation?.let { stopAnimation(it) }
        playLoop(name, lerpTicks = 5)
        baseAnimation = name
    }

    /** 表示を切り替える部品・色を合わせる */
    private fun applyParts() {
        setPartVisible(THROAT, false)
        setPartVisible(MAW, false)
        if (isRaging) {
            tintBones(rageEyeColor, *EYES)
            tintBones(rageCoreColor, *CORE_PARTS)
        }
    }

    /** ブレスや火球の前に、のどと口の中の炎を見せる */
    fun showFire(visible: Boolean) {
        setPartVisible(THROAT, visible)
        setPartVisible(MAW, visible)
    }

    /** 待機中の演出: 鼻から煙、羽ばたきの音、本気なら翼から火の粉 */
    private fun ambience() {
        if (isDying) return
        val anim = baseAnimation
        // 羽ばたきの音（下地のアニメの周期に合わせて、だいたい打ち下ろしのとき）
        val period = if (anim == FLY) 16 else 24
        val phase = age / period
        if (phase != lastFlapPhase) {
            lastFlapPhase = phase
            if (currentSkill == null) DragonSounds.flap(entity)
        }
        if (age % 30 == 0) mouthPoint()?.let { DragonFx.nostrilSmoke(it, heavy = false) }
        if (isRaging && age % 2 == 0) rageAura()
    }

    private fun rageAura() {
        val world = entity.world
        for (name in RAGE_EMBER_BONES) {
            val p = bonePoint(name) ?: continue
            world.spawnParticle(Particle.FLAME, p, 1, 0.3, 0.1, 0.3, 0.01)
            if (Random.nextInt(3) == 0) world.spawnParticle(Particle.DUST, p, 1, 0.4, 0.2, 0.4, 0.0, DragonFx.EMBER)
        }
        if (age % 6 == 0) {
            val body = entity.location.add(0.0, entity.height * 0.4, 0.0)
            world.spawnParticle(Particle.LAVA, body, 1, 1.2, 0.4, 1.2, 0.0)
        }
    }

    /** 本気の溜め: 周りから炎が体へ吸い込まれる */
    private fun gatherEffect(progress: Double) {
        val center = entity.location.add(0.0, entity.height * 0.5, 0.0)
        val world = center.world
        val r = 9.0 * (1 - progress) + 1.5
        for (i in 0 until 12) {
            val a = Math.PI * 2 * i / 12 + progress * 5
            val p = center.clone().add(cos(a) * r, (i % 3 - 1) * 1.2, sin(a) * r)
            val toward = center.toVector().subtract(p.toVector()).normalize()
            world.spawnParticle(Particle.FLAME, p, 0, toward.x, toward.y, toward.z, 0.25)
        }
        world.spawnParticle(Particle.SMALL_FLAME, center, 6, 1.5, 0.8, 1.5, 0.02)
    }

    /** 本気になる: 目と体の炎が赤くなり、周りの相手を吹き飛ばす（ダメージはない） */
    private fun enrage(pushRadius: Double, push: Double) {
        isRaging = true
        tintBones(rageEyeColor, *EYES)
        tintBones(rageCoreColor, *CORE_PARTS)
        val world = entity.world
        val at = entity.location.add(0.0, entity.height * 0.5, 0.0)
        world.spawnParticle(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(255, 120, 40))
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1)
        world.spawnParticle(Particle.FLAME, at, 160, 1.5, 1.0, 1.5, 0.35)
        world.spawnParticle(Particle.LAVA, at, 30, 2.0, 1.0, 2.0, 0.0)
        // 地面にも炎の輪が走る
        val ground = flight.above(at.x, at.z, 0.0)
        val groundLoc = Location(world, ground.x, ground.y, ground.z)
        for (ring in 1..3) DragonFx.ring(groundLoc, pushRadius * ring / 3, DragonFx.EMBER)
        DragonFx.groundShock(groundLoc, pushRadius * 0.6)

        for (e in world.getNearbyEntities(at, pushRadius, pushRadius, pushRadius)) {
            if (!TargetFinder.isValidTarget(this, e)) continue
            val away = e.location.toVector().subtract(entity.location.toVector()).setY(0)
            if (away.lengthSquared() < 1e-4) continue
            e.velocity = away.normalize().multiply(push).setY(0.7)
        }
    }

    // ===== 撃ち落とし =====

    /** 高い所にいる間に受けたダメージをためる。上限に届いたら、次の tick に撃ち落とされる */
    private fun chargeKnockdown(damage: Double) {
        if (isDying || knockCooldownLeft > 0 || knockdownRatio <= 0) return
        if (currentSkill?.id in NO_KNOCKDOWN || hasQueuedSkill) return
        if (flight.altitude() < knockdownMinHeight) return
        knockGauge += damage
        knockIdle = 0
        if (knockGauge >= maxHealth() * knockdownRatio) knockRequested = true
    }

    /**
     * 撃ち落としを、ダメージとは別に fraction（撃ち落としに必要な量に対する割合）だけためる（跳ね返った火球など）。
     * 高さは問わない
     */
    fun addKnockdown(fraction: Double) {
        if (isDying || knockCooldownLeft > 0 || knockdownRatio <= 0 || fraction <= 0) return
        if (currentSkill?.id in NO_KNOCKDOWN || hasQueuedSkill) return
        knockGauge += maxHealth() * knockdownRatio * fraction
        knockIdle = 0
        if (knockGauge >= maxHealth() * knockdownRatio) knockRequested = true
    }

    /** 弾き返された火球が当たった: 弾き返した人からのダメージと、撃ち落としのたまり */
    fun onFireballReflected(player: Player, spec: DragonFireballs.Spec) {
        if (isDying) return
        addKnockdown(spec.reflectKnockdown)
        entity.noDamageTicks = 0
        entity.damage(spec.reflectDamage, player)
        val at = entity.location.add(0.0, entity.height * 0.5, 0.0)
        at.world.spawnParticle(Particle.LAVA, at, 12, 1.0, 0.6, 1.0, 0.0)
        at.world.playSound(at, Sound.ENTITY_ENDER_DRAGON_HURT, SoundCategory.HOSTILE, 2.0f, 1.2f)
        // あちちっ: びっくりして首をぶるぶる振る。頭から煙がぷすぷす
        playOnce(OOPS)
        at.world.playSound(at, Sound.ENTITY_PANDA_WORRIED_AMBIENT, SoundCategory.HOSTILE, 2.5f, 0.6f)
        (bonePoint(HEAD) ?: at).let { it.world.spawnParticle(Particle.LARGE_SMOKE, it.add(0.0, 1.0, 0.0), 10, 0.4, 0.3, 0.4, 0.04) }
    }

    private fun tickKnockdown() {
        if (knockCooldownLeft > 0) knockCooldownLeft--
        knockIdle++
        // しばらく撃たれずにいると、ためた量が減っていく（10秒で上限ぶん）
        if (knockIdle > 60 && knockGauge > 0) knockGauge = (knockGauge - maxHealth() * knockdownRatio / 200).coerceAtLeast(0.0)
        if (!knockRequested) return
        knockRequested = false
        knockGauge = 0.0
        if (isDying || currentSkill?.id in NO_KNOCKDOWN) return
        knockCooldownLeft = knockdownCooldown
        val s = KnockdownSkill.Settings.load(skillSection(KNOCKDOWN))
        interruptWith(KnockdownSkill(KNOCKDOWN, s))
        announce(s.message)
    }

    // ===== 技から使う道具 =====

    /** ボーンの位置（ワールド）。見つからなければ null */
    fun bonePoint(name: String): Location? {
        val bone = findBone(name) ?: return null
        val p = BoneKinematics.worldPose(bone, entity.location).position
        return Location(entity.world, p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
    }

    /** 炎の出る所（口の先）。ボーンが見つからなければ null */
    fun mouthPoint(): Location? = bonePoint(MOUTH)

    /** 口の先（見つからなければ頭のあたりの推定） */
    fun mouthOrFront(): Location =
        mouthPoint() ?: entity.location.add(0.0, entity.height * 0.5, 0.0).add(flight.forward().multiply(4.5))

    /** (x, z) の地面の点 */
    fun groundAt(x: Double, z: Double, refY: Double = entity.location.y): Location {
        val v = flight.above(x, z, 0.0, refY)
        return Location(entity.world, v.x, v.y, v.z)
    }

    /**
     * 標的の近く・高さ height の点（標的から見て、今ドラゴンがいる側へ distance 離れた所）。
     * 技を出す位置へ飛んでいくときに使う
     */
    fun hoverPointNear(target: LivingEntity, distance: Double, height: Double): Vector {
        val t = target.location
        val away = Facing.horizontalDir(t.toVector(), entity.location.toVector()) ?: flight.forward().multiply(-1)
        val p = t.clone().add(away.multiply(distance))
        return flight.above(p.x, p.z, height, maxOf(t.y, entity.location.y - height))
    }

    /** 戦っている人（いなければ周りのプレイヤー）にアクションバーで知らせる */
    fun announce(message: Component) {
        val players: List<Player> = battle?.onlineParticipants(plugin.server)
            ?: entity.getNearbyEntities(48.0, 32.0, 48.0).filterIsInstance<Player>()
        players.forEach { it.sendActionBar(message) }
    }

    /** 標的（range 以内で一番近い相手） */
    fun findTarget(range: Double): LivingEntity? = TargetFinder.findNearest(this, range)

    // ===== 登場 =====

    /** 登場: 空から降りてきて、翼を広げて吠える（吠えたとき、周りに熱風が吹く） */
    private fun spawnIntro(): BossSkill {
        val section = skillSection(SPAWN_INTRO)
        val s = PerformSkill.Settings.load(section)
        val roarTick = section.int(Names.SkillConfig.CHANGE_TICK, 40)
        val descendTicks = section.int(Names.SkillConfig.DESCEND_TICKS, 30)
        return PerformSkill(SPAWN_INTRO, s, DragonSounds.spawn(s, roarTick)) { boss, tick ->
            val d = boss as DragonBoss
            if (tick < descendTicks) {
                val dest = d.flight.above(d.home.x, d.home.z, d.cruiseHeight - 1.5, d.home.y)
                d.flight.steer(dest, 0.9, accel = 0.12, arriveRadius = 8.0)
            } else {
                d.flight.brake(0.1)
            }
            d.findTarget(d.settings.searchRange)?.let { d.flight.turnToward(it.location, 4f) }
            if (tick == roarTick) d.roarBlast()
        }
    }

    /** 吠えたときの熱風（ダメージなし） */
    private fun roarBlast() {
        val mouth = mouthOrFront()
        val world = mouth.world
        world.spawnParticle(Particle.FLAME, mouth, 60, 0.4, 0.4, 0.4, 0.2)
        world.spawnParticle(Particle.LARGE_SMOKE, mouth, 20, 0.5, 0.5, 0.5, 0.08)
        val ground = groundAt(entity.location.x, entity.location.z)
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, ground.clone().add(0.0, 0.5, 0.0), 1)
        DragonFx.groundShock(ground, 5.0)
        DragonSounds.play(ground, Sound.ENTITY_BREEZE_WIND_BURST, 3.0f, 0.6f)
    }

    private fun colorOf(key: String, def: Int): Int =
        config.string(key).trim().removePrefix("#").toIntOrNull(16) ?: def

    companion object {
        const val NORMAL = "normal"
        const val RAGE = "rage"

        // 下地のアニメ
        const val HOVER = "hover"
        const val FLY = "fly"

        // ボーン名（glow_ を除いたもの）
        val EYES = arrayOf("eye_r", "eye_l")
        val CORE_PARTS = arrayOf("core", "throat", "maw")
        const val THROAT = "throat"
        const val MAW = "maw"
        const val MOUTH = "mouth"
        const val HEAD = "head"

        // チャーミングな振る舞いのアニメ（下地の上に1回だけ流す）
        const val SNEEZE = "sneeze"
        /** くしゃみのアニメで「へっくしゅん」と顔を振り下ろす tick */
        const val SNEEZE_TICK = 13
        const val HAPPY = "happy"
        const val OOPS = "oops"
        /** 本気のとき火の粉を散らす所 */
        val RAGE_EMBER_BONES = listOf("wing3_r", "wing3_l", "wing2_r", "wing2_l", "tail4", "neck2")

        // 技ID（設定ファイルの skills.<ID>）
        const val SPAWN_INTRO = "spawn_intro"
        const val RAGE_SHIFT = "rage_shift"
        const val KNOCKDOWN = "knockdown"
        const val BREATH = "flame_breath"
        const val DIVE = "dive"
        const val FIREBALL = "fireball"
        const val TAIL_SWIPE = "tail_swipe"
        const val WING_GUST = "wing_gust"
        const val BREATH_RAGE = "flame_breath_rage"
        const val DIVE_RAGE = "dive_rage"
        const val FIREBALL_RAGE = "fireball_rage"
        const val TAIL_SWIPE_RAGE = "tail_swipe_rage"
        const val WING_GUST_RAGE = "wing_gust_rage"
        const val INFERNO_RAIN = "inferno_rain"

        /** 撃ち落とされない技（演出と、撃ち落とされている最中） */
        val NO_KNOCKDOWN = setOf(SPAWN_INTRO, RAGE_SHIFT, KNOCKDOWN)
    }
}
