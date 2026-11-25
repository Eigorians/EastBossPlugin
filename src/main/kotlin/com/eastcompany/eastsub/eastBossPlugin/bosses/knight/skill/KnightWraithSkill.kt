package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 怨霊: 左手を突き出し、体から怨霊（魂の弾）を次々に放つ。怨霊は左右へ弧を描いて飛び出し、
 * ゆっくり向きを変えながら相手を追う（KnightHazards.wraith）。相手が離れているとき（min-range 以上）だけ使う。
 *
 *   releaseTicks の各 tick … count 体ずつ放つ（左右交互に、横へ spread-degrees の範囲で開いて、少し上へ）
 *   endTicks               … 終わり
 */
class KnightWraithSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val minRange: Double,
        val releaseTicks: List<Int>,
        val count: Int,
        val spreadDegrees: Double,
        val launchSpeed: Double,
        val speed: Double,
        val turnDegrees: Double,
        val maxLifeTicks: Int,
        val damage: Double,
        val hitRadius: Double,
        val endTicks: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION, "cast"),
                range = section.double(Names.SkillConfig.RANGE, 30.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 9.0),
                releaseTicks = section.stringList(Names.SkillConfig.RELEASE_TICKS).mapNotNull { it.trim().toIntOrNull() }
                    .ifEmpty { listOf(10, 16, 22, 28, 34) },
                count = section.int(Names.SkillConfig.COUNT, 1).coerceAtLeast(1),
                spreadDegrees = section.double(Names.SkillConfig.SPREAD_DEGREES, 70.0),
                launchSpeed = section.double(Names.SkillConfig.LAUNCH_SPEED, 0.8),
                speed = section.double(Names.SkillConfig.SPEED, 0.32),
                turnDegrees = section.double(Names.SkillConfig.TURN_DEGREES, 4.0),
                maxLifeTicks = section.int(Names.SkillConfig.MAX_LIFE_TICKS, 160),
                damage = section.double(Names.SkillConfig.DAMAGE, 5.0),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 0.8),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 44),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var side = 1

    override fun canStart(boss: Boss): Boolean {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return false
        return hypot(t.location.x - boss.entity.location.x, t.location.z - boss.entity.location.z) >= settings.minRange
    }

    override fun start(boss: Boss) {
        tick = 0
        side = if (Random.nextBoolean()) 1 else -1
        boss.entity.setAI(false)
        Facing.faceTarget(boss, TargetFinder.findNearest(boss, settings.range))
        boss.playOnce(settings.animation)
        KnightSounds.wraithCast(boss.entity)
    }

    override fun tick(boss: Boss): Boolean {
        val k = boss as KnightBoss
        val target = TargetFinder.findNearest(boss, settings.range)
        if (target != null) Facing.faceTarget(boss, target)
        if (tick % 3 == 0) KnightFx.wraithGather(boss.entity)
        if (tick in settings.releaseTicks) repeat(settings.count) { release(k, target) }
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        boss.resumeEntityAi()
    }

    /** 体（胸の高さ）から、横へ弧を描くように1体放つ。狙う相手は参加者からランダム（いなければ一番近い人） */
    private fun release(boss: KnightBoss, nearest: LivingEntity?) {
        val entity = boss.entity
        val forward = Facing.forward(entity)
        val angle = (40.0 + Random.nextDouble() * settings.spreadDegrees) * side
        side = -side
        val dir = forward.clone().rotateAroundY(Math.toRadians(angle)).add(Vector(0.0, 0.35 + Random.nextDouble() * 0.4, 0.0))
            .normalize()
        val from = entity.location.add(0.0, entity.height * 0.6, 0.0).add(dir.clone().multiply(0.8))
        val target = boss.randomTarget(settings.range) ?: nearest
        boss.hazards.wraith(from, dir.multiply(settings.launchSpeed), target, settings.speed, settings.turnDegrees,
            settings.maxLifeTicks, settings.damage, settings.hitRadius)
        KnightFx.wraithRelease(from)
        KnightSounds.wraithRelease(from)
    }
}
