package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.model.BoneKinematics
import com.eastcompany.eastsub.eastBossPlugin.model.BoneSweep
import com.eastcompany.eastsub.eastBossPlugin.model.SwordSweep
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.max

/**
 * アニメーションに合わせて、指定ボーンの当たり判定でダメージを与える近接攻撃。
 * 剣振り・ジャブ・振り下ろしなど、判定の時間・ボーン・ダメージが違うだけの攻撃はこれで作れる。
 *
 *   0 〜 最初の判定区間       … 標的の方を向く
 *   判定区間（複数可）       … ボーンの当たり判定（区間ごとに1人1回）
 *   endTicks                 … 技の終了
 *
 * 判定の形は [Shape] で選ぶ。
 *
 * approachSpeed を 0 より大きくすると、振る前に標的へダッシュで近づく（標的との距離が approachDistance 以下になるまで）。
 * 上の tick（判定区間・endTicks・効果音）は、近づき終えて振り始めてからの tick。
 * 最初から approachDistance 以内にいれば、ダッシュせずにすぐ振る。
 *
 * lungeWindows（tick の区間）の間は、1tick に lungeSpeed ずつ前へ踏み込む（連撃で1振りごとに詰めるなど）。
 * 標的との水平距離が lungeStopDistance 以下なら踏み込まない（重ならないように。0 なら標的を突き抜けて進む）。向きは変えない。
 * retreatWindows（tick の区間）の間は、1tick に retreatSpeed ずつ後ろへ下がる（振りかぶりながら間合いを取り、踏み込みで詰めるなど）。
 *
 * @param onHit 当てた相手ごとに呼ばれる（ダメージを与えた後）
 */
class BoneStrikeSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
    private val onHit: (Boss, LivingEntity) -> Unit = { _, _ -> },
) : BossSkill {

    /** 当たり判定の形 */
    enum class Shape {
        /** ボーン直下のキューブをそのままの箱として判定（胴体・腕など） */
        BOX,

        /** キューブの一番長い辺を刃とみなし、線分＋太さで判定（剣など） */
        BLADE,
    }

    data class Settings(
        val weight: Int,
        val animation: String,
        /** この距離以内に標的がいるときだけ発動 */
        val range: Double,
        /** 判定区間（tick、両端を含む） */
        val hitWindows: List<IntRange>,
        val endTicks: Int,
        val damage: Double,
        val bones: List<String>,
        val shape: Shape,
        /** BLADE のときの刃の太さ（半径）の最低値。キューブが細すぎても当たるように */
        val minBladeRadius: Double,
        /** 振る前のダッシュの速さ（1tick に進むブロック）。0 以下でダッシュしない */
        val approachSpeed: Double,
        /** 標的との水平距離がこれ以下になったら振り始める */
        val approachDistance: Double,
        /** ダッシュの最大時間。届かなくてもこの時間で振り始める */
        val approachMaxTicks: Int,
        /** ダッシュ中に繰り返すアニメ（空ならアニメなし） */
        val approachAnimation: String,
        /** 振っている途中で前へ踏み込む区間（tick、両端を含む。振り始めてからの tick） */
        val lungeWindows: List<IntRange> = emptyList(),
        /** 踏み込みの速さ（1tick に進むブロック） */
        val lungeSpeed: Double = 0.3,
        /** 標的との水平距離がこれ以下なら踏み込まない */
        val lungeStopDistance: Double = 1.4,
        /** 振っている途中で後ろへ下がる区間（tick、両端を含む。振り始めてからの tick） */
        val retreatWindows: List<IntRange> = emptyList(),
        /** 下がる速さ（1tick に進むブロック） */
        val retreatSpeed: Double = 0.2,
        /** 判定区間の間、前方のこの半径（ブロック）・角度の扇の中にも当たる（0 で無し。刃が通らない足元を埋める） */
        val closeRadius: Double = 0.0,
        val closeDegrees: Double = 160.0,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 4.0),
                hitWindows = section.intRanges(Names.SkillConfig.HIT_WINDOWS),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 40),
                damage = section.double(Names.SkillConfig.DAMAGE, 5.0),
                bones = section.stringList(Names.SkillConfig.BONES),
                shape = section.enum(Names.SkillConfig.SHAPE, Shape.BOX),
                minBladeRadius = section.double(Names.SkillConfig.MIN_BLADE_RADIUS, 0.15),
                approachSpeed = section.double(Names.SkillConfig.APPROACH_SPEED, 0.0),
                approachDistance = section.double(Names.SkillConfig.APPROACH_DISTANCE, 3.0),
                approachMaxTicks = section.int(Names.SkillConfig.APPROACH_MAX_TICKS, 30),
                approachAnimation = section.string(Names.SkillConfig.APPROACH_ANIMATION),
                lungeWindows = section.intRanges(Names.SkillConfig.LUNGE_WINDOWS),
                lungeSpeed = section.double(Names.SkillConfig.LUNGE_SPEED, 0.3).coerceAtLeast(0.0),
                lungeStopDistance = section.double(Names.SkillConfig.LUNGE_STOP_DISTANCE, 1.4),
                retreatWindows = section.intRanges(Names.SkillConfig.RETREAT_WINDOWS),
                retreatSpeed = section.double(Names.SkillConfig.RETREAT_SPEED, 0.2).coerceAtLeast(0.0),
                closeRadius = section.double(Names.SkillConfig.CLOSE_RADIUS, 0.0),
                closeDegrees = section.double(Names.SkillConfig.CLOSE_DEGREES, 160.0),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null

    /** 標的へダッシュ中か */
    private var approaching = false
    private var approachTick = 0

    /** ダッシュ中は効果音を鳴らさず、振り始めてから tick を数える */
    override val soundTick: Int? get() = if (approaching) -1 else tick

    /** 判定区間ごとのヒット履歴（全ボーン共通。どのボーンで当たっても1人1回） */
    private val hit = HashSet<UUID>()

    private val boxSweeps = HashMap<String, BoneSweep>()
    private val bladeSweeps = HashMap<String, SwordSweep>()

    private val warned = HashSet<String>()

    private val firstHitTick: Int get() = settings.hitWindows.minOfOrNull { it.first } ?: Int.MAX_VALUE

    override fun canStart(boss: Boss): Boolean =
        TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        approachTick = 0
        hit.clear()
        target = TargetFinder.findNearest(boss, settings.range)
        createSweeps(boss)

        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)

        approaching = settings.approachSpeed > 0 && !isClose(boss)
        if (approaching) {
            boss.playLoop(settings.approachAnimation, lerpTicks = 2)
            val loc = boss.entity.location
            loc.world.playSound(loc, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.HOSTILE, 1.5f, 0.6f)
        } else {
            boss.playOnce(settings.animation)
        }
    }

    override fun tick(boss: Boss): Boolean {
        if (approaching) {
            approach(boss)
            return false
        }

        // 最初の判定が始まるまでは標的の方を向く
        if (tick < firstHitTick) Facing.faceTarget(boss, target)
        if (settings.lungeWindows.any { tick in it }) lunge(boss)
        if (settings.retreatWindows.any { tick in it }) retreat(boss)

        val inWindow = settings.hitWindows.any { tick in it }
        // 判定区間の1tick前：ヒット履歴をリセットして姿勢だけ記録する
        // → 前の区間で当たった相手にも、次の区間でもう一度当たる
        val primeTick = settings.hitWindows.any { tick == it.first - 1 }

        if (primeTick) hit.clear()
        if (inWindow && settings.closeRadius > 0) hitClose(boss)
        if (inWindow || primeTick) {
            for (name in settings.bones) hitBone(boss, name, prime = primeTick, detect = inWindow)
        }

        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        if (approaching) boss.stopAnimation(settings.approachAnimation)
        approaching = false
        boss.resumeEntityAi()
        boxSweeps.values.forEach { it.reset() }
        bladeSweeps.values.forEach { it.reset() }
        target = null
    }

    // ===== 振る前のダッシュ =====

    /** 標的が振り始められる距離にいるか（標的がいなくなったときも true = その場で振る） */
    private fun isClose(boss: Boss): Boolean {
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) } ?: return true
        val a = boss.entity.location
        val b = t.location
        val dx = b.x - a.x
        val dz = b.z - a.z
        return dx * dx + dz * dz <= settings.approachDistance * settings.approachDistance
    }

    /** 標的へ1tick分進む。着いたら（または進めなくなったら）振り始める */
    private fun approach(boss: Boss) {
        val entity = boss.entity
        if (isClose(boss) || approachTick >= settings.approachMaxTicks) return beginStrike(boss)
        approachTick++

        val t = target ?: return beginStrike(boss)
        val from = entity.location
        val dir = Facing.horizontalDir(entity, t) ?: return beginStrike(boss)
        // 近づきすぎないよう、振り始める距離の手前で止める
        val dist = Math.hypot(t.location.x - from.x, t.location.z - from.z)
        val step = minOf(settings.approachSpeed, dist - settings.approachDistance + 0.3).coerceAtLeast(0.1)

        val to = Ground.step(from.clone().add(dir.clone().multiply(step))) ?: return beginStrike(boss)
        to.direction = dir
        to.pitch = 0f
        entity.teleport(to)

        val world = from.world
        world.spawnParticle(Particle.CLOUD, from.clone().add(0.0, 0.2, 0.0), 2, 0.3, 0.05, 0.3, 0.01)
        world.spawnParticle(Particle.WITCH, from.clone().add(0.0, entity.height * 0.5, 0.0), 3, 0.4, 0.6, 0.4, 0.0)
    }

    /** 振りながら1tick分前へ踏み込む（向きはそのまま） */
    private fun lunge(boss: Boss) {
        val entity = boss.entity
        val from = entity.location
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) }
        if (t != null && Math.hypot(t.location.x - from.x, t.location.z - from.z) <= settings.lungeStopDistance) return
        val dir = Facing.forward(entity)
        val to = Ground.step(from.clone().add(dir.multiply(settings.lungeSpeed))) ?: return
        to.yaw = from.yaw
        to.pitch = 0f
        entity.teleport(to)
    }

    /** 前方の扇（closeRadius・closeDegrees）の中の相手にも当てる（刃の内側＝足元の相手。刃と合わせて1人1回） */
    private fun hitClose(boss: Boss) {
        val entity = boss.entity
        val at = entity.location
        val forward = Facing.forward(entity)
        val cosLimit = Math.cos(Math.toRadians(settings.closeDegrees / 2))
        val r = settings.closeRadius
        for (e in at.world.getNearbyEntities(at, r + 1.0, 3.0, r + 1.0)) {
            if (e !is LivingEntity || e.uniqueId in hit || !TargetFinder.isValidTarget(boss, e)) continue
            val v = org.bukkit.util.Vector(e.location.x - at.x, 0.0, e.location.z - at.z)
            val d = v.length()
            val dy = e.location.y - at.y
            if (d > r + e.width / 2 || dy < -1.5 || dy > 3.0) continue
            if (d > 0.8 && v.multiply(1 / d).dot(forward) < cosLimit) continue
            hit += e.uniqueId
            e.noDamageTicks = 0
            e.damage(settings.damage, entity)
            if (e is Player && boss.status.justGuard.guardedNow(e)) continue
            onHit(boss, e)
        }
    }

    /** 振りながら1tick分後ろへ下がる（向きはそのまま。壁などで下がれなければ止まる） */
    private fun retreat(boss: Boss) {
        val entity = boss.entity
        val from = entity.location
        val back = Facing.forward(entity).multiply(-settings.retreatSpeed)
        val to = Ground.step(from.clone().add(back)) ?: return
        to.yaw = from.yaw
        to.pitch = 0f
        entity.teleport(to)
    }

    /** ダッシュを終えて、標的の方を向いて振り始める */
    private fun beginStrike(boss: Boss) {
        approaching = false
        tick = 0
        boss.stopAnimation(settings.approachAnimation)
        Facing.faceTarget(boss, target)
        boss.playOnce(settings.animation)
    }

    private fun createSweeps(boss: Boss) {
        boxSweeps.clear()
        bladeSweeps.clear()
        val debug = { boss.debugHitbox() }
        for (name in settings.bones) {
            when (settings.shape) {
                Shape.BOX -> boxSweeps[name] = BoneSweep(debug = debug)
                Shape.BLADE -> {
                    // 刃の太さ = キューブの太さ（細すぎる場合は最低値）
                    val radius = boss.findBone(name)?.let { BoneKinematics.bladeRadiusOf(it) } ?: 0.0
                    bladeSweeps[name] = SwordSweep(radius = max(radius, settings.minBladeRadius), debug = debug)
                }
            }
        }
    }

    private fun hitBone(boss: Boss, name: String, prime: Boolean, detect: Boolean) {
        val entity = boss.entity
        val bone = boss.findBone(name) ?: return warnOnce(boss, name, "ボーン $name が見つかりません")
        val noCube = "$name の直下にキューブがありません（間にグループを挟まないでください）"
        val filter = { e: LivingEntity -> TargetFinder.isValidTarget(boss, e) }

        val victims = when (settings.shape) {
            Shape.BOX -> {
                val sweep = boxSweeps[name] ?: return
                val frame = BoneSweep.frameOf(bone, entity.location) ?: return warnOnce(boss, name, noCube)
                if (prime) {
                    sweep.reset()
                    sweep.prime(frame)
                }
                if (detect) sweep.detect(entity.world, frame, hit, filter) else emptyList()
            }
            Shape.BLADE -> {
                val sweep = bladeSweeps[name] ?: return
                val frame = SwordSweep.frameOf(bone, entity.location) ?: return warnOnce(boss, name, noCube)
                if (prime) {
                    sweep.reset()
                    sweep.prime(frame)
                }
                if (detect) sweep.detect(entity.world, frame, hit, filter) else emptyList()
            }
        }

        for (victim in victims) {
            victim.noDamageTicks = 0
            victim.damage(settings.damage, entity)
            // ジャストガードされたら、当たったときの追加効果は無し
            if (victim is Player && boss.status.justGuard.guardedNow(victim)) continue
            onHit(boss, victim)
        }
    }

    private fun warnOnce(boss: Boss, key: String, message: String) {
        if (warned.add(key)) boss.plugin.logger.warning("[${boss.type.id}/$id] $message")
    }
}
