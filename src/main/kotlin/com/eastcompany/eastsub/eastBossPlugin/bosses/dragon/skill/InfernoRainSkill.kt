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
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 業火の雨（本気のときだけ）。高く昇り、戦いの場の上をゆっくり回りながら火球を降らせ続ける。
 * その後は力を使い果たして低く降り、しばらく動けない。
 *
 *   上昇（climb-ticks まで）… 標的たちの真ん中の上、高さ rain-height へ昇る
 *   詠唱（cast-ticks）      … interval-ticks ごとに fireballs-per-wave 個の火球を降らせる（一部は標的の足元を狙う）
 *   降下（descend-ticks）   … 低く（exhausted-height）降りる
 *   疲れ果てる（exhausted-ticks）… 低く浮いたまま息を切らす（殴りどき。受けるダメージ × exhausted-damage-multiplier）
 *   立ち直る（exhausted-end-ticks）
 *
 * 詠唱の間は高い所にいるので、弓で撃ち続ければ撃ち落とせる（DragonBoss の撃ち落とし）
 */
class InfernoRainSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val rainHeight: Double,
        val climbTicks: Int,
        val climbAnimation: String,
        val loopAnimation: String,
        val castTicks: Int,
        val intervalTicks: Int,
        val fireballsPerWave: Int,
        val areaRadius: Double,
        val aimRatio: Double,
        val castDamageMultiplier: Double,
        val descendTicks: Int,
        val exhaustedHeight: Double,
        val exhaustedAnimation: String,
        val exhaustedTicks: Int,
        val exhaustedEndAnimation: String,
        val exhaustedEndTicks: Int,
        val exhaustedDamageMultiplier: Double,
        val fireball: DragonFireballs.Spec,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 24.0),
                rainHeight = section.double(Names.SkillConfig.RAIN_HEIGHT, 15.0),
                climbTicks = section.int(Names.SkillConfig.CLIMB_TICKS, 40),
                climbAnimation = section.string(Names.SkillConfig.CLIMB_ANIMATION, "dive_climb"),
                loopAnimation = section.string(Names.SkillConfig.LOOP_ANIMATION, "inferno_cast"),
                castTicks = section.int(Names.SkillConfig.CAST_TICKS, 160),
                intervalTicks = section.int(Names.SkillConfig.INTERVAL_TICKS, 8).coerceAtLeast(1),
                fireballsPerWave = section.int(Names.SkillConfig.FIREBALLS_PER_WAVE, 2).coerceAtLeast(1),
                areaRadius = section.double(Names.SkillConfig.AREA_RADIUS, 14.0),
                aimRatio = section.double(Names.SkillConfig.AIM_RATIO, 0.5).coerceIn(0.0, 1.0),
                castDamageMultiplier = section.double(Names.SkillConfig.CAST_DAMAGE_MULTIPLIER, 1.0),
                descendTicks = section.int(Names.SkillConfig.DESCEND_TICKS, 30),
                exhaustedHeight = section.double(Names.SkillConfig.EXHAUSTED_HEIGHT, 1.3),
                exhaustedAnimation = section.string(Names.SkillConfig.EXHAUSTED_ANIMATION, "exhausted"),
                exhaustedTicks = section.int(Names.SkillConfig.EXHAUSTED_TICKS, 100),
                exhaustedEndAnimation = section.string(Names.SkillConfig.EXHAUSTED_END_ANIMATION, "exhausted_end"),
                exhaustedEndTicks = section.int(Names.SkillConfig.EXHAUSTED_END_TICKS, 16),
                exhaustedDamageMultiplier = section.double(Names.SkillConfig.EXHAUSTED_DAMAGE_MULTIPLIER, 1.5),
                fireball = DragonFireballs.Spec.load(section.section(Names.SkillConfig.FIREBALL)),
            )
        }
    }

    private enum class Phase { CLIMB, CAST, DESCEND, EXHAUSTED, RECOVER }

    override val weight: Int get() = settings.weight

    private var phase = Phase.CLIMB
    private var tick = 0
    private lateinit var center: Location
    private var circleAngle = 0.0
    private var loop: String? = null

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val d = boss as DragonBoss
        // 標的たちの真ん中（召喚地点から離れすぎない所）
        val targets = targets(d)
        val pos = d.entity.location
        center = if (targets.isEmpty()) pos.clone() else {
            val c = targets.fold(Vector()) { acc, l -> acc.add(l.toVector()) }.multiply(1.0 / targets.size)
            d.clampToHome(Location(pos.world, c.x, c.y, c.z))
        }
        circleAngle = Math.atan2(pos.z - center.z, pos.x - center.x)
        d.setBaseAnimation(DragonBoss.HOVER)
        enter(d, Phase.CLIMB)
        DragonSounds.roar(d.entity, 0.75f)
    }

    override fun tick(boss: Boss): Boolean {
        val d = boss as DragonBoss
        when (phase) {
            Phase.CLIMB -> {
                val dest = d.flight.above(center.x, center.z, settings.rainHeight, center.y)
                d.flight.turnToVelocity(6f)
                d.flight.steer(dest, 0.75, accel = 0.1, arriveRadius = 3.0)
                if (tick % 12 == 0) DragonSounds.flap(d.entity, heavy = true)
                if (tick >= settings.climbTicks || dest.distanceSquared(d.flight.position) < 2.0 * 2.0) return enter(d, Phase.CAST)
            }
            Phase.CAST -> {
                // 真ん中の上をゆっくり回る
                circleAngle += 0.02
                val dest = d.flight.above(center.x + cos(circleAngle) * 4, center.z + sin(circleAngle) * 4, settings.rainHeight, center.y)
                d.flight.turnToward(center, 3f)
                d.flight.steer(dest, 0.2, accel = 0.04, arriveRadius = 2.0)
                if (tick % settings.intervalTicks == 0) repeat(settings.fireballsPerWave) { rain(d) }
                if (tick % 20 == 0) DragonSounds.play(d.entity, Sound.ENTITY_BLAZE_SHOOT, 3.0f, 0.5f)
                if (tick % 40 == 20) DragonSounds.growl(d.entity, 0.7f)
                if (tick >= settings.castTicks) return enter(d, Phase.DESCEND)
            }
            Phase.DESCEND -> {
                val pos = d.entity.location
                val dest = d.flight.above(pos.x, pos.z, settings.exhaustedHeight, center.y)
                d.flight.steer(dest, 0.6, accel = 0.08, arriveRadius = 3.0)
                if (tick >= settings.descendTicks) return enter(d, Phase.EXHAUSTED)
            }
            Phase.EXHAUSTED -> {
                val pos = d.entity.location
                val dest = d.flight.above(pos.x, pos.z, settings.exhaustedHeight, pos.y - d.flight.altitude())
                d.flight.steer(dest, 0.15, accel = 0.04, arriveRadius = 1.0)
                if (tick % 5 == 0) d.mouthPoint()?.let { DragonFx.nostrilSmoke(it, heavy = true) }
                if (tick % 16 == 0) DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 2.0f, 0.5f)
                if (tick >= settings.exhaustedTicks) return enter(d, Phase.RECOVER)
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
        val d = boss as DragonBoss
        loop?.let { d.stopAnimation(it) }
        loop = null
        d.showFire(false)
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        event.damage *= when (phase) {
            Phase.CLIMB, Phase.CAST -> settings.castDamageMultiplier
            Phase.EXHAUSTED -> settings.exhaustedDamageMultiplier
            else -> 1.0
        }
    }

    private fun enter(d: DragonBoss, p: Phase): Boolean {
        phase = p
        tick = 0
        when (p) {
            Phase.CLIMB -> setLoop(d, settings.climbAnimation)
            Phase.CAST -> {
                setLoop(d, settings.loopAnimation)
                d.showFire(true)
                DragonSounds.roar(d.entity, 0.8f)
            }
            Phase.DESCEND -> {
                setLoop(d, settings.exhaustedAnimation)
                d.showFire(false)
                DragonSounds.play(d.entity, Sound.ENTITY_ENDER_DRAGON_HURT, 3.0f, 0.6f)
            }
            Phase.EXHAUSTED -> {}
            Phase.RECOVER -> {
                setLoop(d, null)
                d.playOnce(settings.exhaustedEndAnimation)
                DragonSounds.flap(d.entity, heavy = true)
            }
        }
        return false
    }

    private fun setLoop(d: DragonBoss, name: String?) {
        loop?.let { d.stopAnimation(it) }
        loop = name
        if (name != null) d.playLoop(name, lerpTicks = 4)
    }

    private fun targets(d: DragonBoss): List<Location> =
        d.entity.getNearbyEntities(settings.range, settings.range, settings.range)
            .filter { TargetFinder.isTargetable(d, it) }
            .map { it.location }

    /** 火球を1つ降らせる（一部は標的の足元、残りは範囲のどこか） */
    private fun rain(d: DragonBoss) {
        val point = if (Random.nextDouble() < settings.aimRatio) {
            targets(d).randomOrNull()?.clone()?.add(Random.nextDouble(-1.5, 1.5), 0.0, Random.nextDouble(-1.5, 1.5))
        } else null
        val base = point ?: run {
            val r = settings.areaRadius * sqrt(Random.nextDouble())
            val a = Random.nextDouble(Math.PI * 2)
            center.clone().add(cos(a) * r, 0.0, sin(a) * r)
        }
        val aim = d.groundAt(base.x, base.z, base.y)
        // 口から、少しばらけて出す
        val from = d.mouthOrFront().add(Random.nextDouble(-0.6, 0.6), Random.nextDouble(-0.3, 0.3), Random.nextDouble(-0.6, 0.6))
        d.fireballs.launch(from, aim, settings.fireball)
    }
}
