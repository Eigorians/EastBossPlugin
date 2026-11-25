package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinFx
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import kotlin.random.Random

/**
 * 見切り（抽選では選ばれない。殴られてかわしたときにボスが出す）。
 * 残像を残して、殴ってきた相手から離れる向きへ distance ブロック跳び、shootTicks に苦無を投げ返す。
 * 跳んでいる間（moveStartTick〜moveEndTick）に受けるダメージは takenDamageMultiplier 倍。
 */
class DodgeSkill(
    override val id: String,
    private val settings: Settings,
    /** かわした攻撃をしてきた相手 */
    private val attacker: LivingEntity,
) : BossSkill {

    data class Settings(
        val animation: String,
        val endTicks: Int,
        val moveStartTick: Int,
        val moveEndTick: Int,
        val distance: Double,
        val sideAngle: Double,
        val shootTicks: List<Int>,
        val count: Int,
        val fanDegrees: Double,
        val bullet: BulletField.Spec,
        val takenDamageMultiplier: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                animation = section.string(Names.SkillConfig.ANIMATION, "dodge"),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 12),
                moveStartTick = section.int(Names.SkillConfig.MOVE_START_TICK, 0),
                moveEndTick = section.int(Names.SkillConfig.MOVE_END_TICK, 4),
                distance = section.double(Names.SkillConfig.DISTANCE, 3.2),
                sideAngle = section.double(Names.SkillConfig.SIDE_ANGLE, 35.0),
                shootTicks = section.stringList(Names.SkillConfig.SHOOT_TICKS, listOf("6")).mapNotNull { it.trim().toIntOrNull() },
                count = section.int(Names.SkillConfig.COUNT, 1).coerceAtLeast(1),
                fanDegrees = section.double(Names.SkillConfig.FAN_DEGREES, 0.0),
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
                takenDamageMultiplier = section.double(Names.SkillConfig.TAKEN_DAMAGE_MULTIPLIER, 0.3),
            )
        }
    }

    /** 抽選では選ばれない */
    override val weight: Int get() = 0

    private var tick = 0
    private var away = Vector(0, 0, 1)
    private var blocked = false

    override fun canStart(boss: Boss): Boolean = true

    override fun start(boss: Boss) {
        val a = boss as AssassinBoss
        tick = 0
        blocked = false
        val entity = a.entity
        val back = Facing.horizontalDir(attacker, entity) ?: Facing.forward(entity).multiply(-1)
        val wanted = back.rotateAroundY(Math.toRadians(Random.nextDouble(-settings.sideAngle, settings.sideAngle)))
        away = a.pickDir(wanted, settings.distance) ?: wanted
        // 元いた所に残像
        AssassinFx.afterimage(entity.location, entity.height)
        Facing.faceTarget(a, attacker)
        a.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        val s = settings
        val entity = a.entity
        if (tick >= s.moveStartTick && tick < s.moveEndTick && !blocked) {
            val step = s.distance / (s.moveEndTick - s.moveStartTick).coerceAtLeast(1)
            val from = entity.location
            val to = Ground.step(from.clone().add(away.clone().multiply(step)))
            if (to == null) {
                blocked = true
            } else {
                // 跳んでいる間も相手の方を向いたまま
                val face = Facing.horizontalDir(entity, attacker) ?: away.clone().multiply(-1)
                to.yaw = Facing.yawOf(face)
                to.pitch = 0f
                entity.teleport(to)
                AssassinFx.stepDust(from)
            }
        }
        if (tick in s.shootTicks) {
            val t = attacker.takeIf { TargetFinder.isValidTarget(a, it) } ?: TargetFinder.findNearest(a, 20.0)
            Facing.faceTarget(a, t)
            a.holdKunai(true)
            a.throwKunai(t, s.count, s.fanDegrees, s.bullet)
        }
        if (s.shootTicks.any { tick == it + 3 }) a.holdKunai(false)
        tick++
        return tick >= s.endTicks
    }

    override fun stop(boss: Boss) {
        (boss as AssassinBoss).holdKunai(false)
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (tick >= settings.moveStartTick && tick < settings.moveEndTick) event.damage *= settings.takenDamageMultiplier
    }
}
