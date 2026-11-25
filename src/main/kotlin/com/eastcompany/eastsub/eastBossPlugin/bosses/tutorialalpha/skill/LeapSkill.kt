package com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.model.BoneSweep
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 大ジャンプ → 標的を追って滞空 → 急降下して衝撃波 → 倒れた勢いで前進。
 *
 *   0 〜 jumpStart        … 標的の方を向いて溜め
 *   jumpStart 〜 land     … 跳躍。着地点は land の lockTicks 前まで標的を追いかける（地面に予告の円）
 *   land                  … 着地の衝撃波（impactRadius 以内）
 *   land 〜 slideEnd      … 前進しながら減速
 *   〜 endTicks           … 静止
 *
 * hitWindow の間は、指定ボーン（体・剣など）にも当たり判定がある。
 * この技の最中（隙も含む）は、受けるダメージが takenDamage に固定される。
 */
class LeapSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val jumpStartTicks: Int,
        val peakTicks: Int,
        val landTicks: Int,
        val slideEndTicks: Int,
        val endTicks: Int,
        /** 最高点の高さ（ブロック） */
        val jumpHeight: Double,
        /** 急降下にかけるtick数（短いほど速く落ちる） */
        val diveTicks: Int,
        /** 着地の何tick前に着地点を確定するか（大きいほど避けやすい） */
        val lockTicks: Int,
        /** 動いている標的の進行方向に、どれだけ先へ落ちるか（ブロック） */
        val leadDistance: Double,
        /** これより遅い動き（ブロック/tick）は「止まっている」とみなす */
        val movingThreshold: Double,
        /** 標的が消えたとき前方に跳ぶ距離 */
        val fallbackDistance: Double,
        /** 着地直後の前進速度（ブロック/tick）。slideEnd までに 0 へ減速 */
        val slideSpeed: Double,
        /** この技の最中に受けるダメージ */
        val takenDamage: Double,
        val impactRadius: Double,
        val impactDamage: Double,
        /** ボーン判定に使うボーン（それぞれ直下のキューブの形で判定） */
        val hitBones: List<String>,
        /** ボーン判定の区間（tick、両端を含む） */
        val hitWindow: IntRange,
        val hitDamage: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 30.0),
                jumpStartTicks = section.int(Names.SkillConfig.JUMP_START_TICKS, 7),
                peakTicks = section.int(Names.SkillConfig.PEAK_TICKS, 45),
                landTicks = section.int(Names.SkillConfig.LAND_TICKS, 68),
                slideEndTicks = section.int(Names.SkillConfig.SLIDE_END_TICKS, 150),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 180),
                jumpHeight = section.double(Names.SkillConfig.JUMP_HEIGHT, 14.0),
                diveTicks = section.int(Names.SkillConfig.DIVE_TICKS, 6).coerceAtLeast(1),
                lockTicks = section.int(Names.SkillConfig.LOCK_TICKS, 24),
                leadDistance = section.double(Names.SkillConfig.LEAD_DISTANCE, 2.5),
                movingThreshold = section.double(Names.SkillConfig.MOVING_THRESHOLD, 0.05),
                fallbackDistance = section.double(Names.SkillConfig.FALLBACK_DISTANCE, 8.0),
                slideSpeed = section.double(Names.SkillConfig.SLIDE_SPEED, 0.35),
                takenDamage = section.double(Names.SkillConfig.TAKEN_DAMAGE, 1.0),
                impactRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 3.5),
                impactDamage = section.double(Names.SkillConfig.IMPACT_DAMAGE, 20.0),
                hitBones = section.stringList(Names.SkillConfig.BONES),
                hitWindow = section.intRanges(Names.SkillConfig.HIT_WINDOWS).firstOrNull() ?: IntRange.EMPTY,
                hitDamage = section.double(Names.SkillConfig.DAMAGE, 10.0),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private var aim: Vector = Vector(0, 0, 1)
    private var origin = Vector()
    private var landing = Vector()

    /** 着地点を確定したか（確定までは標的を追いかける） */
    private var landingLocked = false

    /** 標的の前tickの位置（移動速度から着地点を予測する用） */
    private var lastTargetPos: Vector? = null

    /** 着地の衝撃のヒット履歴（ボーン判定とは別に1人1回） */
    private val impactHit = HashSet<UUID>()

    /** 判定するボーンごとの掃引判定 */
    private val sweeps = HashMap<String, BoneSweep>()

    /** 全ボーン共通のヒット履歴（どのボーンで当たっても1人1回） */
    private val boneHit = HashSet<UUID>()

    private val warned = HashSet<String>()

    override fun canStart(boss: Boss): Boolean =
        TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        boneHit.clear()
        impactHit.clear()
        landingLocked = false
        lastTargetPos = null
        sweeps.clear()
        settings.hitBones.forEach { sweeps[it] = BoneSweep(debug = { boss.debugHitbox() }) }

        val t = TargetFinder.findNearest(boss, settings.range)
        target = t
        aim = t?.let { Facing.horizontalDir(boss.entity, it) } ?: Facing.forward(boss.entity)

        boss.entity.setAI(false)
        boss.entity.setGravity(false)
        Facing.face(boss.entity, aim)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val entity = boss.entity
        when {
            // 溜め：標的の方を向く
            tick < settings.jumpStartTicks -> {
                Facing.faceTarget(boss, target)?.let { aim = it } ?: Facing.face(entity, aim)
            }
            // 跳躍 → 最高点で滞空しながら標的を追う → 一気に急降下
            tick < settings.landTicks -> {
                if (tick == settings.jumpStartTicks) startJump(boss)
                if (!landingLocked) trackLanding(boss)
                if (tick >= lockTick()) landingLocked = true
                if (landingLocked) drawWarning(entity)
                airStep(entity)
            }
            // 着地：衝撃波
            tick == settings.landTicks -> {
                moveTo(entity, landing.x, landing.y, landing.z)
                impact(boss)
            }
            // 倒れた勢いで前進（だんだん減速）
            tick < settings.slideEndTicks -> slideStep(boss)
            // アニメ終了までその場で静止
        }

        // 移動を反映した後のボーン位置で判定する
        boneHitStep(boss)

        entity.fallDistance = 0f
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        boss.resumeEntityAi()
        boss.entity.setGravity(true)
        sweeps.values.forEach { it.reset() }
        target = null
    }

    /** この技の最中は、殴られても takenDamage しか受けない */
    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        event.damage = settings.takenDamage
    }

    /** ボーンの当たり判定（hitWindow の間） */
    private fun boneHitStep(boss: Boss) {
        val window = settings.hitWindow
        if (window.isEmpty()) return

        val inWindow = tick in window
        val primeTick = tick == window.first - 1
        if (!inWindow && !primeTick) return

        val entity = boss.entity
        val origin = entity.location

        for ((name, sweep) in sweeps) {
            val bone = boss.findBone(name) ?: run {
                warnOnce(boss, name, "ボーン $name が見つかりません")
                continue
            }
            val frame = BoneSweep.frameOf(bone, origin) ?: run {
                warnOnce(boss, name, "$name の直下にキューブがありません（間にグループを挟まないでください）")
                continue
            }

            if (primeTick) {
                sweep.prime(frame)
                continue
            }

            val touched = sweep.detect(entity.world, frame, boneHit) { TargetFinder.isValidTarget(boss, it) }
            for (victim in touched) {
                victim.noDamageTicks = 0
                victim.damage(settings.hitDamage, entity)
            }
        }
    }

    private fun warnOnce(boss: Boss, key: String, message: String) {
        if (warned.add(key)) boss.plugin.logger.warning("[${boss.type.id}/$id] $message")
    }

    /** 跳躍開始：出発点を記録し、最初の着地点を決める */
    private fun startJump(boss: Boss) {
        origin = boss.entity.location.toVector()
        landing = origin.clone()
        trackLanding(boss)
    }

    /**
     * 着地点を標的に合わせて更新する（lockTicks 前まで毎tick）。
     *
     * 標的が動いていれば、進行方向に leadDistance だけ先へ落ちる。
     * まっすぐ走り続けると当たるが、止まる・引き返す・横へ曲がれば避けられる。
     * 止まっている標的には真上から落ちる（確定後に動けば避けられる）。
     */
    private fun trackLanding(boss: Boss) {
        val entity = boss.entity
        val t = target

        val base: Location = if (t != null && TargetFinder.isValidTarget(boss, t)) {
            val now = t.location.toVector()
            val prev = lastTargetPos ?: now
            lastTargetPos = now

            val move = now.clone().subtract(prev).setY(0.0)

            if (move.lengthSquared() > settings.movingThreshold * settings.movingThreshold) {
                t.location.clone().add(move.normalize().multiply(settings.leadDistance))
            } else {
                t.location
            }
        } else {
            entity.location.clone().add(aim.clone().multiply(settings.fallbackDistance))
        }

        landing = Vector(base.x, groundY(base), base.z)
        Facing.horizontalDir(entity.location.toVector(), landing)?.let { aim = it }
    }

    /** 着地点を確定するtick（ここからは追いかけない） */
    private fun lockTick(): Int =
        maxOf(settings.jumpStartTicks, settings.landTicks - settings.lockTicks)

    /** 急降下を始めるtick */
    private fun diveStart(): Int =
        maxOf(settings.peakTicks, settings.landTicks - settings.diveTicks)

    /** 着地点の予告：衝撃波の範囲を地面に円で表示（2tickごと） */
    private fun drawWarning(entity: Mob) {
        if (tick % 2 != 0) return
        val world = entity.world
        val r = settings.impactRadius
        val points = 32
        for (i in 0 until points) {
            val a = 2 * PI * i / points
            world.spawnParticle(
                Particle.DUST,
                landing.x + cos(a) * r,
                landing.y + 0.1,
                landing.z + sin(a) * r,
                1, 0.0, 0.0, 0.0, 0.0,
                WARNING_DUST
            )
        }
    }

    private fun airStep(entity: Mob) {
        val jumpStart = settings.jumpStartTicks
        val peak = settings.peakTicks
        val land = settings.landTicks
        val dive = diveStart()

        // 水平：残りtickで着地点にちょうど着くように、毎tick寄せていく
        // （着地点が動いても滑らかに追いかける）
        val loc = entity.location
        val remaining = (land - tick).coerceAtLeast(1)
        val x = loc.x + (landing.x - loc.x) / remaining
        val z = loc.z + (landing.z - loc.z) / remaining

        // 高さ：上昇はだんだん減速(sin) → 最高点で滞空 → 急降下(三乗で一気に加速)
        val h = when {
            tick < peak -> {
                val v = (tick - jumpStart + 1).toDouble() / (peak - jumpStart).coerceAtLeast(1)
                sin(v * PI / 2)
            }
            tick < dive -> 1.0
            else -> {
                val w = (tick - dive + 1).toDouble() / (land - dive).coerceAtLeast(1)
                1.0 - w * w * w
            }
        }.coerceIn(0.0, 1.0)

        val u = ((tick - jumpStart + 1).toDouble() / (land - jumpStart).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val baseY = origin.y + (landing.y - origin.y) * u

        moveTo(entity, x, baseY + settings.jumpHeight * h, z)
    }

    /** 着地の衝撃波：着地点から半径 impactRadius 以内（高さ±2ブロック）にダメージ */
    private fun impact(boss: Boss) {
        val entity = boss.entity
        val center = landing
        val r = settings.impactRadius

        for (e in entity.world.getNearbyEntities(entity.location, r, 2.0, r)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            if (e.uniqueId in impactHit) continue

            // 着地点から相手の当たり判定ボックスまでの水平距離
            val bb = e.boundingBox
            val dx = center.x - center.x.coerceIn(bb.minX, bb.maxX)
            val dz = center.z - center.z.coerceIn(bb.minZ, bb.maxZ)
            if (dx * dx + dz * dz > r * r) continue

            impactHit.add(e.uniqueId)
            val victim = e as LivingEntity
            victim.noDamageTicks = 0
            victim.damage(settings.impactDamage, entity)
        }
    }

    private fun slideStep(boss: Boss) {
        val entity = boss.entity
        val p = (tick - settings.landTicks).toDouble() /
                (settings.slideEndTicks - settings.landTicks).coerceAtLeast(1)
        val speed = settings.slideSpeed * (1.0 - p)

        val loc = entity.location
        val nx = loc.x + aim.x * speed
        val nz = loc.z + aim.z * speed
        val feet = entity.world.getBlockAt(floor(nx).toInt(), loc.blockY, floor(nz).toInt())
        // 壁に当たったら進まない
        if (feet.isPassable && feet.getRelative(0, 1, 0).isPassable) {
            moveTo(entity, nx, loc.y, nz)
        }
    }

    /** 真下の地面の高さ（16ブロック下まで探す） */
    private fun groundY(loc: Location): Double {
        val w = loc.world
        var y = loc.blockY
        repeat(16) {
            if (!w.getBlockAt(loc.blockX, y - 1, loc.blockZ).isPassable) return y.toDouble()
            y--
        }
        return loc.y
    }

    private fun moveTo(entity: Mob, x: Double, y: Double, z: Double) {
        val loc = entity.location
        loc.set(x, y, z)
        loc.direction = aim
        loc.pitch = 0f
        entity.teleport(loc)
    }

    private companion object {
        /** 着地点の予告の色（ピンク） */
        val WARNING_DUST = Particle.DustOptions(Color.fromRGB(255, 120, 200), 1.2f)
    }
}
