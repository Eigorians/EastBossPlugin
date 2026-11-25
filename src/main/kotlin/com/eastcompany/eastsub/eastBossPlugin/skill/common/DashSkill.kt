package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID

/**
 * 溜めてから標的へ一直線に突進する。
 *
 *   0 〜 windupTicks           … 標的を追って狙いを定める
 *   windupTicks 〜 hitEndTicks … 突進＋ダメージ判定（1回の突進で1人1回）
 *   〜 endTicks                … その場で静止（隙）
 *
 * 当たったプレイヤーはスタン、モブは吹き飛ばす。壁にぶつかったらそこで止まる。
 */
class DashSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val windupTicks: Int,
        val hitEndTicks: Int,
        val endTicks: Int,
        /** 1tick に進む距離（ブロック） */
        val speed: Double,
        /** 通過した線からの当たり判定の幅（ブロック） */
        val hitRadius: Double,
        val damage: Double,
        /** 当たったプレイヤーのスタン時間。0 以下でスタンしない */
        val stunTicks: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 12.0),
                windupTicks = section.int(Names.SkillConfig.WINDUP_TICKS, 20),
                hitEndTicks = section.int(Names.SkillConfig.HIT_END_TICKS, 35),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 60),
                speed = section.double(Names.SkillConfig.SPEED, 0.9),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 1.3),
                damage = section.double(Names.SkillConfig.DAMAGE, 10.0),
                stunTicks = section.int(Names.SkillConfig.STUN_TICKS, 0),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var blocked = false
    private var target: LivingEntity? = null
    private var aim: Vector = Vector(0, 0, 1)
    private val hit = HashSet<UUID>()

    override fun canStart(boss: Boss): Boolean =
        TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        blocked = false
        hit.clear()

        val t = TargetFinder.findNearest(boss, settings.range)
        target = t
        aim = t?.let { Facing.horizontalDir(boss.entity, it) } ?: Facing.forward(boss.entity)

        boss.entity.setAI(false)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        when {
            // 溜め中: 標的を追って狙いを定める
            tick < settings.windupTicks -> {
                Facing.faceTarget(boss, target)?.let { aim = it } ?: Facing.face(boss.entity, aim)
            }
            // 突進 + ダメージ判定
            tick < settings.hitEndTicks -> dashStep(boss)
            // それ以降は静止
        }
        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        boss.resumeEntityAi()
        target = null
    }

    private fun dashStep(boss: Boss) {
        if (blocked) return
        val entity = boss.entity

        val from = entity.location
        val to = from.clone().add(aim.clone().multiply(settings.speed))

        // 壁にぶつかったらそこで止まる
        if (!to.block.isPassable || !to.clone().add(0.0, 1.0, 0.0).block.isPassable) {
            blocked = true
            return
        }
        entity.teleport(to)

        // 通過した区間(from→to)をまとめて判定
        val sweep = BoundingBox.of(from.toVector(), to.toVector())
            .expand(settings.hitRadius, 1.0, settings.hitRadius)
        for (e in entity.world.getNearbyEntities(sweep)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            if (!hit.add(e.uniqueId)) continue

            val victim = e as LivingEntity
            victim.damage(settings.damage, entity)
            // ジャストガードされたら、スタンも効果音も無し
            if (victim is Player && boss.status.justGuard.guardedNow(victim)) continue
            if (victim is Player) {
                // プレイヤーはスタン。ノックバックは消してその場で止める
                if (settings.stunTicks > 0) boss.status.stun.stun(victim, settings.stunTicks)
                victim.world.playSound(victim.location, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.8f)
            } else {
                victim.velocity = aim.clone().multiply(0.8).setY(0.4)
            }
        }
    }
}
