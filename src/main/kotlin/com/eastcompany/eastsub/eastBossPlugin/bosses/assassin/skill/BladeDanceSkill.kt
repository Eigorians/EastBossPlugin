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
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * 影舞（本気のときだけ）: 標的を貫く線を予告し、その線に沿って一瞬で駆け抜ける一閃を dashes 回くり返す。
 * 最後は高く跳び上がって、標的のいた所へ両手の短刀を突き下ろす。その後は疲れ果てて片膝をつく（殴りどき）。
 *
 *   構え（1回目は firstWarningTicks、2回目からは warningTicks。紫の線が予告。最後の数 tick は赤く）
 *   → 一閃（dashTicks で線の端まで。線の幅 lineWidth に触れた相手を斬る。1回の一閃につき1人1回）
 *   → …… → 跳び上がり（finishLandTick で着地。着地の所に輪の予告）→ 着地の衝撃（impactRadius）
 *   → 疲れ果てる（exhaustedTicks。受けるダメージが exhaustedDamageMultiplier 倍）→ 立ち直る
 *
 * 線は構えた瞬間に固まるので、予告を見てから線の横へ逃げればかわせる。
 */
class BladeDanceSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val readyAnimation: String,
        val dashAnimation: String,
        val dashes: Int,
        val firstWarningTicks: Int,
        val warningTicks: Int,
        val dashTicks: Int,
        val overshoot: Double,
        val maxLength: Double,
        val lineWidth: Double,
        val damage: Double,
        val knockback: Double,
        val finishAnimation: String,
        val finishLandTick: Int,
        val finishTicks: Int,
        val finishHeight: Double,
        val impactRadius: Double,
        val impactDamage: Double,
        val launch: Double,
        val exhaustedAnimation: String,
        val exhaustedTicks: Int,
        val exhaustedEndAnimation: String,
        val exhaustedEndTicks: Int,
        val exhaustedDamageMultiplier: Double,
        val dashDamageMultiplier: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 16.0),
                readyAnimation = section.string(Names.SkillConfig.READY_ANIMATION, "dash_ready"),
                dashAnimation = section.string(Names.SkillConfig.DASH_ANIMATION, "dash"),
                dashes = section.int(Names.SkillConfig.DASHES, 5).coerceAtLeast(1),
                firstWarningTicks = section.int(Names.SkillConfig.FIRST_WARNING_TICKS, 16).coerceAtLeast(1),
                warningTicks = section.int(Names.SkillConfig.WARNING_TICKS, 9).coerceAtLeast(1),
                dashTicks = section.int(Names.SkillConfig.DASH_TICKS, 3).coerceAtLeast(1),
                overshoot = section.double(Names.SkillConfig.OVERSHOOT, 3.5),
                maxLength = section.double(Names.SkillConfig.LENGTH, 16.0),
                lineWidth = section.double(Names.SkillConfig.LINE_WIDTH, 1.1),
                damage = section.double(Names.SkillConfig.DAMAGE, 7.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.4),
                finishAnimation = section.string(Names.SkillConfig.FINISH_ANIMATION, "dance_finish"),
                finishLandTick = section.int(Names.SkillConfig.FINISH_LAND_TICK, 11).coerceAtLeast(1),
                finishTicks = section.int(Names.SkillConfig.FINISH_TICKS, 26),
                finishHeight = section.double(Names.SkillConfig.JUMP_HEIGHT, 5.0),
                impactRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 3.2),
                impactDamage = section.double(Names.SkillConfig.IMPACT_DAMAGE, 10.0),
                launch = section.double(Names.SkillConfig.LAUNCH, 0.7),
                exhaustedAnimation = section.string(Names.SkillConfig.EXHAUSTED_ANIMATION, "tired"),
                exhaustedTicks = section.int(Names.SkillConfig.EXHAUSTED_TICKS, 60),
                exhaustedEndAnimation = section.string(Names.SkillConfig.EXHAUSTED_END_ANIMATION, "tired_end"),
                exhaustedEndTicks = section.int(Names.SkillConfig.EXHAUSTED_END_TICKS, 10),
                exhaustedDamageMultiplier = section.double(Names.SkillConfig.EXHAUSTED_DAMAGE_MULTIPLIER, 1.5),
                dashDamageMultiplier = section.double(Names.SkillConfig.TAKEN_DAMAGE_MULTIPLIER, 0.5),
            )
        }
    }

    private enum class Phase { READY, DASH, FINISH, TIRED, TIRED_END }

    override val weight: Int get() = settings.weight

    private var phase = Phase.READY
    private var tick = 0
    private var dashIndex = 0
    private var target: LivingEntity? = null
    private var lineFrom: Location? = null
    private var lineTo: Location? = null
    private var finishFrom: Location? = null
    private var finishAt: Location? = null
    private val hit = HashSet<UUID>()

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val a = boss as AssassinBoss
        dashIndex = 0
        target = TargetFinder.findNearest(a, settings.range)
        a.playLoop(settings.readyAnimation, lerpTicks = 3)
        AssassinSounds.dashReady(a.entity)
        beginReady(a)
        // beginReady は技の途中から呼ぶ前提で tick を -1 にするので、最初だけ 0 に戻す
        tick = 0
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        val done = when (phase) {
            Phase.READY -> ready(a)
            Phase.DASH -> dash(a)
            Phase.FINISH -> finish(a)
            Phase.TIRED -> tired(a)
            Phase.TIRED_END -> tick >= settings.exhaustedEndTicks - 1
        }
        tick++
        return done
    }

    override fun stop(boss: Boss) {
        val a = boss as AssassinBoss
        a.stopAnimation(settings.readyAnimation)
        a.stopAnimation(settings.exhaustedAnimation)
        a.floating = false
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        when (phase) {
            Phase.TIRED, Phase.TIRED_END -> event.damage *= settings.exhaustedDamageMultiplier
            Phase.DASH -> event.damage *= settings.dashDamageMultiplier
            else -> {}
        }
    }

    private fun next(p: Phase) {
        phase = p
        tick = -1   // tick() の最後で 0 になる
    }

    private fun currentTarget(a: AssassinBoss): LivingEntity? {
        val t = target?.takeIf { TargetFinder.isTargetable(a, it) } ?: TargetFinder.findNearest(a, settings.range * 1.5)
        target = t
        return t
    }

    // ===== 構え: 線を決めて予告 =====

    /** 次の一閃の線を決める（標的を貫いて overshoot 先まで。壁の手前で止まる） */
    private fun beginReady(a: AssassinBoss) {
        val entity = a.entity
        val from = entity.location
        val t = currentTarget(a)
        val dir = t?.let { Facing.horizontalDir(entity, it) } ?: Facing.forward(entity)
        val dist = t?.let { Math.hypot(it.location.x - from.x, it.location.z - from.z) } ?: 6.0
        val want = (dist + settings.overshoot).coerceAtMost(settings.maxLength)
        val free = Ground.freeLength(from, dir, want)
        lineFrom = from.clone()
        lineTo = from.clone().add(dir.clone().multiply(free))
        Facing.face(entity, dir)
        next(Phase.READY)
    }

    private fun ready(a: AssassinBoss): Boolean {
        val from = lineFrom ?: return true
        val to = lineTo ?: return true
        val warn = if (dashIndex == 0) settings.firstWarningTicks else settings.warningTicks
        val hot = tick >= warn - 3
        if (tick % 2 == 0 || hot) AssassinFx.line(from, to, if (hot) AssassinFx.WARN_HOT else AssassinFx.WARN)
        AssassinFx.shadow(a.entity.location, 0.7)
        if (tick < warn - 1) return false
        // 一閃
        a.playOnce(settings.dashAnimation)
        AssassinSounds.dash(a.entity)
        hit.clear()
        next(Phase.DASH)
        return false
    }

    // ===== 一閃 =====

    private fun dash(a: AssassinBoss): Boolean {
        val entity = a.entity
        val from = lineFrom ?: return true
        val to = lineTo ?: return true
        val prev = entity.location
        val f = ((tick + 1).toDouble() / settings.dashTicks).coerceAtMost(1.0)
        val goal = from.clone().add(to.toVector().subtract(from.toVector()).multiply(f))
        goal.y = prev.y
        val moved = stepTowards(prev, goal)
        moved.yaw = prev.yaw
        moved.pitch = 0f
        entity.teleport(moved)

        // 通り道の残像と斬撃
        AssassinFx.afterimage(prev, entity.height * 0.9)
        prev.world.spawnParticle(Particle.SWEEP_ATTACK, moved.clone().add(0.0, 1.0, 0.0), 1, 0.2, 0.2, 0.2, 0.0)
        hitAlong(a, prev, moved)

        val reached = f >= 1.0 || moved.distanceSquared(prev) < 1e-4
        if (!reached) return false
        dashIndex++
        if (dashIndex < settings.dashes) {
            beginReady(a)
            return false
        }
        beginFinish(a)
        return false
    }

    /** 地面に沿って goal まで進む（0.5 ブロックずつ。壁があればその手前で止まる） */
    private fun stepTowards(from: Location, goal: Location): Location {
        val v = Vector(goal.x - from.x, 0.0, goal.z - from.z)
        val len = v.length()
        if (len < 1e-3) return from.clone()
        val n = Math.ceil(len / 0.5).toInt()
        val step = v.multiply(1.0 / n)
        var at = from.clone()
        repeat(n) {
            at = Ground.step(at.clone().add(step)) ?: return at
        }
        return at
    }

    /** a → b の線分の近く（lineWidth）にいる相手を斬る */
    private fun hitAlong(a: AssassinBoss, p0: Location, p1: Location) {
        val seg = Vector(p1.x - p0.x, 0.0, p1.z - p0.z)
        val segLen2 = seg.lengthSquared()
        val mid = p0.clone().add(seg.clone().multiply(0.5))
        val r = Math.sqrt(segLen2) / 2 + settings.lineWidth + 1.0
        for (e in mid.world.getNearbyEntities(mid, r, 3.0, r)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(a, e) || e.uniqueId in hit) continue
            val dy = e.location.y - p0.y
            if (dy < -1.5 || dy > 2.5) continue
            val w = Vector(e.location.x - p0.x, 0.0, e.location.z - p0.z)
            val s = if (segLen2 < 1e-6) 0.0 else (w.dot(seg) / segLen2).coerceIn(0.0, 1.0)
            val closest = seg.clone().multiply(s)
            val half = e.width / 2
            if (w.subtract(closest).length() > settings.lineWidth + half) continue
            hit += e.uniqueId
            e.noDamageTicks = 0
            e.damage(settings.damage, a.entity)
            val side = if (segLen2 > 1e-6) seg.clone().normalize() else Vector()
            e.velocity = side.multiply(settings.knockback).setY(0.25)
            e.world.spawnParticle(Particle.CRIT, e.location.add(0.0, e.height * 0.6, 0.0), 10, 0.2, 0.3, 0.2, 0.3)
        }
    }

    // ===== 跳び上がって突き下ろす =====

    private fun beginFinish(a: AssassinBoss) {
        val entity = a.entity
        val t = currentTarget(a)
        val here = entity.location
        val landRaw = t?.location ?: here.clone().add(Facing.forward(entity).multiply(3.0))
        finishFrom = here.clone()
        finishAt = Ground.step(landRaw.clone(), maxDrop = 4) ?: here.clone()
        a.stopAnimation(settings.readyAnimation)
        a.playOnce(settings.finishAnimation)
        a.floating = true
        AssassinSounds.leap(entity)
        here.world.spawnParticle(Particle.CLOUD, here.clone().add(0.0, 0.1, 0.0), 14, 0.4, 0.05, 0.4, 0.06)
        next(Phase.FINISH)
    }

    private fun finish(a: AssassinBoss): Boolean {
        val entity = a.entity
        val from = finishFrom ?: return true
        val land = finishAt ?: return true
        val s = settings
        if (tick <= s.finishLandTick) {
            AssassinFx.ring(land, s.impactRadius, if (tick > s.finishLandTick - 4) AssassinFx.WARN_HOT else AssassinFx.WARN)
            val f = tick.toDouble() / s.finishLandTick
            val pos = from.clone().add(land.toVector().subtract(from.toVector()).multiply(f))
            pos.y = from.y + (land.y - from.y) * f + s.finishHeight * sin(PI * f)
            val dir = Facing.horizontalDir(from.toVector(), land.toVector())
            pos.yaw = dir?.let { Facing.yawOf(it) } ?: entity.location.yaw
            pos.pitch = 0f
            // 天井にぶつかる所までしか上がらない
            if (!entity.collidesAt(pos)) entity.teleport(pos)
        }
        if (tick == s.finishLandTick) impact(a, land)
        if (tick < s.finishTicks - 1) return false
        a.playLoop(s.exhaustedAnimation, lerpTicks = 3)
        next(Phase.TIRED)
        return false
    }

    private fun impact(a: AssassinBoss, at: Location) {
        val entity = a.entity
        val landed = at.clone()
        landed.yaw = entity.location.yaw
        entity.teleport(landed)
        a.floating = false
        AssassinSounds.finishImpact(at)
        AssassinFx.inkBurst(at, 1.6)
        AssassinFx.ring(at, settings.impactRadius, AssassinFx.WARN_HOT, 48)
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) {
            at.world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.1, 0.0), 30, settings.impactRadius * 0.4, 0.05, settings.impactRadius * 0.4, 0.0, below.blockData)
        }
        val r = settings.impactRadius
        for (e in entity.getNearbyEntities(r, 3.0, r)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(a, e)) continue
            val dx = e.location.x - at.x
            val dz = e.location.z - at.z
            if (dx * dx + dz * dz > r * r || e.location.y - at.y > 2.5) continue
            e.noDamageTicks = 0
            e.damage(settings.impactDamage, entity)
            val away = Vector(dx, 0.0, dz)
            e.velocity = (if (away.lengthSquared() > 1e-4) away.normalize().multiply(0.6) else Vector()).setY(settings.launch)
        }
    }

    // ===== 疲れ果てる =====

    private fun tired(a: AssassinBoss): Boolean {
        if (tick % 16 == 0) AssassinSounds.pant(a.entity)
        if (tick % 6 == 0) a.entity.world.spawnParticle(Particle.CLOUD, a.entity.location.add(0.0, 1.4, 0.0).add(Facing.forward(a.entity).multiply(0.4)), 1, 0.05, 0.05, 0.05, 0.01)
        if (tick < settings.exhaustedTicks - 1) return false
        a.stopAnimation(settings.exhaustedAnimation)
        a.playOnce(settings.exhaustedEndAnimation)
        next(Phase.TIRED_END)
        return false
    }
}
