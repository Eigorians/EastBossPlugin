package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 間合いを測る: 大剣を構えたまま相手を見据え、相手の周りを横へ回る（すり足）。ときどき素早く横へステップする。
 * 技と技の合間に出す（KnightBoss が技の終わりに extra.circle-chance の割合で決める。抽選では選ばれない）。
 *
 * - 相手との距離が minRange〜maxRange のときだけ始める（遠すぎれば歩いて寄り、近すぎればすぐ次の技）
 * - 回りながら、相手との距離を preferredDistance へ少しずつ合わせる
 * - stepIntervalMin〜Max ごとに横ステップ（stepTicks で stepDistance 跳ぶ。stepReverseChance の割合で逆へ）
 * - 壁などで進めなければ、回る向きを変える
 * - minTicks〜maxTicks 回ったら終わる（相手がいなくなっても終わる）
 */
class KnightCircleSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val minRange: Double,
        val maxRange: Double,
        val preferredDistance: Double,
        /** 回る速さ（1tick に進むブロック） */
        val speed: Double,
        val minTicks: Int,
        val maxTicks: Int,
        val stepIntervalMin: Int,
        val stepIntervalMax: Int,
        val stepTicks: Int,
        val stepDistance: Double,
        val stepReverseChance: Double,
        val leftAnimation: String,
        val rightAnimation: String,
        val stepLeftAnimation: String,
        val stepRightAnimation: String,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings {
                val minTicks = section.int(Names.SkillConfig.MIN_TICKS, 30).coerceAtLeast(1)
                val stepMin = section.int(Names.SkillConfig.STEP_INTERVAL_MIN, 18).coerceAtLeast(1)
                return Settings(
                    minRange = section.double(Names.SkillConfig.MIN_RANGE, 4.0),
                    maxRange = section.double(Names.SkillConfig.MAX_RANGE, 12.0),
                    preferredDistance = section.double(Names.SkillConfig.PREFERRED_DISTANCE, 7.0),
                    speed = section.double(Names.SkillConfig.SPEED, 0.16),
                    minTicks = minTicks,
                    maxTicks = section.int(Names.SkillConfig.MAX_TICKS, 70).coerceAtLeast(minTicks),
                    stepIntervalMin = stepMin,
                    stepIntervalMax = section.int(Names.SkillConfig.STEP_INTERVAL_MAX, 34).coerceAtLeast(stepMin),
                    stepTicks = section.int(Names.SkillConfig.STEP_TICKS, 10).coerceAtLeast(1),
                    stepDistance = section.double(Names.SkillConfig.STEP_DISTANCE, 3.0),
                    stepReverseChance = section.double(Names.SkillConfig.STEP_REVERSE_CHANCE, 0.4).coerceIn(0.0, 1.0),
                    leftAnimation = section.string(Names.SkillConfig.LEFT_ANIMATION, "strafe_left"),
                    rightAnimation = section.string(Names.SkillConfig.RIGHT_ANIMATION, "strafe_right"),
                    stepLeftAnimation = section.string(Names.SkillConfig.STEP_LEFT_ANIMATION, "step_left"),
                    stepRightAnimation = section.string(Names.SkillConfig.STEP_RIGHT_ANIMATION, "step_right"),
                )
            }
        }
    }

    /** 抽選では選ばれない（技の終わりに決めて出す） */
    override val weight: Int get() = 0

    private var tick = 0
    private var duration = 0
    private var target: LivingEntity? = null

    /** 回る向き（+1 = 自分の右へ、-1 = 左へ） */
    private var side = 1

    /** 次の横ステップまでの tick */
    private var nextStep = 0

    /** 横ステップの残り tick（0 ならすり足） */
    private var stepLeft = 0

    /** 繰り返している（すり足の）アニメ */
    private var loopAnimation: String? = null

    override fun canStart(boss: Boss): Boolean {
        val t = TargetFinder.findNearest(boss, settings.maxRange) ?: return false
        val d = hypot(t.location.x - boss.entity.location.x, t.location.z - boss.entity.location.z)
        return d >= settings.minRange
    }

    override fun start(boss: Boss) {
        tick = 0
        stepLeft = 0
        duration = Random.nextInt(settings.minTicks, settings.maxTicks + 1)
        target = TargetFinder.findNearest(boss, settings.maxRange)
        side = if (Random.nextBoolean()) 1 else -1
        nextStep = nextStepDelay()
        loopAnimation = null
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        strafe(boss)
    }

    override fun tick(boss: Boss): Boolean {
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) } ?: return true
        val entity = boss.entity
        val from = entity.location
        val toTarget = Vector(t.location.x - from.x, 0.0, t.location.z - from.z)
        val dist = toTarget.length()
        if (dist < 1e-3) return true
        val forward = toTarget.clone().multiply(1 / dist)
        // 自分の右（前を見たときの右手側）
        val right = Vector(-forward.z, 0.0, forward.x)

        // 横ステップ
        if (stepLeft == 0 && --nextStep <= 0) {
            if (Random.nextDouble() < settings.stepReverseChance) side = -side
            stepLeft = settings.stepTicks
            nextStep = nextStepDelay()
            loopAnimation?.let { boss.stopAnimation(it) }
            loopAnimation = null
            boss.playOnce(if (side > 0) settings.stepRightAnimation else settings.stepLeftAnimation)
            KnightSounds.sideStep(entity)
        }

        val speed = if (stepLeft > 0) {
            // さっと出て、すっと止まる: 残りの距離 = stepDistance × (残り時間の割合)^3
            val before = stepLeft.toDouble() / settings.stepTicks
            val after = (stepLeft - 1.0) / settings.stepTicks
            settings.stepDistance * (before * before * before - after * after * after)
        } else settings.speed
        // 相手との距離を preferredDistance へ少しずつ合わせる
        val radial = ((dist - settings.preferredDistance) * 0.06).coerceIn(-0.12, 0.12)
        val move = right.clone().multiply(speed * side).add(forward.clone().multiply(radial))

        // 出だしは 1tick に 1ブロック以上進むので、刻んで調べる（薄い壁をすり抜けないように）
        val pieces = ceil(move.length() / MAX_STEP).toInt().coerceAtLeast(1)
        val piece = move.clone().multiply(1.0 / pieces)
        var to: Location? = from.clone()
        for (i in 0 until pieces) to = to?.let { Ground.step(it.add(piece)) }
        if (to == null) {
            // 進めなければ向きを変える
            side = -side
            to = Ground.step(from.clone().add(right.clone().multiply(speed.coerceAtMost(MAX_STEP) * side)))
            if (stepLeft == 0) strafe(boss)
        }
        val dest = to ?: from.clone()
        dest.direction = Vector(t.location.x - dest.x, 0.0, t.location.z - dest.z).takeIf { it.lengthSquared() > 1e-4 } ?: forward
        dest.pitch = 0f
        entity.teleport(dest)

        if (stepLeft > 0 && --stepLeft == 0) strafe(boss)

        tick++
        return tick >= duration
    }

    override fun stop(boss: Boss) {
        loopAnimation?.let { boss.stopAnimation(it) }
        loopAnimation = null
        boss.resumeEntityAi()
        target = null
    }

    /** 回る向きのすり足のアニメにする */
    private fun strafe(boss: Boss) {
        val anim = if (side > 0) settings.rightAnimation else settings.leftAnimation
        if (anim == loopAnimation) return
        loopAnimation?.let { boss.stopAnimation(it) }
        boss.playLoop(anim, lerpTicks = 5)
        loopAnimation = anim
    }

    private fun nextStepDelay(): Int = Random.nextInt(settings.stepIntervalMin, settings.stepIntervalMax + 1)

    private companion object {
        /** 壁・段差を調べる刻み（ブロック） */
        const val MAX_STEP = 0.5
    }
}
