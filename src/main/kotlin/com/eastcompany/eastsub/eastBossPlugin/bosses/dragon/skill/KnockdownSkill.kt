package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import net.kyori.adventure.text.Component
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * 撃ち落とされる（抽選では選ばれない。DragonBoss が今の技を止めて始める）。
 * 高い所で撃たれ続けて体勢を崩し、翼をばたつかせながら低く落ちて、しばらく動けない。
 *
 *   落ちる（fall-ticks）           … 低く（exhausted-height）まで落ちる。地面すれすれで土ぼこり
 *   動けない（exhausted-ticks）     … 低く浮いたまま息を切らす（殴りどき。受けるダメージ × exhausted-damage-multiplier）
 *   立ち直る（exhausted-end-ticks）
 */
class KnockdownSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val animation: String,
        val fallTicks: Int,
        val exhaustedHeight: Double,
        val exhaustedAnimation: String,
        val exhaustedTicks: Int,
        val exhaustedEndAnimation: String,
        val exhaustedEndTicks: Int,
        val exhaustedDamageMultiplier: Double,
        /** 撃ち落としたときに、戦っている人のアクションバーに出す */
        val message: Component,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                animation = section.string(Names.SkillConfig.ANIMATION, "knockdown"),
                fallTicks = section.int(Names.SkillConfig.FALL_TICKS, 20).coerceAtLeast(1),
                exhaustedHeight = section.double(Names.SkillConfig.EXHAUSTED_HEIGHT, 1.3),
                exhaustedAnimation = section.string(Names.SkillConfig.EXHAUSTED_ANIMATION, "exhausted"),
                exhaustedTicks = section.int(Names.SkillConfig.EXHAUSTED_TICKS, 90),
                exhaustedEndAnimation = section.string(Names.SkillConfig.EXHAUSTED_END_ANIMATION, "exhausted_end"),
                exhaustedEndTicks = section.int(Names.SkillConfig.EXHAUSTED_END_TICKS, 16),
                exhaustedDamageMultiplier = section.double(Names.SkillConfig.EXHAUSTED_DAMAGE_MULTIPLIER, 1.4),
                message = section.file.component(
                    section.fullKey(Names.SkillConfig.MESSAGE),
                    def = "<gold><bold>撃ち落とした！</bold></gold> <yellow>今のうちに叩け",
                ),
            )
        }
    }

    private enum class Phase { FALL, DOWN, RECOVER }

    /** 抽選では選ばれない */
    override val weight: Int get() = 0

    private var phase = Phase.FALL
    private var tick = 0
    private var looping = false
    private var crashed = false

    override fun canStart(boss: Boss): Boolean = true

    override fun start(boss: Boss) {
        val d = boss as DragonBoss
        phase = Phase.FALL
        tick = 0
        crashed = false
        d.setBaseAnimation(DragonBoss.HOVER)
        d.showFire(false)
        d.playOnce(settings.animation)
        DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_HURT, 4.0f, 0.6f)
        DragonSounds.play(d.entity, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.4f)
        val at = d.entity.location.add(0.0, d.entity.height * 0.6, 0.0)
        at.world.spawnParticle(Particle.EXPLOSION, at, 3, 1.5, 0.5, 1.5, 0.0)
        at.world.spawnParticle(Particle.LARGE_SMOKE, at, 20, 1.5, 0.6, 1.5, 0.05)
    }

    override fun tick(boss: Boss): Boolean {
        val d = boss as DragonBoss
        val pos = d.entity.location
        val ground = pos.y - d.flight.altitude()
        val dest = d.flight.above(pos.x, pos.z, settings.exhaustedHeight, ground)
        when (phase) {
            Phase.FALL -> {
                d.flight.steer(dest, 1.0, accel = 0.15, arriveRadius = 2.0)
                d.entity.world.spawnParticle(Particle.LARGE_SMOKE, pos.clone().add(0.0, d.entity.height * 0.5, 0.0), 2, 0.8, 0.4, 0.8, 0.02)
                if (!crashed && d.flight.altitude() <= settings.exhaustedHeight + 0.6) {
                    crashed = true
                    DragonFx.groundShock(d.groundAt(pos.x, pos.z), 3.0)
                    DragonSounds.play(d.entity, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.6f)
                }
                if (tick >= settings.fallTicks) {
                    phase = Phase.DOWN
                    tick = 0
                    d.playLoop(settings.exhaustedAnimation, lerpTicks = 4)
                    looping = true
                    return false
                }
            }
            Phase.DOWN -> {
                d.flight.steer(dest, 0.15, accel = 0.04, arriveRadius = 1.0)
                if (tick % 5 == 0) d.mouthPoint()?.let { DragonFx.nostrilSmoke(it, heavy = true) }
                if (tick % 16 == 0) DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 2.0f, 0.5f)
                if (tick >= settings.exhaustedTicks) {
                    phase = Phase.RECOVER
                    tick = 0
                    d.stopAnimation(settings.exhaustedAnimation)
                    looping = false
                    d.playOnce(settings.exhaustedEndAnimation)
                    DragonSounds.growl(d.entity, 0.9f)
                    return false
                }
            }
            Phase.RECOVER -> {
                d.flight.brake()
                if (tick >= settings.exhaustedEndTicks) return true
            }
        }
        tick++
        return false
    }

    override fun stop(boss: Boss) {
        if (looping) (boss as DragonBoss).stopAnimation(settings.exhaustedAnimation)
        looping = false
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (phase != Phase.RECOVER) event.damage *= settings.exhaustedDamageMultiplier
    }
}
