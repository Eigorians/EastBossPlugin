package com.eastcompany.eastsub.eastBossPlugin.bosses.giant

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSpawn
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.giant.skill.GiantComboSkill
import com.eastcompany.eastsub.eastBossPlugin.model.BoneKinematics
import kr.toxicity.model.api.tracker.ModelScaler
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs

/** 大巨人（設定: bosses/giant.yml） */
object GiantType : BossType {
    override val id = "giant"
    override val icon: ItemStack get() = ItemStack.of(Material.MOSSY_COBBLESTONE)
    override fun create(spawn: BossSpawn): Boss = GiantBoss(spawn)
}

/**
 * 大巨人。岩の肌に苔をまとった、見上げるほど大きな巨人。石の大棍棒を引きずって歩く。
 *
 * - **溜めが長く、範囲がとても広い**: どの動きも、地面に範囲の予告が出てから当たる（予告の中を色が満たし切った瞬間）。
 *   範囲の形は円（叩きつけ・踏みつけ）・扇（薙ぎ払い）・帯（大地割り）・投石・大跳躍
 * - **技をつなげてくる**: 技は「動き」（ymlの moves）を並べた連携（[GiantComboSkill]）。1つかわしても次が来る
 * - **積んで登っても逃げられない**: どの動きも上へ hit-height まで届き、それより高い相手には岩を直接投げる
 * - **隙が大きい**: 連携の最後に、棍棒を立てて片膝をつき、肩で息をする（受けるダメージが増える）
 * - 技の合間は、ずしん、ずしんと歩いて近づく（足音で地面が揺れる。向きを変えるのは遅い）
 * - HP が rage-health-ratio 以下で本気（身をかがめて力を溜め、吠えると目と胸の紋様が燃えるように赤くなる）。
 *   連携が長くなり、大地割り（手前から奥へ割れていく帯）が加わる
 * - 倒されると、膝をつき、ゆっくり前へ倒れ込む（地響き）
 *
 * エンティティの AI は使わない（[usesEntityAi] = false）。移動は歩きと技でテレポートして行う。
 */
class GiantBoss(spawn: BossSpawn) : Boss(spawn) {

    override val usesEntityAi: Boolean get() = false
    /** AI を切っていると足が地面に着いているかが更新されないので、着地は自分で合わせる（[settle]） */
    override val canStartSkillInAir: Boolean get() = true
    override val hurtSounds: List<SoundCue> get() = GiantSounds.HURT

    /** 本気になったか（第2フェーズ） */
    var isRaging = false
        private set

    /** 空中にいる動きの最中（[settle] で地面へ落とさない） */
    var floating = false

    // ===== 高い所の相手（extra） =====
    /** これ以上高い相手は「高い所」として扱う */
    val airHeight = config.double(Names.BossConfig.AIR_HEIGHT, 5.0)
    override val targetMaxHeight: Double = config.double(Names.BossConfig.AIR_TARGET_HEIGHT, 80.0)

    /** 高い所にいる相手か */
    fun isHigh(e: Entity): Boolean = e.location.y - entity.location.y >= airHeight

    // ===== 歩き（extra） =====
    private val walkSpeed = config.double(Names.BossConfig.WALK_SPEED, 0.12).coerceAtLeast(0.0)
    private val walkTurnSpeed = config.double(Names.BossConfig.WALK_TURN_SPEED, 4.0).toFloat()
    private val stopDistance = config.double(Names.BossConfig.STOP_DISTANCE, 6.0)
    private val homeRadius = config.double(Names.BossConfig.HOME_RADIUS, 20.0)
    private val eyeColor = colorOf(Names.BossConfig.EYE_COLOR, 0xFFC060)
    private val rageEyeColor = colorOf(Names.BossConfig.RAGE_EYE_COLOR, 0xFF3010)
    private val runeColor = colorOf(Names.BossConfig.RUNE_COLOR, 0x6A4A30)
    private val rageRuneColor = colorOf(Names.BossConfig.RAGE_RUNE_COLOR, 0xFF6A1A)

    // ===== 本気（extra） =====
    /** 本気になると体がこの倍率まで大きくなる（範囲も同じだけ広がる） */
    private val rageSize = config.double(Names.BossConfig.RAGE_SIZE, 1.3).coerceIn(0.5, 3.0)
    /** 本気の動きの時間の倍率（0.8 ＝ワンテンポ速い） */
    private val rageTempo = config.double(Names.BossConfig.RAGE_TEMPO, 0.8).coerceIn(0.3, 2.0)
    private val rageGrowTicks = config.int(Names.BossConfig.RAGE_GROW_TICKS, 30).coerceAtLeast(1)
    /** 大きくなり始めてからの tick（大きくなっていなければ -1） */
    private var growAge = -1
    /** 今の大きさ（1 が元の大きさ） */
    private var size = 1.0

    /** 戦いの中心（召喚地点） */
    lateinit var home: Location
        private set
    private var age = 0
    private var walkTick = 0
    private var baseAnimation: String? = null

    /** 相手ごとの前の tick の位置と、そこから測った速さ（プレイヤーの速さはサーバーで取れないので自分で測る） */
    private val lastPositions = HashMap<UUID, Vector>()
    private val velocities = HashMap<UUID, Vector>()

    // ===== フェーズ・技 =====

    override fun createPhases(): List<BossPhase> {
        val rageRatio = config.double(Names.BossConfig.RAGE_HEALTH_RATIO, 0.5)
        return listOf(
            BossPhase(NORMAL, 1.0, combos(NORMAL_COMBOS, rage = false)),
            BossPhase(RAGE, rageRatio, combos(RAGE_COMBOS, rage = true)),
        )
    }

    /**
     * 連携技を作る。動き（moves.<ID>）が1つも読めない技は飛ばす（警告を出す）。
     * 本気の技は、動きを大きく（rage-size）・速く（rage-tempo）した写しを使う。chain-pool があれば連携の長さがランダム
     */
    private fun combos(ids: List<String>, rage: Boolean): List<BossSkill> = ids.mapNotNull { id ->
        val skill = skillSection(id)
        fun load(key: String) = skill.stringList(key).mapNotNull { moveId ->
            val path = "${Names.BossConfig.MOVES}.$moveId"
            if (!config.contains(path)) {
                plugin.logger.warning("[giant/$id] 動き $moveId が moves にありません")
                null
            } else GiantMove.load(moveId, section(path)).let { if (rage) it.scaled(rageSize, rageTempo) else it }
        }
        val moves = load(Names.SkillConfig.MOVES)
        if (moves.isEmpty()) null else GiantComboSkill(id, GiantComboSkill.Settings.load(skill), moves, load(Names.SkillConfig.CHAIN_POOL))
    }

    // ===== 召喚・毎tick =====

    override fun onSpawn() {
        entity.setAI(false)
        home = entity.location.clone()
        applyLook()
        setBaseAnimation(STAND)
        queueSkill(spawnIntro())
    }

    override fun onTick() {
        age++
        // AI は常に切っておく（ほかの仕組みで戻されても）
        if (entity.hasAI()) entity.setAI(false)
        // 召喚直後はモデルの表示がまだ届いていないことがあるので、少し後にもう一度合わせる
        if (age == 5 || age == 40) applyLook()
        if (!floating) settle()
        trackVelocities()
        if (isRaging && age % 3 == 0) GiantFx.embers(this)
        tickGrow()

        if (currentSkill == null && !hasQueuedSkill) walk() else {
            walkTick = 0
            setBaseAnimation(STAND)
        }
    }

    override fun onPhaseChange(from: BossPhase?, to: BossPhase) {
        if (from == null || to.id != RAGE) return
        // 本気: 身をかがめて力を溜め、change-tick で吠える（目と胸の紋様が赤く燃え、周りを吹き飛ばす）
        val section = skillSection(RAGE_SHIFT)
        val s = PerformSkill.Settings.load(section)
        val changeTick = section.int(Names.SkillConfig.CHANGE_TICK, 28)
        val pushRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 10.0)
        val push = section.double(Names.SkillConfig.KNOCKBACK, 1.6)
        queueSkill(PerformSkill(RAGE_SHIFT, s, GiantSounds.roar(changeTick)) { boss, tick ->
            val g = boss as GiantBoss
            if (tick < changeTick && tick % 3 == 0) {
                val at = g.entity.location.add(0.0, g.entity.height * 0.5, 0.0)
                at.world.spawnParticle(Particle.DUST, at, 6, 1.5, 2.0, 1.5, 0.0, GiantFx.EMBER)
            }
            if (tick == changeTick) g.enrage(pushRadius, push)
        })
    }

    override fun playVanishEffect() {
        val at = entity.location
        GiantFx.groundBurst(at, 4.0)
        at.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0.0, entity.height * 0.5, 0.0), 40, 1.5, entity.height * 0.4, 1.5, 0.02)
        GiantSounds.fallen(at)
    }

    // ===== 死亡: 膝をつき、ゆっくり前へ倒れ込む =====

    override fun onDyingTick(deathAge: Int) {
        if (deathAge == 1) GiantSounds.death(entity)
        if (deathAge == 24) {
            GiantSounds.impact(entity.location)
            GiantFx.groundBurst(entity.location, 3.0)
        }
        if (deathAge == 60) {
            // 前へ倒れ込んだ所に地響き
            val at = entity.location.add(Facing.forward(entity).multiply(entity.height * 0.5))
            at.y = Ground.groundY(at.world, at.x, at.y + 3, at.z, 8)
            GiantSounds.fallen(at)
            GiantFx.groundBurst(at, 6.0)
            GiantFx.shake(this, at, 18.0, 1.5)
        }
    }

    // ===== 登場・本気 =====

    /** 登場: うずくまった岩の姿から立ち上がり、胸を叩いて吠える */
    private fun spawnIntro(): BossSkill {
        val s = PerformSkill.Settings.load(skillSection(SPAWN_INTRO))
        return PerformSkill(SPAWN_INTRO, s, GiantSounds.spawn()) { boss, tick ->
            val g = boss as GiantBoss
            val at = g.entity.location
            if (tick == 0) GiantFx.groundBurst(at, 4.0)
            if (tick in 6..24 && tick % 3 == 0) at.world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 2.0, 0.0), 10, 1.5, 1.5, 1.5, 0.0, Material.MOSSY_COBBLESTONE.createBlockData())
            if (tick == 46) GiantFx.shake(g, at, 16.0)
        }
    }

    /** 本気になる: 目と胸の紋様が燃えるように赤くなり、周りを吹き飛ばす（ダメージなし）。体がみるみる大きくなる */
    fun enrage(pushRadius: Double, push: Double) {
        isRaging = true
        applyLook()
        growAge = 0
        val at = entity.location
        GiantFx.groundBurst(at, pushRadius * 0.6)
        GiantFx.shake(this, at, pushRadius * 1.5, 1.5)
        at.world.spawnParticle(Particle.FLAME, at.clone().add(0.0, entity.height * 0.5, 0.0), 80, 2.0, entity.height * 0.3, 2.0, 0.1)
        for (e in entity.getNearbyEntities(pushRadius, 6.0, pushRadius)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(this, e)) continue
            val away = e.location.toVector().subtract(at.toVector()).setY(0)
            if (away.lengthSquared() < 1e-4) continue
            e.velocity = away.normalize().multiply(push).setY(0.6)
        }
    }

    // ===== 本気で大きくなる =====

    /** rage-grow-ticks かけて、体（モデルと殴れる当たり判定）を rage-size 倍まで大きくする。岩が軋む音と砂ぼこり */
    private fun tickGrow() {
        if (growAge < 0 || growAge > rageGrowTicks) return
        val f = growAge.toDouble() / rageGrowTicks
        val eased = f * f * (3 - 2 * f)
        size = 1.0 + (rageSize - 1.0) * eased
        tracker.scaler(ModelScaler.value((settings.modelScale * size).toFloat()))
        entity.getAttribute(Attribute.SCALE)?.baseValue = settings.entityScale * size
        if (growAge % 5 == 0) {
            val at = entity.location
            at.world.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 2.5f, 0.5f)
            at.world.playSound(at, Sound.ENTITY_IRON_GOLEM_REPAIR, SoundCategory.HOSTILE, 1.5f, 0.5f)
            GiantFx.groundBurst(at, 2.5 * size)
        }
        growAge++
    }

    // ===== 歩き =====

    /** 足元が空いていたら落とす（AI を切っているので重力が効かない）。地面より上へは動かさない */
    private fun settle() {
        val loc = entity.location
        val ground = Ground.groundY(loc.world, loc.x, loc.y + 0.5, loc.z, 8)
        if (ground < loc.y - 0.05) {
            loc.y = maxOf(ground, loc.y - 0.8)
            entity.teleport(loc)
        }
    }

    /** 標的へ、ゆっくり向きを変えながら歩いて近づく（stopDistance まで）。標的がいなければ召喚地点へ戻る */
    private fun walk() {
        val here = entity.location
        val t = TargetFinder.findNearest(this, settings.searchRange, verticalRange = targetMaxHeight)
        val goal = t?.location ?: home.takeIf { it.world == here.world && it.distanceSquared(here) > 9.0 }
        if (goal == null) {
            setBaseAnimation(STAND)
            return
        }
        val dir = Facing.horizontalDir(here.toVector(), goal.toVector()) ?: return
        // 向きはゆっくり変える
        var diff = (Facing.yawOf(dir) - here.yaw) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val yaw = here.yaw + diff.coerceIn(-walkTurnSpeed, walkTurnSpeed)

        val dist = Math.hypot(goal.x - here.x, goal.z - here.z)
        val far = if (t != null) dist > stopDistance else dist > 3.0
        // 正面を向いてから歩き出す（大きく横を向いている間はその場で向きを変える）
        if (!far || abs(diff) > 50f) {
            Facing.faceYaw(entity, yaw)
            setBaseAnimation(STAND)
            walkTick = 0
            return
        }
        // 召喚地点から離れすぎない
        // 本気では歩幅も速さも増す
        val step = Facing.dirOfYaw(yaw).multiply(if (isRaging) walkSpeed * size / rageTempo else walkSpeed)
        val next = Ground.step(here.clone().add(step))
        if (next == null || (home.world == here.world && next.distance(home) > homeRadius && next.distance(home) > here.distance(home))) {
            Facing.faceYaw(entity, yaw)
            setBaseAnimation(STAND)
            return
        }
        next.yaw = yaw
        next.pitch = 0f
        entity.teleport(next)
        setBaseAnimation(TRUDGE)
        // 歩きのアニメ（1.6秒）で足が着く 8・24tick に足音
        if (walkTick % 16 == 8) {
            GiantFx.footstep(this, next)
            GiantFx.shake(this, next, 5.0, 0.3)
        }
        walkTick++
    }

    // ===== 標的の速さ =====

    /** 近くの相手の速さを、前の tick からの移動で測る（岩を投げる先を見越すのに使う） */
    private fun trackVelocities() {
        val seen = HashSet<UUID>()
        for (e in entity.getNearbyEntities(48.0, targetMaxHeight, 48.0)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(this, e)) continue
            val now = e.location.toVector()
            val last = lastPositions.put(e.uniqueId, now)
            seen += e.uniqueId
            if (last == null) continue
            val v = now.clone().subtract(last).setY(0)
            if (v.lengthSquared() > 4.0) continue
            velocities[e.uniqueId] = (velocities[e.uniqueId] ?: Vector()).multiply(0.6).add(v.multiply(0.4))
        }
        lastPositions.keys.retainAll(seen)
        velocities.keys.retainAll(seen)
    }

    /** 相手の速さ（ブロック/tick。水平だけ） */
    fun velocityOf(e: LivingEntity): Vector = velocities[e.uniqueId]?.clone() ?: Vector()

    // ===== 見た目 =====

    /** 目と胸の紋様の色、投げる岩は隠す */
    private fun applyLook() {
        tintBones(if (isRaging) rageEyeColor else eyeColor, EYES)
        tintBones(if (isRaging) rageRuneColor else runeColor, RUNES)
        setPartVisible(BOULDER_BONE, false)
    }

    /** 下地の繰り返しのアニメ（立つ / 歩く）を切り替える */
    private fun setBaseAnimation(name: String) {
        if (baseAnimation == name) return
        baseAnimation?.let { stopAnimation(it) }
        baseAnimation = name
        playLoop(name, lerpTicks = 5)
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

        /** 投げる岩のボーン（左手） */
        const val BOULDER_BONE = "boulder"
        /** 目と胸の紋様（glow_ を除いた名前） */
        const val EYES = "eyes"
        const val RUNES = "runes"

        // 繰り返しのアニメ
        const val STAND = "stand"
        const val TRUDGE = "trudge"

        // 技ID（設定ファイルの skills.<ID>）
        const val SPAWN_INTRO = "spawn_intro"
        const val RAGE_SHIFT = "rage_shift"
        val NORMAL_COMBOS = listOf("smash_combo", "sweep_combo", "stomp_combo", "boulder", "leap_slam")
        val RAGE_COMBOS = listOf(
            "smash_combo_rage", "sweep_combo_rage", "stomp_combo_rage", "boulder_rage", "leap_slam_rage", "crack_combo",
        )
    }
}
