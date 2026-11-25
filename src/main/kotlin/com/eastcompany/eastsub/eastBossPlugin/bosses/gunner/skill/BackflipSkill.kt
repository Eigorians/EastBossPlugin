package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerBoss
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import kotlin.random.Random

/**
 * バク宙で距離を取る。近づいてきた相手から離れる向きへ distance ブロック跳び、
 * 逆さまの頂点（shootTicks）で両手の銃を撃つ。
 *
 * 跳ぶ向きは真後ろから最大 sideAngle 度ずれる（毎回少し違う所に着地する）。
 * 後ろが壁なら、開けている横や斜め前へ跳ぶ（[GunnerBoss.escapeDir]）。
 * 壁際に追い込まれている間は、選ばれやすさが corneredWeightMultiplier 倍になる（壁際から抜け出す）。
 * 跳んでいる間（moveStartTick〜moveEndTick）は受けるダメージが takenDamageMultiplier 倍。
 */
class BackflipSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
    /** 壁際に追い込まれているか */
    private val cornered: () -> Boolean = { false },
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        /** この距離以内に相手がいるときだけ使う */
        val range: Double,
        val endTicks: Int,
        val moveStartTick: Int,
        val moveEndTick: Int,
        val distance: Double,
        val sideAngle: Double,
        val shootTicks: List<Int>,
        val bullet: BulletField.Spec,
        val takenDamageMultiplier: Double,
        val corneredWeightMultiplier: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 5.0),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 15),
                moveStartTick = section.int(Names.SkillConfig.MOVE_START_TICK, 3),
                moveEndTick = section.int(Names.SkillConfig.MOVE_END_TICK, 12),
                distance = section.double(Names.SkillConfig.DISTANCE, 7.0),
                sideAngle = section.double(Names.SkillConfig.SIDE_ANGLE, 25.0),
                shootTicks = section.stringList(Names.SkillConfig.SHOOT_TICKS, listOf("6"))
                    .mapNotNull { it.trim().toIntOrNull() },
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
                takenDamageMultiplier = section.double(Names.SkillConfig.TAKEN_DAMAGE_MULTIPLIER, 0.4),
                corneredWeightMultiplier = section.int(Names.SkillConfig.CORNERED_WEIGHT_MULTIPLIER, 4).coerceAtLeast(1),
            )
        }
    }

    override val weight: Int get() = if (cornered()) settings.weight * settings.corneredWeightMultiplier else settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private var away: Vector = Vector(0, 0, -1)
    private var blocked = false

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        blocked = false
        val t = TargetFinder.findNearest(boss, settings.range)
        target = t
        val back = t?.let { Facing.horizontalDir(it, boss.entity) } ?: Facing.forward(boss.entity).multiply(-1)
        val wanted = back.rotateAroundY(Math.toRadians(Random.nextDouble(-settings.sideAngle, settings.sideAngle)))
        away = (boss as GunnerBoss).escapeDir(wanted, settings.distance)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GunnerBoss
        val s = settings
        val entity = g.entity

        if (tick == s.moveStartTick) {
            entity.world.spawnParticle(Particle.CLOUD, entity.location.add(0.0, 0.1, 0.0), 12, 0.4, 0.05, 0.4, 0.04)
        }
        if (tick >= s.moveStartTick && tick < s.moveEndTick && !blocked) {
            val step = s.distance / (s.moveEndTick - s.moveStartTick).coerceAtLeast(1)
            val dest = Ground.step(entity.location.add(away.clone().multiply(step)))
            if (dest == null) {
                blocked = true
            } else {
                // 跳んでいる間も相手の方を向いたまま（後ろ向きに跳ぶ）
                val face = target?.let { Facing.horizontalDir(entity, it) } ?: away.clone().multiply(-1)
                dest.yaw = Facing.yawOf(face)
                dest.pitch = 0f
                entity.teleport(dest)
            }
        }
        if (tick == s.moveEndTick) {
            entity.world.spawnParticle(Particle.CLOUD, entity.location.add(0.0, 0.1, 0.0), 10, 0.4, 0.05, 0.4, 0.03)
        }
        if (tick in s.shootTicks) {
            val t = target?.takeIf { TargetFinder.isValidTarget(g, it) }
            g.shoot(GunnerBoss.Muzzle.RIGHT, t, s.bullet)
            g.shoot(GunnerBoss.Muzzle.LEFT, t, s.bullet)
        }

        tick++
        return tick >= s.endTicks
    }

    override fun stop(boss: Boss) {
        boss.entity.setAI(true)
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (tick >= settings.moveStartTick && tick < settings.moveEndTick) event.damage *= settings.takenDamageMultiplier
    }
}
