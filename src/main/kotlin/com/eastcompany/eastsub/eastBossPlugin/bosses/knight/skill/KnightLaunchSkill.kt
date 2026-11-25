package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos

/**
 * 打ち上げ → 空中連撃: 下から斬り上げて、当たった相手を空高く打ち上げる（盾で防いでも・ジャストガードでも飛ぶ。
 * ダメージはガードで防げる）。騎士も跳んで追いつき、宙に留めた相手を素早い連撃で斬り続け、最後に真下へ叩き落とす。
 * 打ち上げた相手は落下ダメージを受けない（status.fall）。
 *
 *   0 〜 hitWindow            … 屈んで溜め → 斬り上げ（前方 hitRadius・hitDegrees の扇に当たる）
 *   当てて launchDelay 後     … 当てた相手を launchVelocity で真上へ（ジャストガードの吹き飛ばし止めが切れてから）
 *   jumpTick 〜 +riseTicks    … 跳んで、いちばん先に打ち上げた相手の前（holdDistance）へ追いつく（空中連撃のアニメ）
 *   comboHits × comboInterval … 相手を宙に留めて（テレポートで固定）連撃
 *   finishAnimation           … 振りかぶって finishTick に叩き落とす（smashVelocity で下へ）→ landTick に着地
 *   〜 recoverTicks            … 着地の隙
 *
 * 誰にも当たらなければ、跳ばずに斬り上げの隙のまま終わる。
 */
class KnightLaunchSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val hitWindow: IntRange,
        val hitRadius: Double,
        val hitDegrees: Double,
        val damage: Double,
        /** 当ててから空へ飛ばすまでの tick */
        val launchDelay: Int,
        val launchVelocity: Double,
        /** 空振りのときに技を終える tick */
        val endTicks: Int,
        val jumpTick: Int,
        val riseTicks: Int,
        val holdDistance: Double,
        val comboAnimation: String,
        val comboHits: Int,
        val comboInterval: Int,
        val comboDamage: Double,
        val finishAnimation: String,
        val finishTick: Int,
        val finishDamage: Double,
        val smashVelocity: Double,
        val landTick: Int,
        val recoverTicks: Int,
        val fallProtectTicks: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION, "launch"),
                range = section.double(Names.SkillConfig.RANGE, 7.0),
                hitWindow = section.intRanges(Names.SkillConfig.HIT_WINDOWS).firstOrNull() ?: 10..13,
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 5.5),
                hitDegrees = section.double(Names.SkillConfig.HIT_DEGREES, 140.0),
                damage = section.double(Names.SkillConfig.DAMAGE, 5.0),
                launchDelay = section.int(Names.SkillConfig.LAUNCH_TICK, 2).coerceAtLeast(0),
                launchVelocity = section.double(Names.SkillConfig.LAUNCH_VELOCITY, 2.05),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 28),
                jumpTick = section.int(Names.SkillConfig.JUMP_TICK, 20),
                riseTicks = section.int(Names.SkillConfig.RISE_TICKS, 10).coerceAtLeast(1),
                holdDistance = section.double(Names.SkillConfig.HOLD_DISTANCE, 3.2),
                comboAnimation = section.string(Names.SkillConfig.COMBO_ANIMATION, "air_combo"),
                comboHits = section.int(Names.SkillConfig.COMBO_HITS, 6).coerceAtLeast(0),
                comboInterval = section.int(Names.SkillConfig.COMBO_INTERVAL_TICKS, 3).coerceAtLeast(1),
                comboDamage = section.double(Names.SkillConfig.COMBO_DAMAGE, 2.0),
                finishAnimation = section.string(Names.SkillConfig.FINISH_ANIMATION, "air_finish"),
                finishTick = section.int(Names.SkillConfig.FINISH_TICK, 7),
                finishDamage = section.double(Names.SkillConfig.FINISH_DAMAGE, 5.0),
                smashVelocity = section.double(Names.SkillConfig.SMASH_VELOCITY, 1.4),
                landTick = section.int(Names.SkillConfig.LAND_TICK, 18),
                recoverTicks = section.int(Names.SkillConfig.RECOVER_TICKS, 16),
                fallProtectTicks = section.int(Names.SkillConfig.FALL_PROTECT_TICKS, 200),
            )
        }
    }

    private enum class Stage { STRIKE, RISE, COMBO, FINISH, RECOVER }

    override val weight: Int get() = settings.weight

    private var stage = Stage.STRIKE
    private var tick = 0
    private var stageTick = 0
    private var target: LivingEntity? = null
    private val hit = HashSet<UUID>()

    /** 打ち上げる相手と、空へ飛ばす tick */
    private val pendingLaunch = ArrayList<Pair<LivingEntity, Int>>()

    /** 空中で斬る相手（いちばん先に打ち上げた人） */
    private var victim: LivingEntity? = null

    /** 跳び始めた所・相手を留める所 */
    private var jumpFrom: Location? = null
    private var holdAt: Location? = null

    /** 叩き落とした後の、騎士が降り始める所 */
    private var fallFrom: Location? = null

    /** 着地（または空振り）の隙の長さ */
    private var recoverLength = 0

    override val soundTick: Int? get() = if (stage == Stage.STRIKE) tick else -1

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        stage = Stage.STRIKE
        tick = 0
        stageTick = 0
        hit.clear()
        pendingLaunch.clear()
        victim = null
        holdAt = null
        fallFrom = null
        recoverLength = settings.recoverTicks
        target = TargetFinder.findNearest(boss, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        launchPending(boss)
        val done = when (stage) {
            Stage.STRIKE -> strike(boss)
            Stage.RISE -> rise(boss)
            Stage.COMBO -> combo(boss)
            Stage.FINISH -> finish(boss)
            Stage.RECOVER -> stageTick >= recoverLength
        }
        tick++
        stageTick++
        return done
    }

    override fun stop(boss: Boss) {
        val entity = boss.entity
        if (stage == Stage.RISE || stage == Stage.COMBO || stage == Stage.FINISH) {
            // 宙にいるうちに止められたら、その下の地面に降ろす
            val loc = entity.location
            loc.y = Ground.groundY(loc.world, loc.x, loc.y, loc.z, 40)
            entity.teleport(loc)
            boss.stopAnimation(settings.comboAnimation)
            (victim as? Player)?.let { boss.status.fall.protect(it, settings.fallProtectTicks) }
        }
        entity.setGravity(true)
        boss.resumeEntityAi()
        victim = null
        target = null
        pendingLaunch.clear()
    }

    // ===== 斬り上げ =====

    private fun strike(boss: Boss): Boolean {
        if (tick < settings.hitWindow.first) Facing.faceTarget(boss, target)
        if (tick in settings.hitWindow) slashUp(boss)
        if (tick == settings.hitWindow.first) KnightFx.upwardArc(boss.entity, settings.hitRadius)
        if (tick == settings.jumpTick) {
            if (victim == null) {
                // 空振り: 跳ばずに、斬り上げの隙のまま endTicks で終わる
                stage = Stage.RECOVER
                stageTick = -1
                recoverLength = (settings.endTicks - tick).coerceAtLeast(1)
            } else {
                beginRise(boss)
            }
        }
        return false
    }

    /** 前方の扇の中の相手を斬り上げる（1人1回）。当てた相手は少し後で空へ */
    private fun slashUp(boss: Boss) {
        val entity = boss.entity
        val at = entity.location
        val forward = Facing.forward(entity)
        val cosLimit = cos(Math.toRadians(settings.hitDegrees / 2))
        for (e in at.world.getNearbyEntities(at, settings.hitRadius + 1.0, 4.0, settings.hitRadius + 1.0)) {
            if (e !is LivingEntity || e.uniqueId in hit || !TargetFinder.isValidTarget(boss, e)) continue
            val v = Vector(e.location.x - at.x, 0.0, e.location.z - at.z)
            val d = v.length()
            val dy = e.location.y - at.y
            if (d > settings.hitRadius + e.width / 2 || dy < -1.5 || dy > 4.0) continue
            if (d > 0.8 && v.clone().multiply(1 / d).dot(forward) < cosLimit) continue
            hit += e.uniqueId
            e.noDamageTicks = 0
            e.damage(settings.damage, entity)
            if (e is Player) boss.status.fall.protect(e, settings.fallProtectTicks)
            pendingLaunch += e to tick + settings.launchDelay
            if (victim == null) victim = e
            KnightSounds.launchHit(e)
        }
    }

    /** 予約した相手を真上へ飛ばす（ガード・ジャストガードしていても飛ぶ） */
    private fun launchPending(boss: Boss) {
        val it = pendingLaunch.iterator()
        while (it.hasNext()) {
            val (e, at) = it.next()
            if (tick < at) continue
            it.remove()
            if (!e.isValid || e.isDead) continue
            e.velocity = Vector(0.0, settings.launchVelocity, 0.0)
            KnightFx.launchBurst(e.location)
        }
    }

    // ===== 跳んで追いつく =====

    private fun beginRise(boss: Boss) {
        stage = Stage.RISE
        stageTick = 0
        val entity = boss.entity
        entity.setGravity(false)
        jumpFrom = entity.location.clone()
        boss.playLoop(settings.comboAnimation, lerpTicks = 3)
        KnightSounds.leapUp(entity)
        KnightFx.groundBurstSmall(entity.location)
    }

    private fun rise(boss: Boss): Boolean {
        val v = victim?.takeIf { it.isValid && !it.isDead } ?: return abort(boss)
        val entity = boss.entity
        val from = jumpFrom ?: entity.location
        // 相手の少し手前（騎士の側）・少し下へ
        val dir = Facing.horizontalDir(from.toVector(), v.location.toVector()) ?: Facing.forward(entity)
        val dest = v.location.clone().subtract(dir.clone().multiply(settings.holdDistance)).add(0.0, -1.2, 0.0)
        val f = ((stageTick + 1).toDouble() / settings.riseTicks).coerceIn(0.0, 1.0)
        val ease = 1 - (1 - f) * (1 - f)
        val pos = from.clone().add(dest.toVector().subtract(from.toVector()).multiply(ease))
        pos.direction = dir
        pos.pitch = 0f
        entity.teleport(pos)
        if (stageTick % 2 == 0) KnightFx.airTrail(entity)
        if (stageTick + 1 >= settings.riseTicks) {
            stage = Stage.COMBO
            stageTick = -1
            holdAt = v.location.clone()
        }
        return false
    }

    // ===== 空中連撃 =====

    private fun combo(boss: Boss): Boolean {
        val v = victim?.takeIf { it.isValid && !it.isDead && TargetFinder.isValidTarget(boss, it) } ?: return abort(boss)
        hold(boss, v)
        if (stageTick > 0 && stageTick % settings.comboInterval == 0) {
            val n = stageTick / settings.comboInterval
            if (n <= settings.comboHits) {
                v.noDamageTicks = 0
                v.damage(settings.comboDamage, boss.entity)
                KnightFx.airSlash(v.location.add(0.0, v.height * 0.5, 0.0), n)
                KnightSounds.airSlash(v, n)
            }
        }
        if (stageTick >= settings.comboHits * settings.comboInterval + settings.comboInterval) {
            stage = Stage.FINISH
            stageTick = -1
            boss.stopAnimation(settings.comboAnimation)
            boss.playOnce(settings.finishAnimation)
        }
        return false
    }

    /** 相手を宙の同じ所に留め、騎士はその手前に浮く（向きは相手へ） */
    private fun hold(boss: Boss, v: LivingEntity) {
        val at = holdAt ?: return
        val keep = v.location.clone()
        keep.x = at.x
        keep.y = at.y
        keep.z = at.z
        v.teleport(keep)
        v.velocity = Vector(0, 0, 0)
        v.fallDistance = 0f
        val entity = boss.entity
        val dir = Facing.horizontalDir(entity.location.toVector(), at.toVector()) ?: Facing.forward(entity)
        val pos = at.clone().subtract(dir.clone().multiply(settings.holdDistance)).add(0.0, -1.2, 0.0)
        pos.direction = dir
        pos.pitch = 0f
        entity.teleport(pos)
    }

    // ===== とどめ・着地 =====

    private fun finish(boss: Boss): Boolean {
        val entity = boss.entity
        val v = victim?.takeIf { it.isValid && !it.isDead }
        if (stageTick < settings.finishTick) {
            if (v != null) hold(boss, v)
        } else if (stageTick == settings.finishTick) {
            if (v != null && TargetFinder.isValidTarget(boss, v)) {
                v.noDamageTicks = 0
                v.damage(settings.finishDamage, entity)
                if (v is Player) boss.status.fall.protect(v, settings.fallProtectTicks)
                v.velocity = Vector(0.0, -settings.smashVelocity, 0.0)
                KnightFx.smashDown(v.location)
                KnightSounds.airSmash(v)
            }
            fallFrom = entity.location.clone()
        } else {
            // 叩き落としてから着地まで、真下の地面へ落ちていく（だんだん速く）
            val from = fallFrom ?: entity.location
            val ground = Ground.groundY(from.world, from.x, from.y, from.z, 48)
            val span = (settings.landTick - settings.finishTick).coerceAtLeast(1)
            val f = ((stageTick - settings.finishTick).toDouble() / span).coerceIn(0.0, 1.0)
            val pos = from.clone()
            pos.y = from.y + (ground - from.y) * f * f
            entity.teleport(pos)
            if (stageTick >= settings.landTick) {
                pos.y = ground
                entity.teleport(pos)
                entity.setGravity(true)
                KnightFx.groundBurstSmall(pos)
                KnightSounds.land(entity)
                stage = Stage.RECOVER
                stageTick = -1
            }
        }
        return false
    }

    /** 相手がいなくなった: その下の地面へ降りて終わる */
    private fun abort(boss: Boss): Boolean {
        val entity = boss.entity
        boss.stopAnimation(settings.comboAnimation)
        val loc = entity.location
        loc.y = Ground.groundY(loc.world, loc.x, loc.y, loc.z, 48)
        entity.teleport(loc)
        entity.setGravity(true)
        stage = Stage.RECOVER
        stageTick = -1
        return false
    }
}
