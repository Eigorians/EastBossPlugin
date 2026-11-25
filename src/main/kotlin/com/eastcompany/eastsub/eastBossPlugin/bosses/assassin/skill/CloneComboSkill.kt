package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinClone
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinFx
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import java.util.UUID

/**
 * 残像の連撃（本気）: 本体の連撃（[strike]）を振り始めると同時に、標的の左右に分身が飛び出し、
 * cloneDelayTicks 遅れて同じ連撃で斬りかかる（正面と横からの挟み撃ち）。
 *
 * 分身の斬撃は、分身の前の扇（cloneHitRadius・cloneHitDegrees）で cloneHitWindows ごとに当たる。
 * 分身は殴ると煙になって消える。斬り終えた分身も煙になって消える。
 */
class CloneComboSkill(
    override val id: String,
    private val settings: Settings,
    private val strike: BoneStrikeSkill,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val clones: Int,
        val flankDistance: Double,
        val cloneDelayTicks: Int,
        val cloneAnimation: String,
        val cloneTicks: Int,
        val cloneHitWindows: List<IntRange>,
        val cloneHitRadius: Double,
        val cloneHitDegrees: Double,
        val cloneDamage: Double,
        val cloneKnockback: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                clones = section.int(Names.SkillConfig.CLONES, 2).coerceIn(1, 3),
                flankDistance = section.double(Names.SkillConfig.FLANK_DISTANCE, 2.6),
                cloneDelayTicks = section.int(Names.SkillConfig.CLONE_DELAY_TICKS, 5).coerceAtLeast(0),
                cloneAnimation = section.string(Names.SkillConfig.CLONE_ANIMATION, "combo"),
                cloneTicks = section.int(Names.SkillConfig.CLONE_TICKS, 28),
                cloneHitWindows = section.intRanges(Names.SkillConfig.CLONE_HIT_WINDOWS, listOf(2..5, 8..10, 14..17)),
                cloneHitRadius = section.double(Names.SkillConfig.CLONE_HIT_RADIUS, 2.4),
                cloneHitDegrees = section.double(Names.SkillConfig.CLONE_HIT_DEGREES, 150.0),
                cloneDamage = section.double(Names.SkillConfig.CLONE_DAMAGE, 3.0),
                cloneKnockback = section.double(Names.SkillConfig.KNOCKBACK, 0.3),
            )
        }
    }

    /** 飛び出した分身1体 */
    private class Flanker(val clone: AssassinClone) {
        var alive = true
        val hit = HashSet<UUID>()
        var window = -1
    }

    override val weight: Int get() = settings.weight
    override val sounds: List<SoundCue> get() = strike.sounds
    override val soundTick: Int? get() = strike.soundTick

    private var strikeDone = false
    private var spawned = false
    private var cloneTick = 0
    private val flankers = mutableListOf<Flanker>()

    override fun canStart(boss: Boss): Boolean = strike.canStart(boss)

    override fun start(boss: Boss) {
        strikeDone = false
        spawned = false
        cloneTick = 0
        flankers.clear()
        strike.start(boss)
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        if (!strikeDone && strike.tick(a)) {
            strikeDone = true
            strike.stop(a)
        }
        // 本体が振り始めたら（詰め寄りが終わったら）、左右に分身を出す
        if (!spawned && (strikeDone || (strike.soundTick ?: -1) >= 0)) {
            spawned = true
            spawnFlankers(a)
        }
        if (spawned) tickFlankers(a)
        val clonesDone = !spawned || flankers.none { it.alive } || cloneTick > settings.cloneDelayTicks + settings.cloneTicks
        if (!(strikeDone && clonesDone)) return false
        finishFlankers(a)
        return true
    }

    override fun stop(boss: Boss) {
        val a = boss as AssassinBoss
        if (!strikeDone) strike.stop(a)
        strikeDone = true
        finishFlankers(a)
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (!strikeDone) strike.modifyIncomingDamage(boss, event)
    }

    private fun spawnFlankers(a: AssassinBoss) {
        val t = TargetFinder.findNearest(a, 12.0) ?: return
        val toTarget = Facing.horizontalDir(a.entity, t) ?: Facing.forward(a.entity)
        val side = Vector(-toTarget.z, 0.0, toTarget.x)
        // 左右（3体目は標的の向こう側）
        val offsets = listOf(side, side.clone().multiply(-1), toTarget).take(settings.clones)
        for (off in offsets) {
            val raw = t.location.clone().add(off.clone().multiply(settings.flankDistance))
            if (Ground.freeLength(t.location, off, settings.flankDistance) < settings.flankDistance - 0.6) continue
            val at = Ground.step(raw, maxDrop = 4) ?: continue
            at.yaw = Facing.yawOf(off.clone().multiply(-1))
            val clone = a.spawnClone(at, hittable = true) ?: continue
            val f = Flanker(clone)
            clone.onHit = { _ ->
                if (f.alive) {
                    f.alive = false
                    a.removeClone(clone)
                }
            }
            clone.playOnce(AssassinBoss.STEP_FRONT)
            flankers += f
        }
    }

    private fun tickFlankers(a: AssassinBoss) {
        val s = settings
        val local = cloneTick - s.cloneDelayTicks
        for (f in flankers) {
            if (!f.alive) continue
            val clone = f.clone
            val t = TargetFinder.findNearest(a, 16.0)
            // 振り始めるまでは標的の方を向く
            if (local < (s.cloneHitWindows.firstOrNull()?.first ?: 0)) t?.let { clone.faceTo(it.location) }
            if (local == 0) clone.playOnce(s.cloneAnimation)
            val w = s.cloneHitWindows.indexOfFirst { local in it }
            if (w >= 0) {
                if (w != f.window) {
                    f.window = w
                    f.hit.clear()
                    AssassinFx.slashArc(clone.location.clone().add(0.0, 1.1, 0.0), s.cloneHitRadius * 0.8, clone.forward(), s.cloneHitDegrees)
                }
                a.hitArc(clone.location, clone.forward(), s.cloneHitRadius, s.cloneHitDegrees, s.cloneDamage, s.cloneKnockback, f.hit)
            }
        }
        cloneTick++
    }

    private fun finishFlankers(a: AssassinBoss) {
        for (f in flankers) if (f.alive) {
            f.alive = false
            a.removeClone(f.clone)
        }
        flankers.clear()
    }
}
