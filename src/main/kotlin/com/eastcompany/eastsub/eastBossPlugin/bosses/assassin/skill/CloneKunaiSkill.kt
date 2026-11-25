package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinClone
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.random.Random

/**
 * 分身の苦無（本気）: 標的をはさんで左右に分身が現れ、本体と同時に3方向から苦無を扇に投げる。
 *
 *   分身が現れる（appearTicks）→ みんなで投げる構え → aimLockTicks 前に狙いが固まり、苦無の通り道が細い線で見える
 *   → releaseTick で一斉に投げる → 分身は煙になって消える
 *
 * 逃げ道が限られるので、線の隙間を探して走る。分身は殴ると消える（その方向からは飛んでこない）。
 */
class CloneKunaiSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val minRange: Double,
        val clones: Int,
        val sideDegrees: Double,
        val distanceMin: Double,
        val distanceMax: Double,
        val appearAnimation: String,
        val appearTicks: Int,
        val throwAnimation: String,
        val releaseTick: Int,
        val aimLockTicks: Int,
        val endTicks: Int,
        val count: Int,
        val fanDegrees: Double,
        val bullet: BulletField.Spec,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 18.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 4.0),
                clones = section.int(Names.SkillConfig.CLONES, 2).coerceIn(1, 4),
                sideDegrees = section.double(Names.SkillConfig.SIDE_ANGLE, 85.0),
                distanceMin = section.double(Names.SkillConfig.DISTANCE, 6.0),
                distanceMax = section.double(Names.SkillConfig.MAX_RANGE, 10.0),
                appearAnimation = section.string(Names.SkillConfig.APPEAR_ANIMATION, "clone_appear"),
                appearTicks = section.int(Names.SkillConfig.APPEAR_TICKS, 10),
                throwAnimation = section.string(Names.SkillConfig.ANIMATION, "throw"),
                releaseTick = section.int(Names.SkillConfig.RELEASE_TICKS, 7),
                aimLockTicks = section.int(Names.SkillConfig.LOCK_TICKS, 6),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 30),
                count = section.int(Names.SkillConfig.COUNT, 3).coerceAtLeast(1),
                fanDegrees = section.double(Names.SkillConfig.FAN_DEGREES, 30.0),
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
            )
        }
    }

    /** 投げ手（clone が null なら本体） */
    private class Thrower(val clone: AssassinClone?) {
        var alive = true
        var aim: Vector? = null
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private val throwers = mutableListOf<Thrower>()

    /** 投げる tick（技の開始から） */
    private val throwStart: Int get() = settings.appearTicks
    private val releaseAt: Int get() = settings.appearTicks + settings.releaseTick

    override fun canStart(boss: Boss): Boolean = findTarget(boss) != null

    private fun findTarget(boss: Boss): LivingEntity? {
        val t = TargetFinder.findNearest(boss, settings.range) ?: return null
        return if (t.location.distanceSquared(boss.entity.location) < settings.minRange * settings.minRange) null else t
    }

    override fun start(boss: Boss) {
        val a = boss as AssassinBoss
        tick = 0
        throwers.clear()
        val t = findTarget(a)
        target = t
        Facing.faceTarget(a, t)
        throwers += Thrower(null)
        if (t != null) spawnClones(a, t)
    }

    /** 標的をはさんで左右（標的から見て本体と sideDegrees ずれた向き）に分身を出す */
    private fun spawnClones(a: AssassinBoss, t: LivingEntity) {
        val tl = t.location
        val toBoss = Facing.horizontalDir(tl.toVector(), a.entity.location.toVector()) ?: Facing.forward(t)
        val dist = Math.hypot(a.entity.location.x - tl.x, a.entity.location.z - tl.z).coerceIn(settings.distanceMin, settings.distanceMax)
        for (i in 0 until settings.clones) {
            val sign = if (i % 2 == 0) 1 else -1
            val deg = sign * (settings.sideDegrees + 40.0 * (i / 2)) + Random.nextDouble(-12.0, 12.0)
            val dir = toBoss.clone().rotateAroundY(Math.toRadians(deg))
            val free = Ground.freeLength(tl, dir, dist)
            if (free < 3.0) continue
            val at = Ground.step(tl.clone().add(dir.clone().multiply(free)), maxDrop = 4) ?: continue
            at.yaw = Facing.yawOf(dir.clone().multiply(-1))
            val clone = a.spawnClone(at, hittable = true) ?: continue
            val th = Thrower(clone)
            clone.onHit = { _ ->
                if (th.alive) {
                    th.alive = false
                    a.removeClone(clone)
                }
            }
            clone.playOnce(settings.appearAnimation)
            throwers += th
        }
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        val s = settings
        val t = target?.takeIf { TargetFinder.isValidTarget(a, it) }

        // 狙いが固まるまでは、全員が標的の方を向く
        if (tick < releaseAt - s.aimLockTicks && t != null) {
            for (th in throwers) {
                if (!th.alive) continue
                if (th.clone == null) Facing.faceTarget(a, t) else th.clone.faceTo(t.location)
            }
        }
        if (tick == throwStart) {
            for (th in throwers) {
                if (!th.alive) continue
                if (th.clone == null) {
                    a.holdKunai(true)
                    a.playOnce(s.throwAnimation)
                } else {
                    th.clone.setPartVisible("dagger_l", false)
                    th.clone.setPartVisible("kunai_fan", true)
                    th.clone.playOnce(s.throwAnimation)
                }
            }
        }
        // 狙いを固める（標的の今の胸元へ）
        if (tick == releaseAt - s.aimLockTicks) {
            for (th in throwers) {
                if (!th.alive) continue
                val from = handOf(a, th)
                th.aim = t?.location?.add(0.0, t.height * 0.5, 0.0)?.toVector()?.subtract(from.toVector())
                    ?: if (th.clone == null) Facing.forward(a.entity) else th.clone.forward()
            }
            AssassinSounds.play(a.entity.location, org.bukkit.Sound.ITEM_CROSSBOW_LOADING_END, 1.2f, 1.6f)
        }
        // 苦無の通り道を細い線で見せる
        if (tick in (releaseAt - s.aimLockTicks) until releaseAt && tick % 2 == 0) {
            for (th in throwers) {
                if (!th.alive) continue
                val aim = th.aim ?: continue
                val from = handOf(a, th)
                for (k in 0 until s.count) {
                    val ang = if (s.count == 1) 0.0 else -s.fanDegrees / 2 + s.fanDegrees * k / (s.count - 1)
                    val d = aim.clone().setY(0).normalize().rotateAroundY(Math.toRadians(ang))
                    val ground = from.clone().also { it.y = Ground.groundY(it.world, it.x, it.y, it.z, 4) }
                    AssassinFx.line(ground, ground.clone().add(d.multiply(7.0)), AssassinFx.WARN, spacing = 0.7, height = 0.1)
                }
            }
        }
        if (tick == releaseAt) {
            for (th in throwers) {
                if (!th.alive) continue
                val aim = th.aim ?: continue
                val from = handOf(a, th)
                val n = s.count
                for (k in 0 until n) {
                    val ang = if (n == 1) 0.0 else -s.fanDegrees / 2 + s.fanDegrees * k / (n - 1)
                    a.kunai.fire(from, aim.clone().rotateAroundY(Math.toRadians(ang)), s.bullet)
                }
                if (th.clone != null) th.clone.setPartVisible("kunai_fan", false)
            }
            AssassinSounds.throwKunai(a.entity)
        }
        if (tick == releaseAt + 3) a.holdKunai(false)
        // 投げ終えた分身は煙になって消える
        if (tick == releaseAt + 6) {
            for (th in throwers) if (th.clone != null && th.alive) {
                th.alive = false
                a.removeClone(th.clone)
            }
        }
        tick++
        return tick >= s.endTicks
    }

    override fun stop(boss: Boss) {
        val a = boss as AssassinBoss
        a.holdKunai(false)
        for (th in throwers) if (th.clone != null && th.alive) {
            th.alive = false
            a.removeClone(th.clone)
        }
        throwers.clear()
        target = null
    }

    /** 苦無を投げ出す所（本体は左手のボーン、分身は胸の高さの少し前） */
    private fun handOf(a: AssassinBoss, th: Thrower): Location {
        val clone = th.clone ?: return a.bonePoint(AssassinBoss.KUNAI_BONE)
            ?: a.entity.location.add(0.0, a.entity.height * 0.6, 0.0).add(Facing.forward(a.entity).multiply(0.6))
        return clone.location.clone().add(0.0, 1.3 * a.settings.modelScale, 0.0).add(clone.forward().multiply(0.6))
    }
}
