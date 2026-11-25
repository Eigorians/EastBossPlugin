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
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import java.util.UUID

/**
 * 影渡り: 地面に沈み、黒い影になって標的の背後へ這い寄り、飛び出して斬り上げる。
 *
 *   沈む（sinkTicks。hideTick でモデルが消え、攻撃がすり抜けるようになる）
 *   → 這い寄る（標的の背後 behindDistance の所を目指す。標的に lockDistance まで近づくと、行き先が固まる）
 *   → 予告（warningTicks。行き先の影が広がり、鼓動の音。紫の輪が飛び出しの範囲）
 *   → 飛び出す（emerge。hitWindows の間、hitRadius 以内の相手を斬り上げる）
 *   → 構え直す（recoverTicks。受けるダメージが recoverDamageMultiplier 倍の殴りどき）
 *
 * 光に弱い: 這い寄る・予告の間に、影が明るい所（[AssassinBoss.isLit]）に入ると引きずり出され、
 * 目をかばってよろめく（exposedTicks。受けるダメージが exposedDamageMultiplier 倍）。明るい所では沈めない。
 *
 * decoys > 0 なら分身の影も出す（本気）。分身は標的のまわりの別の所へ這い寄り、本物より先に弾けて
 * 近くの相手に decoyDamage を与える。本物の影にだけ、目の光が2つ浮かぶ。
 */
class ShadowStrikeSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val minRange: Double,
        val sinkAnimation: String,
        val sinkTicks: Int,
        val hideTick: Int,
        val travelSpeed: Double,
        val travelMaxTicks: Int,
        val behindDistance: Double,
        val lockDistance: Double,
        val warningTicks: Int,
        val emergeAnimation: String,
        val emergeTicks: Int,
        val hitWindows: List<IntRange>,
        val hitRadius: Double,
        val damage: Double,
        val knockback: Double,
        val launch: Double,
        val recoverTicks: Int,
        val recoverDamageMultiplier: Double,
        val decoys: Int,
        val decoyDamage: Double,
        val decoyRadius: Double,
        val exposedAnimation: String,
        val exposedTicks: Int,
        val exposedDamageMultiplier: Double,
        val exposedMessage: Component,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 20.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 3.0),
                sinkAnimation = section.string(Names.SkillConfig.SINK_ANIMATION, "sink"),
                sinkTicks = section.int(Names.SkillConfig.SINK_TICKS, 10).coerceAtLeast(1),
                hideTick = section.int(Names.SkillConfig.HIDE_TICK, 8),
                travelSpeed = section.double(Names.SkillConfig.TRAVEL_SPEED, 0.5).coerceAtLeast(0.05),
                travelMaxTicks = section.int(Names.SkillConfig.TRAVEL_MAX_TICKS, 60),
                behindDistance = section.double(Names.SkillConfig.BEHIND_DISTANCE, 2.0),
                lockDistance = section.double(Names.SkillConfig.LOCK_DISTANCE, 3.5),
                warningTicks = section.int(Names.SkillConfig.WARNING_TICKS, 12).coerceAtLeast(1),
                emergeAnimation = section.string(Names.SkillConfig.EMERGE_ANIMATION, "emerge"),
                emergeTicks = section.int(Names.SkillConfig.EMERGE_TICKS, 20),
                hitWindows = section.intRanges(Names.SkillConfig.HIT_WINDOWS, listOf(2..7)),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 2.6),
                damage = section.double(Names.SkillConfig.DAMAGE, 9.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.5),
                launch = section.double(Names.SkillConfig.LAUNCH, 0.6),
                recoverTicks = section.int(Names.SkillConfig.RECOVER_TICKS, 16),
                recoverDamageMultiplier = section.double(Names.SkillConfig.RECOVER_DAMAGE_MULTIPLIER, 1.3),
                decoys = section.int(Names.SkillConfig.DECOYS, 0).coerceIn(0, 6),
                decoyDamage = section.double(Names.SkillConfig.DECOY_DAMAGE, 4.0),
                decoyRadius = section.double(Names.SkillConfig.DECOY_RADIUS, 2.0),
                exposedAnimation = section.string(Names.SkillConfig.EXPOSED_ANIMATION, "exposed"),
                exposedTicks = section.int(Names.SkillConfig.EXPOSED_TICKS, 30),
                exposedDamageMultiplier = section.double(Names.SkillConfig.EXPOSED_DAMAGE_MULTIPLIER, 1.6),
                exposedMessage = section.file.component(
                    section.fullKey(Names.SkillConfig.EXPOSED_MESSAGE),
                    def = "<gold>光に影をあぶり出した！ 今のうちに叩け",
                ),
            )
        }
    }

    private enum class Phase { SINK, TRAVEL, WARN, EMERGE, EXPOSED }

    /** 分身の影。標的のまわりの angle の方向（背後から何度ずれた所か）を目指す */
    private class Decoy(var pos: Location, val angle: Double) {
        var dest: Location = pos.clone()
        var burst = false
    }

    override val weight: Int get() = settings.weight

    private var phase = Phase.SINK
    private var tick = 0
    private var target: LivingEntity? = null
    private var dest: Location? = null
    private var locked = false
    private val decoys = mutableListOf<Decoy>()
    private val hit = HashSet<UUID>()

    override fun canStart(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        if (a.submerged || a.isLit(a.entity.location)) return false
        return findTarget(a) != null
    }

    private fun findTarget(boss: Boss): LivingEntity? {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return null
        return if (t.location.distanceSquared(boss.entity.location) < settings.minRange * settings.minRange) null else t
    }

    override fun start(boss: Boss) {
        val a = boss as AssassinBoss
        phase = Phase.SINK
        tick = 0
        locked = false
        dest = null
        decoys.clear()
        hit.clear()
        target = findTarget(a)
        Facing.faceTarget(a, target)
        a.playOnce(settings.sinkAnimation)
        AssassinSounds.sink(a.entity)
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        val done = when (phase) {
            Phase.SINK -> sink(a)
            Phase.TRAVEL -> travel(a)
            Phase.WARN -> warn(a)
            Phase.EMERGE -> emerge(a)
            Phase.EXPOSED -> tick >= settings.exposedTicks
        }
        tick++
        return done
    }

    override fun stop(boss: Boss) {
        (boss as AssassinBoss).surface()
        decoys.clear()
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        when (phase) {
            Phase.EXPOSED -> event.damage *= settings.exposedDamageMultiplier
            Phase.EMERGE -> if (tick >= settings.emergeTicks) event.damage *= settings.recoverDamageMultiplier
            else -> {}
        }
    }

    private fun next(p: Phase) {
        phase = p
        tick = -1   // tick() の最後で 0 になる
    }

    // ===== 沈む =====

    private fun sink(a: AssassinBoss): Boolean {
        val at = a.entity.location
        AssassinFx.shadow(at, 0.5 + 0.8 * tick / settings.sinkTicks)
        if (tick == settings.hideTick) {
            a.submerge()
            AssassinFx.inkBurst(at, 0.5)
        }
        if (tick >= settings.sinkTicks - 1) {
            a.submerge()
            // 分身: 本物と同じ所から、標的のまわりの別の向きへ
            for (i in 0 until settings.decoys) {
                val side = if (i % 2 == 0) 1 else -1
                decoys += Decoy(at.clone(), side * (110.0 + 50.0 * (i / 2)))
            }
            next(Phase.TRAVEL)
        }
        return false
    }

    // ===== 這い寄る =====

    private fun travel(a: AssassinBoss): Boolean {
        val entity = a.entity
        val t = target?.takeIf { TargetFinder.isValidTarget(a, it) } ?: TargetFinder.findNearest(a, settings.range * 1.5)
        target = t
        val here = entity.location
        if (t == null) {
            // 標的がいなくなった: その場で出る
            dest = here.clone()
            return beginWarn(a)
        }

        if (!locked) dest = behindPoint(t, here, settings.behindDistance, 0.0)
        val goal = dest ?: here
        val moved = a.moveShadow(here, goal, settings.travelSpeed)
        entity.teleport(moved)
        AssassinFx.shadow(moved, 0.85, real = true, rage = a.isRaging, forward = Vector(goal.x - moved.x, 0.0, goal.z - moved.z))
        if (tick % 6 == 0) AssassinSounds.creep(moved)

        // 分身も同じ速さで這う
        for (d in decoys) {
            if (!locked) d.dest = behindPoint(t, d.pos, settings.behindDistance + 0.5, d.angle)
            d.pos = a.moveShadow(d.pos, d.dest, settings.travelSpeed)
            AssassinFx.shadow(d.pos, 0.85)
        }

        if (a.isLit(moved)) return expose(a)

        val dx = t.location.x - moved.x
        val dz = t.location.z - moved.z
        if (!locked && dx * dx + dz * dz <= settings.lockDistance * settings.lockDistance) locked = true
        val gx = goal.x - moved.x
        val gz = goal.z - moved.z
        if ((locked && gx * gx + gz * gz < 0.36) || tick >= settings.travelMaxTicks) {
            dest = moved.clone()
            return beginWarn(a)
        }
        return false
    }

    /**
     * 標的の背後（向いている向きの反対側）distance の所。angle だけ標的のまわりを回した所（分身用）。
     * 壁などで立てなければ、影のいる側に寄せる
     */
    private fun behindPoint(t: LivingEntity, from: Location, distance: Double, angle: Double): Location {
        val tl = t.location
        val back = Facing.forward(t).multiply(-1).rotateAroundY(Math.toRadians(angle))
        val candidates = listOf(back, back.clone().rotateAroundY(Math.PI / 2), back.clone().rotateAroundY(-Math.PI / 2))
        for (dir in candidates) {
            val p = Ground.step(tl.clone().add(dir.clone().multiply(distance)), maxDrop = 4) ?: continue
            if (Ground.freeLength(tl, dir, distance) >= distance - 0.6) return p
        }
        val toShadow = Facing.horizontalDir(tl.toVector(), from.toVector()) ?: back
        return Ground.step(tl.clone().add(toShadow.multiply(distance * 0.8)), maxDrop = 4) ?: from.clone()
    }

    // ===== 予告 =====

    private fun beginWarn(a: AssassinBoss): Boolean {
        val at = dest ?: a.entity.location
        AssassinSounds.emergeWarn(at)
        for (d in decoys) d.dest = d.pos.clone()
        next(Phase.WARN)
        return false
    }

    private fun warn(a: AssassinBoss): Boolean {
        val entity = a.entity
        val at = dest ?: entity.location
        val f = (tick + 1).toDouble() / settings.warningTicks
        val t = target?.takeIf { TargetFinder.isValidTarget(a, it) }
        val face = t?.let { Facing.horizontalDir(at.toVector(), it.location.toVector()) }
        AssassinFx.shadow(at, 0.85 + 0.7 * f, real = true, rage = a.isRaging, forward = face)
        if (tick % 2 == 0) AssassinFx.ring(at, settings.hitRadius, if (f > 0.6) AssassinFx.WARN_HOT else AssassinFx.WARN)
        if (tick == settings.warningTicks / 2) AssassinSounds.emergeWarn(at)

        // 分身: 予告の半ばで先に弾ける（偽物の一撃）
        for (d in decoys) {
            if (d.burst) continue
            AssassinFx.shadow(d.pos, 0.85 + 0.7 * f)
            if (tick % 2 == 0) AssassinFx.ring(d.pos, settings.decoyRadius, AssassinFx.WARN)
            if (tick >= settings.warningTicks / 2) burstDecoy(a, d)
        }

        if (a.isLit(at)) return expose(a)
        if (tick < settings.warningTicks - 1) return false

        // 飛び出す: 標的の方を向いて、モデルを戻す
        val loc = at.clone()
        loc.yaw = face?.let { Facing.yawOf(it) } ?: entity.location.yaw
        loc.pitch = 0f
        entity.teleport(loc)
        a.playOnce(settings.emergeAnimation)
        a.surface()
        AssassinFx.inkBurst(loc, 1.2)
        AssassinSounds.emerge(entity)
        hit.clear()
        next(Phase.EMERGE)
        return false
    }

    private fun burstDecoy(a: AssassinBoss, d: Decoy) {
        d.burst = true
        AssassinFx.inkBurst(d.pos, 1.0)
        d.pos.world.spawnParticle(Particle.SWEEP_ATTACK, d.pos.clone().add(0.0, 1.0, 0.0), 3, 0.6, 0.3, 0.6, 0.0)
        AssassinSounds.decoyBurst(d.pos)
        for (e in d.pos.world.getNearbyEntities(d.pos, settings.decoyRadius, 2.5, settings.decoyRadius)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(a, e)) continue
            val dx = e.location.x - d.pos.x
            val dz = e.location.z - d.pos.z
            if (dx * dx + dz * dz > settings.decoyRadius * settings.decoyRadius) continue
            e.noDamageTicks = 0
            e.damage(settings.decoyDamage, a.entity)
        }
    }

    // ===== 飛び出して斬り上げる =====

    private fun emerge(a: AssassinBoss): Boolean {
        val entity = a.entity
        val at = entity.location
        if (settings.hitWindows.any { tick in it }) {
            if (tick == settings.hitWindows.first().first) AssassinFx.slashArc(at.clone().add(0.0, 1.2, 0.0), settings.hitRadius * 0.8, Facing.forward(entity), 360.0)
            val r = settings.hitRadius
            for (e in entity.getNearbyEntities(r, 3.0, r)) {
                if (e !is LivingEntity || !TargetFinder.isValidTarget(a, e) || e.uniqueId in hit) continue
                val dx = e.location.x - at.x
                val dz = e.location.z - at.z
                val dy = e.location.y - at.y
                if (dx * dx + dz * dz > r * r || dy < -1.5 || dy > 3.0) continue
                hit += e.uniqueId
                e.noDamageTicks = 0
                e.damage(settings.damage, entity)
                if (e is Player && a.status.justGuard.guardedNow(e)) continue
                val away = Vector(dx, 0.0, dz)
                val push = if (away.lengthSquared() > 1e-4) away.normalize().multiply(settings.knockback) else Vector()
                e.velocity = push.setY(settings.launch)
            }
        }
        return tick >= settings.emergeTicks + settings.recoverTicks
    }

    // ===== 光にあぶり出される =====

    private fun expose(a: AssassinBoss): Boolean {
        val entity = a.entity
        a.playOnce(settings.exposedAnimation)
        a.surface()
        val at = entity.location
        at.world.spawnParticle(Particle.END_ROD, at.clone().add(0.0, 1.0, 0.0), 24, 0.3, 0.5, 0.3, 0.12)
        at.world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0.0, 0.8, 0.0), 20, 0.4, 0.6, 0.4, 0.04)
        AssassinFx.inkBurst(at, 0.8)
        AssassinSounds.exposed(entity)
        a.announce(settings.exposedMessage)
        // 分身は光にかき消える
        for (d in decoys) if (!d.burst) {
            d.burst = true
            AssassinFx.inkBurst(d.pos, 0.5)
        }
        next(Phase.EXPOSED)
        return false
    }
}
