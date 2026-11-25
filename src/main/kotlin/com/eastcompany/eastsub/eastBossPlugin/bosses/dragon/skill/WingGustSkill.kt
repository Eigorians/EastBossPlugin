package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

/**
 * 翼の突風。近くにいる相手（真下にもぐり込んだ相手など）を、翼を強く打ち下ろして吹き飛ばす。
 *
 *   0 〜                … 翼を高く上げながら、高さ hover-height まで少し昇る
 *   gust-ticks の各 tick … 翼を打ち下ろす。真下の gust-radius 以内の相手を外へ吹き飛ばし、少しダメージ
 *   〜 end-ticks         … 体勢を戻す
 *
 * fire-ticks が 0 より大きいと、熱風になって当たった相手が燃える（本気）
 */
class WingGustSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val hoverHeight: Double,
        val animation: String,
        val gustTicks: List<Int>,
        val gustRadius: Double,
        val damage: Double,
        val knockback: Double,
        val fireTicks: Int,
        val endTicks: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 8.0),
                hoverHeight = section.double(Names.SkillConfig.HOVER_HEIGHT, 4.0),
                animation = section.string(Names.SkillConfig.ANIMATION, "gust"),
                gustTicks = section.stringList(Names.SkillConfig.GUST_TICKS, listOf("14", "30"))
                    .mapNotNull { it.trim().toIntOrNull() },
                gustRadius = section.double(Names.SkillConfig.GUST_RADIUS, 9.0),
                damage = section.double(Names.SkillConfig.DAMAGE, 3.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 1.5),
                fireTicks = section.int(Names.SkillConfig.FIRE_TICKS, 0),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 44),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val d = boss as DragonBoss
        tick = 0
        target = d.findTarget(settings.range)
        d.setBaseAnimation(DragonBoss.HOVER)
        d.playOnce(settings.animation)
        DragonSounds.growl(d.entity, 1.2f)
    }

    override fun tick(boss: Boss): Boolean {
        val d = boss as DragonBoss
        target?.takeIf { TargetFinder.isValidTarget(d, it) }?.let { d.flight.turnToward(it.location, 4f) }
        val pos = d.entity.location
        val dest = d.flight.above(pos.x, pos.z, settings.hoverHeight, pos.y - d.flight.altitude())
        d.flight.steer(dest, 0.3, accel = 0.05, arriveRadius = 1.5)
        if (tick in settings.gustTicks) gust(d)
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        target = null
    }

    private fun gust(d: DragonBoss) {
        val pos = d.entity.location
        val ground = d.groundAt(pos.x, pos.z)
        val world = ground.world
        DragonSounds.flap(d.entity, heavy = true)
        DragonSounds.play(ground, Sound.ENTITY_BREEZE_WIND_BURST, 3.0f, 0.5f)
        DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_FLAP, 5.0f, 0.55f)

        // 地面を外へ走る風
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, ground.clone().add(0.0, 0.3, 0.0), 1)
        val r = settings.gustRadius
        for (i in 0 until 32) {
            val a = Math.PI * 2 * i / 32
            val dir = Vector(cos(a), 0.0, sin(a))
            val at = ground.clone().add(dir.clone().multiply(1.5)).add(0.0, 0.3, 0.0)
            world.spawnParticle(Particle.CLOUD, at, 0, dir.x, 0.05, dir.z, r * 0.07)
            if (settings.fireTicks > 0) world.spawnParticle(Particle.FLAME, at, 0, dir.x, 0.05, dir.z, r * 0.06)
        }
        if (settings.fireTicks > 0) DragonFx.ring(ground, r * 0.5, DragonFx.EMBER)

        // 真下のまわり（ドラゴンより下）にいる相手を外へ
        for (e in world.getNearbyEntities(ground, r, d.flight.altitude() + 3.0, r)) {
            if (!TargetFinder.isValidTarget(d, e)) continue
            if (e.location.y > pos.y + 1.0) continue
            val dx = e.location.x - pos.x
            val dz = e.location.z - pos.z
            val dist = Math.hypot(dx, dz)
            if (dist > r) continue
            val victim = e as LivingEntity
            val away = if (dist < 1e-3) d.flight.forward() else Vector(dx / dist, 0.0, dz / dist)
            // 近いほど強く吹き飛ぶ
            val power = settings.knockback * (1.2 - 0.5 * dist / r)
            victim.noDamageTicks = 0
            if (settings.damage > 0) victim.damage(settings.damage, d.entity)
            victim.velocity = away.multiply(power).setY(0.55)
            if (settings.fireTicks > 0) victim.fireTicks = maxOf(victim.fireTicks, settings.fireTicks)
        }
    }
}
