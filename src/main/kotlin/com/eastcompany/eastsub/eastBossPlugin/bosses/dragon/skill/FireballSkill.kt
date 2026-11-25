package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonFireballs
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.random.Random

/**
 * 火球。高めに浮いて、首を引いては口から燃える岩を吐き出す（shoot-ticks の数だけ）。
 *
 *   接近（approach-max-ticks まで）… 標的から hover-distance 離れた、高さ hover-height の所へ飛ぶ
 *   撃つ                           … shoot-ticks（接近の後からの tick）ごとに1発。アニメ spit は吐く 7tick 前から流す
 *   終わり                         … 最後の1発から end-ticks
 *
 * - 狙いは標的の足元。動いている相手には、進んでいる先（lead-ratio）へ撃つ。着く所には予告の輪が出る
 * - 着弾すると爆発し、その場所はしばらく燃える（fireball セクション）
 */
class FireballSkill(
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
        val shootTicks: List<Int>,
        /** 動いている標的の、進む先をどれだけ狙うか（1 で着弾までに進む所ちょうど） */
        val leadRatio: Double,
        /** 狙いのぶれ（ブロック） */
        val spreadRadius: Double,
        val endTicks: Int,
        val fireball: DragonFireballs.Spec,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 30.0),
                hoverHeight = section.double(Names.SkillConfig.HOVER_HEIGHT, 6.0),
                hoverDistance = section.double(Names.SkillConfig.HOVER_DISTANCE, 12.0),
                approachSpeed = section.double(Names.SkillConfig.APPROACH_SPEED, 0.55),
                approachMaxTicks = section.int(Names.SkillConfig.APPROACH_MAX_TICKS, 30),
                animation = section.string(Names.SkillConfig.ANIMATION, "spit"),
                shootTicks = section.stringList(Names.SkillConfig.SHOOT_TICKS, listOf("10", "24", "38"))
                    .mapNotNull { it.trim().toIntOrNull() }.sorted(),
                leadRatio = section.double(Names.SkillConfig.LEAD_RATIO, 0.6),
                spreadRadius = section.double(Names.SkillConfig.SPREAD_RADIUS, 1.0),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 20),
                fireball = DragonFireballs.Spec.load(section.section(Names.SkillConfig.FIREBALL)),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var approaching = true
    private var tick = 0
    private var target: LivingEntity? = null
    private var lastTargetPos: Vector? = null
    private var targetVelocity = Vector()

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val d = boss as DragonBoss
        approaching = true
        tick = 0
        target = d.findTarget(settings.range)
        lastTargetPos = null
        targetVelocity = Vector()
        d.setBaseAnimation(DragonBoss.HOVER)
    }

    override fun tick(boss: Boss): Boolean {
        val d = boss as DragonBoss
        // 狙う相手（いなくなったら、近くの別の相手）
        val t = target?.takeIf { TargetFinder.isValidTarget(d, it) } ?: d.findTarget(settings.range)?.also { target = it }
        trackVelocity(t)

        if (approaching) {
            if (t == null || tick >= settings.approachMaxTicks) return begin(d)
            val dest = d.hoverPointNear(t, settings.hoverDistance, settings.hoverHeight)
            d.flight.turnToward(t.location, 8f)
            d.flight.steer(dest, settings.approachSpeed, accel = 0.07, arriveRadius = 3.0)
            if (dest.distanceSquared(d.flight.position) < 2.0 * 2.0) return begin(d)
            tick++
            return false
        }

        t?.let { d.flight.turnToward(it.location, 7f) }
        d.flight.brake()
        if (settings.shootTicks.any { it - SPIT_LEAD == tick }) d.playOnce(settings.animation)
        if (tick in settings.shootTicks) shoot(d, t)

        tick++
        return tick >= (settings.shootTicks.lastOrNull() ?: 0) + settings.endTicks
    }

    override fun stop(boss: Boss) {
        (boss as DragonBoss).showFire(false)
        target = null
    }

    private fun begin(d: DragonBoss): Boolean {
        approaching = false
        tick = 0
        d.showFire(true)
        DragonSounds.growl(d.entity, 0.9f)
        return false
    }

    private fun trackVelocity(t: LivingEntity?) {
        val now = t?.location?.toVector() ?: return
        val prev = lastTargetPos
        lastTargetPos = now
        if (prev != null) {
            // 少しならして、急な動きに振り回されないようにする
            val v = now.clone().subtract(prev).setY(0)
            targetVelocity = targetVelocity.multiply(0.6).add(v.multiply(0.4))
        }
    }

    private fun shoot(d: DragonBoss, t: LivingEntity?) {
        val mouth = d.mouthOrFront()
        val base = t?.location ?: d.entity.location.add(d.flight.forward().multiply(settings.hoverDistance))
        // 着弾までにかかる時間のぶん、進む先を狙う
        val travel = mouth.distance(base) / settings.fireball.speed
        val lead = targetVelocity.clone().multiply(travel * settings.leadRatio)
        val a = Random.nextDouble(Math.PI * 2)
        val r = Random.nextDouble(settings.spreadRadius)
        val x = base.x + lead.x + Math.cos(a) * r
        val z = base.z + lead.z + Math.sin(a) * r
        val aim = d.groundAt(x, z, base.y)
        d.fireballs.launch(mouth, aim, settings.fireball)
        DragonSounds.play(mouth, Sound.ENTITY_GHAST_SHOOT, 2.5f, 0.6f)
        DragonFx.mawGlow(mouth)
    }

    private companion object {
        /** アニメ spit で、口から吐くまでの tick */
        const val SPIT_LEAD = 7
    }
}
