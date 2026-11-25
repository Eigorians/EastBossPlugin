package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 影縫い: 標的の足元（動きを見越した所）めがけて苦無を投げ下ろす。刺さる所には投げた瞬間から紫の輪が出る。
 * 刺さったとき輪の中（bindRadius 以内）にいたプレイヤーは、影を縫いとめられて stunTicks の間動けない。
 * 誰かを縫いとめたら、follow-up が true なら続けて連撃で斬りかかる（[AssassinBoss.followUpCombo]）。
 *
 * count が 2 以上なら、2本目からは標的のまわり spreadRadius 以内にばらまく（本気）。
 */
class ShadowBindSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val minRange: Double,
        val releaseTick: Int,
        val flightTicks: Int,
        val arcHeight: Double,
        val count: Int,
        val spreadRadius: Double,
        val leadRatio: Double,
        val bindRadius: Double,
        val stunTicks: Int,
        val damage: Double,
        val followUp: Boolean,
        val endTicks: Int,
        val bindMessage: Component,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                animation = section.string(Names.SkillConfig.ANIMATION, "bind_throw"),
                range = section.double(Names.SkillConfig.RANGE, 14.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 3.0),
                releaseTick = section.int(Names.SkillConfig.RELEASE_TICKS, 8),
                flightTicks = section.int(Names.SkillConfig.FLIGHT_TICKS, 12).coerceAtLeast(1),
                arcHeight = section.double(Names.SkillConfig.ARC_HEIGHT, 3.0),
                count = section.int(Names.SkillConfig.COUNT, 1).coerceAtLeast(1),
                spreadRadius = section.double(Names.SkillConfig.SPREAD_RADIUS, 2.5),
                leadRatio = section.double(Names.SkillConfig.LEAD_RATIO, 0.6),
                bindRadius = section.double(Names.SkillConfig.BIND_RADIUS, 1.6),
                stunTicks = section.int(Names.SkillConfig.STUN_TICKS, 30),
                damage = section.double(Names.SkillConfig.DAMAGE, 3.0),
                followUp = section.boolean(Names.SkillConfig.FOLLOW_UP, true),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 24),
                bindMessage = section.file.component(
                    section.fullKey(Names.SkillConfig.BIND_MESSAGE),
                    def = "<dark_purple><bold>影を縫いとめられた！",
                ),
            )
        }
    }

    /** 飛んでいる・刺さった苦無 */
    private class Pin(val from: Location, val land: Location) {
        var landed = false
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private val pins = mutableListOf<Pin>()
    private var boundAny = false

    override fun canStart(boss: Boss): Boolean = findTarget(boss) != null

    private fun findTarget(boss: Boss): LivingEntity? {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return null
        return if (t.location.distanceSquared(boss.entity.location) < settings.minRange * settings.minRange) null else t
    }

    override fun start(boss: Boss) {
        val a = boss as AssassinBoss
        tick = 0
        pins.clear()
        boundAny = false
        target = findTarget(a)
        Facing.faceTarget(a, target)
        a.holdKunai(true)
        a.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        val s = settings
        val t = target?.takeIf { TargetFinder.isValidTarget(a, it) }
        if (tick < s.releaseTick) Facing.faceTarget(a, t)
        if (tick == s.releaseTick) release(a, t)
        if (tick == s.releaseTick + 2) a.holdKunai(false)

        if (tick > s.releaseTick) {
            val f = (tick - s.releaseTick).toDouble() / s.flightTicks
            for (pin in pins) {
                if (!pin.landed) {
                    // 刺さる所の輪（だんだん赤く）と、放物線を描いて落ちる苦無
                    if (tick % 2 == 0) AssassinFx.ring(pin.land, s.bindRadius, if (f > 0.6) AssassinFx.WARN_HOT else AssassinFx.WARN)
                    val p = pin.from.clone().add(pin.land.toVector().subtract(pin.from.toVector()).multiply(f.coerceAtMost(1.0)))
                    p.add(0.0, s.arcHeight * sin(PI * f.coerceAtMost(1.0)), 0.0)
                    AssassinFx.kunaiTrail(p)
                    if (f >= 1.0) land(a, pin)
                } else {
                    // 刺さった苦無から、縫いとめた影へ黒い糸
                    pin.land.world.spawnParticle(Particle.DUST, pin.land.clone().add(0.0, 0.2, 0.0), 2, 0.05, 0.1, 0.05, 0.0, AssassinFx.STEEL)
                    AssassinFx.shadow(pin.land, 0.5)
                }
            }
        }

        tick++
        if (tick < s.endTicks) return false
        // 縫いとめたら、そのまま斬りかかる
        if (boundAny && s.followUp && !a.isDying) a.queueSkill(a.followUpCombo())
        return true
    }

    override fun stop(boss: Boss) {
        (boss as AssassinBoss).holdKunai(false)
        pins.clear()
        target = null
    }

    private fun release(a: AssassinBoss, t: LivingEntity?) {
        val from = a.bonePoint(AssassinBoss.KUNAI_BONE) ?: a.entity.location.add(0.0, a.entity.height * 0.8, 0.0)
        val center = if (t != null) {
            val ahead = t.velocity.clone().setY(0).multiply(settings.flightTicks * settings.leadRatio)
            t.location.add(ahead)
        } else a.entity.location.add(Facing.forward(a.entity).multiply(6.0))
        for (i in 0 until settings.count) {
            val at = if (i == 0) center.clone() else {
                val ang = Random.nextDouble(2 * PI)
                val r = settings.spreadRadius * (0.5 + 0.5 * Random.nextDouble())
                center.clone().add(cos(ang) * r, 0.0, sin(ang) * r)
            }
            val ground = Ground.step(at, maxDrop = 4) ?: at
            pins += Pin(from.clone(), ground)
        }
        AssassinSounds.throwKunai(a.entity)
    }

    private fun land(a: AssassinBoss, pin: Pin) {
        pin.landed = true
        val at = pin.land
        val world = at.world
        AssassinSounds.pin(at)
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.1, 0.0), 10, 0.2, 0.05, 0.2, 0.0, below.blockData)
        world.spawnParticle(Particle.CRIT, at.clone().add(0.0, 0.2, 0.0), 10, 0.2, 0.1, 0.2, 0.2)
        AssassinFx.ring(at, settings.bindRadius, AssassinFx.WARN_HOT, 30)
        val r = settings.bindRadius
        for (e in world.getNearbyEntities(at, r, 2.0, r)) {
            if (e !is Player || !TargetFinder.isValidTarget(a, e)) continue
            val dx = e.location.x - at.x
            val dz = e.location.z - at.z
            if (dx * dx + dz * dz > r * r || e.location.y - at.y > 1.5) continue
            a.status.stun.stun(e, settings.stunTicks)
            if (settings.damage > 0) {
                e.noDamageTicks = 0
                e.damage(settings.damage, a.entity)
            }
            e.sendActionBar(settings.bindMessage)
            AssassinFx.shadow(e.location, 0.9)
            AssassinSounds.bound(e.location)
            boundAny = true
        }
    }
}
