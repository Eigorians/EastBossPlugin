package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.model.BoneKinematics
import com.eastcompany.eastsub.eastBossPlugin.model.SwordSweep
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.max

/**
 * 尻尾の薙ぎ払い。標的のそばに低く降りてきて、体をひねって溜め、その場で一回転して尻尾で払う。
 *
 *   接近（approach-max-ticks まで）… 標的の hover-distance 先、高さ hover-height（低い。殴れる）へ飛ぶ
 *   薙ぎ払い（end-ticks まで）      … アニメに合わせて、尻尾のボーン（bones）が通った所に当たる（hit-windows）
 *
 * - 尻尾は体から離れた所を回るので、ドラゴンの真下・すぐそばにもぐり込めば当たらない
 * - 当たると大きく吹き飛ぶ
 * - hit-windows を2つ書くと、2回払う（本気: 一回転して、逆向きにもう一回転）
 */
class TailSwipeSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val hoverHeight: Double,
        val hoverDistance: Double,
        val approachSpeed: Double,
        val approachMaxTicks: Int,
        val animation: String,
        val bones: List<String>,
        val minBladeRadius: Double,
        val hitWindows: List<IntRange>,
        val damage: Double,
        val knockback: Double,
        val endTicks: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 16.0),
                hoverHeight = section.double(Names.SkillConfig.HOVER_HEIGHT, 1.8),
                hoverDistance = section.double(Names.SkillConfig.HOVER_DISTANCE, 2.5),
                approachSpeed = section.double(Names.SkillConfig.APPROACH_SPEED, 0.75),
                approachMaxTicks = section.int(Names.SkillConfig.APPROACH_MAX_TICKS, 40),
                animation = section.string(Names.SkillConfig.ANIMATION, "tail_swipe"),
                bones = section.stringList(Names.SkillConfig.BONES, listOf("tail3", "tail4", "tail5")),
                minBladeRadius = section.double(Names.SkillConfig.MIN_BLADE_RADIUS, 0.7),
                hitWindows = section.intRanges(Names.SkillConfig.HIT_WINDOWS, listOf(12..21)),
                damage = section.double(Names.SkillConfig.DAMAGE, 10.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 1.6),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 46),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var approaching = true
    private var tick = 0
    private var target: LivingEntity? = null
    private val sweeps = HashMap<String, SwordSweep>()
    /** 判定区間ごとのヒット履歴（区間ごとに1人1回） */
    private val hit = HashSet<UUID>()
    private val warned = HashSet<String>()

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val d = boss as DragonBoss
        approaching = true
        tick = 0
        hit.clear()
        target = d.findTarget(settings.range)
        d.setBaseAnimation(DragonBoss.HOVER)
        sweeps.clear()
        for (name in settings.bones) {
            val radius = d.findBone(name)?.let { BoneKinematics.bladeRadiusOf(it) } ?: 0.0
            sweeps[name] = SwordSweep(radius = max(radius, settings.minBladeRadius), debug = { d.debugHitbox() })
        }
        DragonSounds.growl(d.entity, 1.0f)
    }

    override fun tick(boss: Boss): Boolean {
        val d = boss as DragonBoss
        val t = target?.takeIf { TargetFinder.isValidTarget(d, it) }
        if (approaching) {
            if (t == null || tick >= settings.approachMaxTicks) return beginSwipe(d)
            val dest = d.hoverPointNear(t, settings.hoverDistance, settings.hoverHeight)
            d.flight.turnToward(t.location, 9f)
            d.flight.steer(dest, settings.approachSpeed, accel = 0.09, arriveRadius = 2.5)
            if (dest.distanceSquared(d.flight.position) < 1.2 * 1.2) return beginSwipe(d)
            tick++
            return false
        }

        d.flight.brake(0.08)
        for ((i, window) in settings.hitWindows.withIndex()) {
            if (tick == window.first) {
                hit.clear()
                DragonSounds.play(d.entity, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 3.0f, 0.5f)
                DragonSounds.play(d.entity, Sound.ENTITY_PHANTOM_SWOOP, 3.0f, 0.8f + i * 0.1f)
            }
            if (tick == window.first - 1 || tick in window) strike(d, prime = tick == window.first - 1)
        }
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        sweeps.values.forEach { it.reset() }
        target = null
    }

    private fun beginSwipe(d: DragonBoss): Boolean {
        approaching = false
        tick = 0
        d.playOnce(settings.animation)
        DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_GROWL, 3.5f, 1.3f)
        return false
    }

    /** 尻尾のボーンの通った所に当てる（prime なら姿勢を覚えるだけ） */
    private fun strike(d: DragonBoss, prime: Boolean) {
        val origin = d.entity.location
        for ((name, sweep) in sweeps) {
            val bone = d.findBone(name) ?: run { warnOnce(d, name, "ボーン $name が見つかりません"); continue }
            val frame = SwordSweep.frameOf(bone, origin)
                ?: run { warnOnce(d, name, "$name の直下にキューブがありません"); continue }
            if (prime) {
                sweep.reset()
                sweep.prime(frame)
                continue
            }
            // 尻尾の先が地面をこする砂ぼこり
            if (name == settings.bones.lastOrNull()) {
                val tip = frame.pointB()
                origin.world.spawnParticle(Particle.CLOUD, tip.x.toDouble(), tip.y.toDouble(), tip.z.toDouble(), 2, 0.3, 0.1, 0.3, 0.02)
            }
            val victims = sweep.detect(origin.world, frame, hit) { TargetFinder.isValidTarget(d, it) }
            for (victim in victims) {
                victim.noDamageTicks = 0
                victim.damage(settings.damage, d.entity)
                val away = Vector(victim.location.x - origin.x, 0.0, victim.location.z - origin.z)
                if (away.lengthSquared() > 1e-4) victim.velocity = away.normalize().multiply(settings.knockback).setY(0.55)
                DragonSounds.play(victim.location, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 2.0f, 0.6f)
            }
        }
    }

    private fun warnOnce(d: DragonBoss, key: String, message: String) {
        if (warned.add(key)) d.plugin.logger.warning("[${d.type.id}/$id] $message")
    }
}
