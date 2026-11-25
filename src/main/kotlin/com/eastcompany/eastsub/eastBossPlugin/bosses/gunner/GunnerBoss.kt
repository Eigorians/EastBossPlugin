package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSpawn
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.BackflipSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.DuelSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.GatlingSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.GrenadeSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.SnipeSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.StrafeFireSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.TwinBurstSkill
import com.eastcompany.eastsub.eastBossPlugin.model.BoneKinematics
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

/** ガンナー（設定: bosses/gunner.yml） */
object GunnerType : BossType {
    override val id = "gunner"
    override val icon: ItemStack get() = ItemStack.of(Material.CROSSBOW)
    override fun create(spawn: BossSpawn): Boss = GunnerBoss(spawn)
}

/**
 * ガンナー。ロングコートに二丁のリボルバー、背中に狙撃銃を背負った賞金稼ぎ。
 *
 * - 遠くからは銃で撃ち、近づかれると蹴り飛ばすかバク宙で離れる
 * - 弾はすべて目に見える速さで飛び、ブロックで止まる（壁の陰に隠れればかわせる）
 * - 武器はモデルのボーンの表示・非表示で持ち替える（二丁拳銃 / 狙撃銃 / ガトリング）
 * - HP が rage-health-ratio 以下で本気になる（バイザーが赤くなり、ガトリングと3連続の狙撃を使う）
 * - HP が final-health-ratio 以下で瀕死になり、決闘（鐘が鳴り終わると早撃ち。物陰も貫き、撃鉄の音でジャストガードしなければ即死）を使う
 * - 拳銃には弾数がある（ammo）。撃ち尽くすと空撃ちの「カチッ」が鳴り、次はリロード（殴りどき）
 * - プレイヤーを倒すと、銃口の煙を吹いて挑発する
 * - 壁を背にしない: 下がるときは開けている方へ逃げ、召喚地点から home-radius 以上離れると戻る方へ寄る。
 *   壁際で近づかれたら（追い込まれたら）、バク宙で開けた方へ抜け出しやすくなる
 */
class GunnerBoss(spawn: BossSpawn) : Boss(spawn) {

    /** 手に持っている武器 */
    enum class Weapon { PISTOLS, RIFLE, GATLING }

    /** 弾の出る所（モデルのボーン） */
    enum class Muzzle(val bone: String) {
        RIGHT("muzzle_r"), LEFT("muzzle_l"), RIFLE("muzzle_rifle"), GATLING("muzzle_gatling")
    }

    /** 撃った弾（技が終わっても飛び続ける） */
    val bullets = BulletField(this)

    var weapon = Weapon.PISTOLS
        private set

    /** 本気になったか（第2フェーズ） */
    var isRaging = false
        private set

    private val normalAmmo = config.int(Names.BossConfig.AMMO, 12).coerceAtLeast(1)
    private val rageAmmo = config.int(Names.BossConfig.RAGE_AMMO, 18).coerceAtLeast(1)

    /** 拳銃の残りの弾（左右合わせて） */
    var ammo = normalAmmo
        private set

    /** 戦いの中心（召喚地点） */
    private var home: Location? = null
    private val homeRadius = config.double(Names.BossConfig.HOME_RADIUS, 10.0).coerceAtLeast(1.0)

    /** 壁を背にして、近くに相手がいるか（追い込まれている。5tick ごとに更新） */
    var isCornered = false
        private set

    private val visorColor = colorOf(Names.BossConfig.VISOR_COLOR, 0x55E6FF)
    private val visorRageColor = colorOf(Names.BossConfig.VISOR_RAGE_COLOR, 0xFF2A2A)
    private var age = 0

    override val hurtSounds: List<SoundCue> get() = GunnerSounds.HURT

    override fun createPhases(): List<BossPhase> {
        val rageRatio = config.double(Names.BossConfig.RAGE_HEALTH_RATIO, 0.5)
        val finalRatio = config.double(Names.BossConfig.FINAL_HEALTH_RATIO, 0.2)
        return listOf(
            BossPhase(NORMAL, 1.0, normalSkills()),
            BossPhase(RAGE, rageRatio, rageSkills()),
            // 瀕死: 本気の技に決闘が加わる
            BossPhase(FINAL, finalRatio, rageSkills() + duel()),
        )
    }

    private fun normalSkills(): List<BossSkill> = listOf(
        twinBurst(TWIN_BURST), strafeFire(STRAFE_FIRE), snipe(SNIPE), grenade(GRENADE), kick(), backflip(),
    )

    /** 本気: 連射が長くなり、狙撃は3連続、ガトリングが加わる */
    private fun rageSkills(): List<BossSkill> = listOf(
        twinBurst(TWIN_BURST_RAGE), strafeFire(STRAFE_FIRE), snipe(SNIPE_RAPID), grenade(GRENADE), kick(), backflip(),
        GatlingSkill.Settings.load(skillSection(GATLING)).let { GatlingSkill(GATLING, it, GunnerSounds.gatling(it)) },
    )

    private fun twinBurst(id: String) =
        TwinBurstSkill.Settings.load(skillSection(id)).let { TwinBurstSkill(id, it, GunnerSounds.twinBurst(it)) }

    private fun strafeFire(id: String) =
        StrafeFireSkill.Settings.load(skillSection(id)).let { StrafeFireSkill(id, it, GunnerSounds.strafeFire(it)) }

    private fun snipe(id: String) =
        SnipeSkill.Settings.load(skillSection(id)).let { SnipeSkill(id, it, GunnerSounds.snipe(it)) }

    private fun grenade(id: String) =
        GrenadeSkill.Settings.load(skillSection(id)).let { GrenadeSkill(id, it, GunnerSounds.grenade(it)) }

    private fun duel(): DuelSkill {
        val s = DuelSkill.Settings.load(skillSection(DUEL))
        val last = s.bellTicks.lastIndex
        val bells = s.bellTicks.flatMapIndexed { i, t -> GunnerSounds.duelBell(i, i == last).map { it.copy(tick = t) } }
        return DuelSkill(DUEL, s, GunnerSounds.duelReady(s.coinTick) + bells)
    }

    /** リロード: 弾倉を振り出して空薬莢を落とし、弾を込めて戻す（closeTick で弾が満タンになる） */
    private fun reload(): BossSkill {
        val section = skillSection(RELOAD)
        val s = PerformSkill.Settings.load(section)
        val eject = section.int(Names.SkillConfig.EJECT_TICK, 6)
        val insert = section.int(Names.SkillConfig.INSERT_TICK, 14)
        val close = section.int(Names.SkillConfig.CLOSE_TICK, 20)
        return PerformSkill(RELOAD, s, GunnerSounds.reload(s, eject, insert, close)) { boss, tick ->
            val g = boss as GunnerBoss
            if (tick == eject) g.ejectCasings()
            if (tick == close) g.refillAmmo()
        }
    }

    /** 挑発: 銃口の煙を吹いて、銃を回し、帽子のつばに触れる */
    private fun taunt(): BossSkill {
        val section = skillSection(TAUNT)
        val s = PerformSkill.Settings.load(section)
        val smoke = section.int(Names.SkillConfig.SMOKE_TICK, 10)
        return PerformSkill(TAUNT, s, GunnerSounds.taunt(s, smoke)) { boss, tick ->
            if (tick in smoke..smoke + 6) {
                val at = (boss as GunnerBoss).muzzle(Muzzle.RIGHT)
                at.world.spawnParticle(Particle.SMOKE, at.clone().add(0.0, 0.1, 0.0), 2, 0.03, 0.05, 0.03, 0.01)
                if (tick == smoke) at.world.spawnParticle(Particle.POOF, at, 3, 0.05, 0.05, 0.05, 0.02)
            }
        }
    }

    private fun backflip() =
        BackflipSkill.Settings.load(skillSection(BACKFLIP)).let { BackflipSkill(BACKFLIP, it, GunnerSounds.backflip(it)) { isCornered } }

    /** 蹴り: 右足のブーツで当てる。当たった相手は大きく吹き飛ぶ */
    private fun kick(): BossSkill {
        val s = BoneStrikeSkill.Settings.load(skillSection(KICK))
        val knockback = skillSection(KICK).double(Names.SkillConfig.KNOCKBACK, 1.6)
        return BoneStrikeSkill(KICK, s, GunnerSounds.kick(s)) { boss, victim ->
            val away = victim.location.toVector().subtract(boss.entity.location.toVector()).setY(0)
            if (away.lengthSquared() > 1e-4) victim.velocity = away.normalize().multiply(knockback).setY(0.55)
        }
    }

    // ===== 召喚・毎tick =====

    override fun onSpawn() {
        home = entity.location.clone()
        applyWeapon()
        tintBones(visorColor, VISOR)
        // 登場の決めポーズ
        val s = PerformSkill.Settings.load(skillSection(SPAWN_POSE))
        queueSkill(PerformSkill(SPAWN_POSE, s, GunnerSounds.spawnPose(s)))
    }

    override fun onTick() {
        age++
        // 召喚直後はモデルの表示がまだ届いていないことがあるので、少し後にもう一度合わせる
        if (age == 5 || age == 40) {
            applyWeapon()
            tintBones(if (isRaging) visorRageColor else visorColor, VISOR)
        }
        bullets.tick()
        if (age % 5 == 0) updateCornered()
        // 弾を撃ち尽くしたら、今の技が終わった後にリロードする
        // （本気モードの演出と決闘は、その中で弾を込め直す）
        if (ammo <= 0 && !isDying && !hasQueuedSkill && currentSkill?.id !in RELOADING_SKILLS) queueSkill(reload())
    }

    /** 倒しそうな一撃を当てたら、次の技は挑発（ほかに決まった技がなければ） */
    override fun onDamagePlayer(player: Player, event: EntityDamageByEntityEvent) {
        if (isDying || hasQueuedSkill) return
        if (event.finalDamage < player.health + player.absorptionAmount) return
        queueSkill(taunt())
    }

    override fun onPhaseChange(from: BossPhase?, to: BossPhase) {
        if (from == null) return
        if (to.id == FINAL) {
            // 瀕死: すぐに決闘（本気モードの演出より先にここまで削られたら、その場で本気になる）
            if (!isRaging) enrage()
            queueSkill(duel())
            return
        }
        if (to.id != RAGE) return
        // 本気モード: うつむいて弾を込め直し、顔を上げるとバイザーが赤くなる
        val section = skillSection(PHASE_SHIFT)
        val s = PerformSkill.Settings.load(section)
        val changeTick = section.int(Names.SkillConfig.VISOR_CHANGE_TICK, 26)
        queueSkill(PerformSkill(PHASE_SHIFT, s, GunnerSounds.phaseShift(s, changeTick)) { boss, tick ->
            if (tick == changeTick) (boss as GunnerBoss).enrage()
        })
    }

    private fun enrage() {
        isRaging = true
        refillAmmo()
        tintBones(visorRageColor, VISOR)
        val head = entity.location.add(0.0, entity.height * 0.85, 0.0)
        val world = entity.world
        world.spawnParticle(Particle.DUST, head, 30, 0.4, 0.2, 0.4, 0.0, Particle.DustOptions(Color.fromRGB(visorRageColor), 1.2f))
        world.spawnParticle(Particle.FLAME, entity.location.add(0.0, 0.1, 0.0), 40, 1.2, 0.05, 1.2, 0.05)
        world.spawnParticle(Particle.LARGE_SMOKE, entity.location.add(0.0, 0.5, 0.0), 20, 0.8, 0.4, 0.8, 0.02)
    }

    override fun playVanishEffect() {
        val world = entity.world
        val loc = entity.location.add(0.0, 1.0, 0.0)
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 40, 0.5, 0.9, 0.5, 0.02)
        world.spawnParticle(Particle.POOF, loc, 30, 0.5, 0.9, 0.5, 0.05)
        GunnerSounds.VANISH.forEach { it.play(entity) }
    }

    // ===== 立ち位置 =====

    /** 近く（CORNER_CHECK_RANGE 以内）の相手から離れる向きに、CORNER_SPACE ブロックの余裕がなければ追い込まれている */
    private fun updateCornered() {
        val t = TargetFinder.findNearest(this, CORNER_CHECK_RANGE)
        val away = t?.let { Facing.horizontalDir(it, entity) }
        isCornered = away != null && Ground.freeLength(entity.location, away, CORNER_SPACE) < CORNER_SPACE
    }

    /** 召喚地点へ戻る向き（home-radius 以内なら長さ 0。離れるほど長く、最大 1.5） */
    fun homePull(): Vector {
        val h = home ?: return Vector()
        val from = entity.location
        if (h.world != from.world) return Vector()
        val v = Vector(h.x - from.x, 0.0, h.z - from.z)
        val dist = v.length()
        if (dist <= homeRadius || dist < 1e-3) return Vector()
        return v.multiply(((dist - homeRadius) / homeRadius).coerceAtMost(1.5) / dist)
    }

    /**
     * 下がる・跳ぶ向きを選ぶ。preferred に近く、distance ブロック先まで壁がなく、
     * 召喚地点から離れすぎない向き（16方向から選ぶ。真後ろが壁なら横や斜め前へ抜ける）
     */
    fun escapeDir(preferred: Vector, distance: Double): Vector {
        val pref = preferred.clone().setY(0).let { if (it.lengthSquared() < 1e-6) Facing.forward(entity).multiply(-1) else it.normalize() }
        val from = entity.location
        val pull = homePull()
        var best = pref
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until 16) {
            val dir = pref.clone().rotateAroundY(Math.PI * 2 * i / 16)
            val free = Ground.freeLength(from, dir, distance)
            val score = free / distance * 3.0 + dir.dot(pref) * 1.2 + dir.dot(pull) * 1.5
            if (score > bestScore) {
                bestScore = score
                best = dir
            }
        }
        return best
    }

    // ===== 武器 =====

    /** 武器を持ち替える（モデルのボーンを表示・非表示にする） */
    fun equip(w: Weapon) {
        weapon = w
        applyWeapon()
    }

    private fun applyWeapon() {
        val w = weapon
        setPartVisible("pistol_r", w == Weapon.PISTOLS)
        setPartVisible("pistol_l", w == Weapon.PISTOLS)
        setPartVisible("rifle", w == Weapon.RIFLE)
        setPartVisible("rifle_back", w != Weapon.RIFLE)
        setPartVisible("gatling", w == Weapon.GATLING)
        // ガトリングの赤熱と手榴弾は、技の途中でだけ出す
        setPartVisible("heat", false)
        setPartVisible("grenade_hand", false)
    }

    // ===== 撃つ =====

    /** 銃口の位置（モデルのボーンから。見つからなければ胸の前） */
    fun muzzle(m: Muzzle): Location {
        val bone = findBone(m.bone)
        if (bone != null) {
            val p = BoneKinematics.worldPose(bone, entity.location).position
            return Location(entity.world, p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
        }
        return entity.location.add(0.0, entity.height * 0.6, 0.0).add(Facing.forward(entity).multiply(0.8))
    }

    /** ボーンの位置（手榴弾を投げる手など）。見つからなければ null */
    fun bonePoint(name: String): Location? {
        val bone = findBone(name) ?: return null
        val p = BoneKinematics.worldPose(bone, entity.location).position
        return Location(entity.world, p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
    }

    /** 標的の胸元 */
    fun aimPoint(target: LivingEntity): Location = target.location.add(0.0, target.height * 0.55, 0.0)

    /** from から標的の胸元への向き。標的がいなければ正面 */
    fun aimDir(from: Location, target: LivingEntity?): Vector {
        val t = target?.takeIf { TargetFinder.isValidTarget(this, it) } ?: return Facing.forward(entity)
        val v = aimPoint(t).toVector().subtract(from.toVector())
        return if (v.lengthSquared() < 1e-4) Facing.forward(entity) else v.normalize()
    }

    /**
     * 拳銃で標的へ1発撃つ（マズルフラッシュと銃声付き）。
     * 弾が無ければ空撃ち（カチッと鳴って煙が出るだけ）で false
     */
    fun shoot(m: Muzzle, target: LivingEntity?, spec: BulletField.Spec): Boolean {
        val from = muzzle(m)
        if (ammo <= 0) {
            GunnerSounds.playDryFire(entity)
            from.world.spawnParticle(Particle.SMOKE, from, 2, 0.03, 0.03, 0.03, 0.005)
            return false
        }
        ammo--
        val dir = aimDir(from, target)
        bullets.fire(from, dir, spec)
        GunnerFx.muzzleFlash(from, dir)
        GunnerSounds.playPistolShot(entity, if (m == Muzzle.LEFT) 0.92f else 1.0f)
        return true
    }

    /** 弾を満タンにする（本気になると弾倉が大きくなる） */
    fun refillAmmo() {
        ammo = if (isRaging) rageAmmo else normalAmmo
    }

    /** リロードで両手の弾倉から空薬莢を落とす */
    fun ejectCasings() {
        for (side in listOf("cylinder_r", "cylinder_l")) {
            val at = bonePoint(side) ?: continue
            repeat(6) { GunnerFx.dropCasing(at) }
            at.world.spawnParticle(Particle.SMOKE, at, 3, 0.05, 0.05, 0.05, 0.01)
        }
    }

    /**
     * 技を出す標的: range 以内で一番近い相手。ただし minRange より近ければ null
     * （近づかれたら銃ではなく蹴り・バク宙を使う）
     */
    fun findTarget(minRange: Double, range: Double): LivingEntity? {
        val t = TargetFinder.findNearest(this, range) ?: return null
        return if (t.location.distanceSquared(entity.location) < minRange * minRange) null else t
    }

    private fun colorOf(key: String, def: Int): Int {
        val raw = config.string(key).trim().removePrefix("#")
        return raw.toIntOrNull(16) ?: def
    }

    private companion object {
        const val NORMAL = "normal"
        const val RAGE = "rage"
        const val FINAL = "final"

        /** 追い込まれているかを調べる範囲と、背中側に必要な余裕（ブロック） */
        const val CORNER_CHECK_RANGE = 6.0
        const val CORNER_SPACE = 3.0

        /** バイザーのボーン名（glow_ を除いたもの） */
        const val VISOR = "visor"

        // 技ID（設定ファイルの skills.<ID>）
        const val TWIN_BURST = "twin_burst"
        const val TWIN_BURST_RAGE = "twin_burst_rage"
        const val STRAFE_FIRE = "strafe_fire"
        const val SNIPE = "snipe"
        const val SNIPE_RAPID = "snipe_rapid"
        const val GRENADE = "grenade"
        const val KICK = "kick"
        const val BACKFLIP = "backflip"
        const val GATLING = "gatling"
        const val SPAWN_POSE = "spawn_pose"
        const val PHASE_SHIFT = "phase_shift"
        const val RELOAD = "reload"
        const val TAUNT = "taunt"
        const val DUEL = "duel"

        /** 弾を込め直す技（この最中に弾切れでもリロードを予約しない） */
        val RELOADING_SKILLS = setOf(RELOAD, PHASE_SHIFT, DUEL)
    }
}
