package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.random.Random

/**
 * 杖からビームを撃ちながら、その場でぐるりと回る。
 *
 *   0 〜 fireStartTicks          … 標的の方を向いて溜める。撃ち始める方向を地面に赤い線で知らせる
 *   fireStartTicks 〜 fireEndTicks … ビームを撃ちながら rotationDegrees 度回る（左右どちらかはランダム）
 *   〜 endTicks                    … 隙
 *
 * ビームは杖の先（originForward・originHeight）から dropDistance で地面すれすれ（beamHeight）まで下り、
 * そこから length まで地面を這う。壁に当たるとそこで止まる。
 * 当たるのは、体がビームの高さ（±hitHeight）に重なっているときだけ。地面を這う部分はジャンプで飛び越えられる。
 * 回る速さはそれほど速くないので、ビームの向きを見て、跳ぶか、届かない所（length より外）へ逃げる。
 * 同じ相手には hitIntervalTicks ごとにダメージ。
 *
 * 回転はボス自体の向きを回して表す（モデルが向きに付いてくるので、ビームとずれない）。
 */
class RotatingBeamSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        /** この距離以内に標的がいるときだけ発動 */
        val range: Double,
        val animation: String,
        val fireStartTicks: Int,
        val fireEndTicks: Int,
        val endTicks: Int,
        /** 撃っている間に回る角度（度）。360 で1周 */
        val rotationDegrees: Double,
        val length: Double,
        /** ビームの出る位置: ボスの中心から前へ・足元から上へ（ブロック） */
        val originForward: Double,
        val originHeight: Double,
        /** 杖の先から地面すれすれまで下りきる水平距離（ボスの中心から） */
        val dropDistance: Double,
        /** 地面を這う部分の高さ（足元から） */
        val beamHeight: Double,
        /** ビームの当たり判定の幅（中心線からの水平距離） */
        val hitWidth: Double,
        /** ビームの当たり判定の厚さ（中心からの上下の距離） */
        val hitHeight: Double,
        val damage: Double,
        val hitIntervalTicks: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 16.0),
                animation = section.string(Names.SkillConfig.ANIMATION),
                fireStartTicks = section.int(Names.SkillConfig.FIRE_START_TICKS, 20),
                fireEndTicks = section.int(Names.SkillConfig.FIRE_END_TICKS, 96),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 108),
                rotationDegrees = section.double(Names.SkillConfig.ROTATION_DEGREES, 270.0),
                length = section.double(Names.SkillConfig.LENGTH, 16.0),
                originForward = section.double(Names.SkillConfig.ORIGIN_FORWARD, 3.5),
                originHeight = section.double(Names.SkillConfig.ORIGIN_HEIGHT, 2.0),
                dropDistance = section.double(Names.SkillConfig.DROP_DISTANCE, 6.0),
                beamHeight = section.double(Names.SkillConfig.BEAM_HEIGHT, 0.4),
                hitWidth = section.double(Names.SkillConfig.HIT_WIDTH, 0.6),
                hitHeight = section.double(Names.SkillConfig.HIT_HEIGHT, 0.35),
                damage = section.double(Names.SkillConfig.DAMAGE, 6.0),
                hitIntervalTicks = section.int(Names.SkillConfig.HIT_INTERVAL_TICKS, 10).coerceAtLeast(1),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var yaw = 0f
    /** 回る向き（+1 / -1） */
    private var spin = 1
    private var target: LivingEntity? = null
    /** 相手ごとの最後に当てた tick */
    private val lastHit = HashMap<UUID, Int>()

    override fun canStart(boss: Boss): Boolean =
        TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        lastHit.clear()
        spin = if (Random.nextBoolean()) 1 else -1
        target = TargetFinder.findNearest(boss, settings.range)
        boss.entity.setAI(false)
        yaw = boss.entity.location.yaw
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val s = settings
        val entity = boss.entity

        when {
            // 溜め: 標的を追い、撃ち始める方向を知らせる（直前の 6tick は向きを固定）
            tick < s.fireStartTicks -> {
                if (tick < s.fireStartTicks - 6) {
                    Facing.faceTarget(boss, target)
                    yaw = entity.location.yaw
                }
                if (tick % 3 == 0) drawWarning(entity.location, Facing.dirOfYaw(yaw))
            }
            // 撃ちながら回る
            tick < s.fireEndTicks -> {
                val perTick = s.rotationDegrees / (s.fireEndTicks - s.fireStartTicks)
                yaw = (yaw + perTick * spin).toFloat()
                Facing.faceYaw(entity, yaw)
                fire(boss)
            }
        }

        tick++
        return tick >= s.endTicks
    }

    override fun stop(boss: Boss) {
        lastHit.clear()
        boss.resumeEntityAi()
        target = null
    }

    private fun fire(boss: Boss) {
        val base = boss.entity.location
        val dir = Facing.dirOfYaw(yaw)
        val reach = drawBeam(base, dir)
        hitAlong(boss, base, dir, reach)
    }

    /** ボスの中心から水平に d 離れた所での、ビームの高さ（足元から） */
    private fun beamY(d: Double): Double {
        val s = settings
        if (d >= s.dropDistance) return s.beamHeight
        val t = ((d - s.originForward) / (s.dropDistance - s.originForward)).coerceIn(0.0, 1.0)
        return s.originHeight + (s.beamHeight - s.originHeight) * t
    }

    /** ビームを描く。壁に当たったらそこで止め、届いた水平距離を返す */
    private fun drawBeam(base: Location, dir: Vector): Double {
        val s = settings
        val world = base.world
        var d = s.originForward
        var i = 0
        while (d <= s.length) {
            val p = base.clone().add(dir.clone().multiply(d)).add(0.0, beamY(d), 0.0)
            if (!p.block.isPassable) break
            world.spawnParticle(Particle.DUST, p, 2, 0.12, 0.12, 0.12, 0.0, BEAM_OUTER)
            world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, BEAM_CORE)
            if (i % 4 == 0) world.spawnParticle(Particle.END_ROD, p, 1, 0.05, 0.05, 0.05, 0.01)
            // 地面を這う部分は、地面が焦げる
            if (d >= s.dropDistance && i % 3 == 0) world.spawnParticle(Particle.FLAME, p, 1, 0.1, 0.05, 0.1, 0.01)
            d += 0.45
            i++
        }
        val end = base.clone().add(dir.clone().multiply(d)).add(0.0, beamY(d), 0.0)
        world.spawnParticle(Particle.LARGE_SMOKE, end, 2, 0.2, 0.1, 0.2, 0.01)
        return d
    }

    /** ビームに体が重なっている相手に当てる */
    private fun hitAlong(boss: Boss, base: Location, dir: Vector, reach: Double) {
        val s = settings
        for (e in base.world.getNearbyEntities(base, reach + 1, 6.0, reach + 1)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            val battle = boss.battle
            if (battle != null && !battle.isParticipant(e)) continue

            val rel = e.location.toVector().subtract(base.toVector()).setY(0.0)
            val along = rel.dot(dir)
            if (along < s.originForward - 0.5 || along > reach) continue
            val side = rel.clone().subtract(dir.clone().multiply(along)).length()
            if (side > s.hitWidth + e.width / 2) continue

            // 体（足元〜頭）がビームの高さに重なっているときだけ当たる。地面を這う部分はジャンプでかわせる
            val y = base.y + beamY(along)
            val feet = e.location.y
            if (feet > y + s.hitHeight || feet + e.height < y - s.hitHeight) continue

            val last = lastHit[e.uniqueId]
            if (last != null && tick - last < s.hitIntervalTicks) continue
            lastHit[e.uniqueId] = tick

            val victim = e as LivingEntity
            victim.damage(s.damage, boss.entity)
            // ビームの回る向きへ押し流す
            victim.velocity = Vector(-dir.z, 0.0, dir.x).multiply(0.6 * spin).setY(0.25)
        }
    }

    /** 撃ち始める方向に、地面の赤い線 */
    private fun drawWarning(base: Location, dir: Vector) {
        val world = base.world
        var d = settings.originForward
        while (d <= settings.length) {
            val p = base.clone().add(dir.clone().multiply(d)).add(0.0, 0.15, 0.0)
            world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, WARNING)
            d += 0.8
        }
    }

    private companion object {
        val BEAM_OUTER = Particle.DustOptions(Color.fromRGB(140, 40, 230), 2.2f)
        val BEAM_CORE = Particle.DustOptions(Color.fromRGB(255, 230, 255), 1.2f)
        val WARNING = Particle.DustOptions(Color.fromRGB(255, 50, 40), 1.4f)
    }
}
