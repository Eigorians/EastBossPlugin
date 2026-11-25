package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * 瀕死: HP 1 で耐えた髑髏騎士の最後の技（倒されるまで終わらない。KnightBoss が始める）。
 *
 *   0 〜 blastTick        … 崩れ落ちて片膝をつき、怨霊を吸い込む（殴っても効かない）
 *   blastTick             … 絶叫。range 以内の全員を、ノーダメージで pushDistance の距離まで吹き飛ばす
 *   barrageStartTick 〜   … 大剣にすがり、全員へ intervalTicks ごとに怨霊の束（count 体）を放ち続ける
 *                           （KnightHazards.volley）。ここからは近接の一撃で倒せる
 *
 * 束は必ず追いついて同時に届くので、走っても避けられない。受け損ねても普通に防いでも knockback で押し戻され、
 * ジャストガードしたときだけ押されない＝ジャストガードしながらでなければ近づけない。
 * 届く cueLeadTicks 前に、狙われた本人へ構える合図の音を鳴らす。
 */
class KnightLastStandSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val animation: String,
        val loopAnimation: String,
        val range: Double,
        val blastTick: Int,
        val pushDistance: Double,
        val fallProtectTicks: Int,
        val barrageStartTick: Int,
        val intervalTicks: Int,
        val count: Int,
        val speed: Double,
        val minFlightTicks: Int,
        val cueLeadTicks: Int,
        val damage: Double,
        val knockback: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                animation = section.string(Names.SkillConfig.ANIMATION, "last_stand"),
                loopAnimation = section.string(Names.SkillConfig.LOOP_ANIMATION, "last_stand_loop"),
                range = section.double(Names.SkillConfig.RANGE, 64.0),
                blastTick = section.int(Names.SkillConfig.BLAST_TICK, 30).coerceAtLeast(0),
                pushDistance = section.double(Names.SkillConfig.PUSH_DISTANCE, 28.0),
                fallProtectTicks = section.int(Names.SkillConfig.FALL_PROTECT_TICKS, 240),
                barrageStartTick = section.int(Names.SkillConfig.BARRAGE_START_TICK, 52),
                intervalTicks = section.int(Names.SkillConfig.INTERVAL_TICKS, 20).coerceAtLeast(1),
                count = section.int(Names.SkillConfig.COUNT, 7).coerceAtLeast(1),
                speed = section.double(Names.SkillConfig.SPEED, 0.9).coerceAtLeast(0.1),
                minFlightTicks = section.int(Names.SkillConfig.MIN_FLIGHT_TICKS, 12).coerceAtLeast(1),
                cueLeadTicks = section.int(Names.SkillConfig.CUE_LEAD_TICKS, 7),
                damage = section.double(Names.SkillConfig.DAMAGE, 2.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 1.15),
            )
        }
    }

    /** 抽選では選ばれない */
    override val weight: Int get() = 0

    /** 近接の一撃で倒せる状態か（吹き飛ばしの演出が終わった後） */
    var exposed = false
        private set

    private var tick = 0
    private var looping = false

    /** 吹き飛ばしで浮かせた tick（相手ごと） */
    private val lifted = HashMap<UUID, Int>()

    /** もう吹き飛ばした人 */
    private val pushed = HashSet<UUID>()

    /** 相手ごとに、最後に放った束が届く tick（続けて届いて受けられない、が起きないよう間を空ける） */
    private val arrivals = HashMap<UUID, Int>()

    override fun canStart(boss: Boss): Boolean = true

    override fun start(boss: Boss) {
        tick = 0
        looping = false
        exposed = false
        lifted.clear()
        pushed.clear()
        arrivals.clear()
        val k = boss as KnightBoss
        // それまでに放った地割れ・雷などは消す（吹き飛ばしはノーダメージ）
        k.hazards.clear()
        val entity = k.entity
        entity.setAI(false)
        entity.setGravity(true)
        // AI を切ると落ちないので、宙で耐えたら地面に降ろす（近接が届かなくならないように）
        val at = entity.location
        at.y = Ground.groundY(at.world, at.x, at.y, at.z, GROUND_SEARCH)
        entity.teleport(at)
        k.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val k = boss as KnightBoss
        if (tick < settings.blastTick && tick % 2 == 0) {
            KnightFx.lastStandGather(k.entity, tick.toDouble() / settings.blastTick)
        }
        if (tick == settings.blastTick) {
            KnightFx.lastStandBlast(k.entity)
            KnightSounds.lastStandBlast(k.entity)
        }
        // 直前にジャストガードした人は吹き飛ばし止めが効いているので、切れるまで数 tick やり直す
        if (tick in settings.blastTick..settings.blastTick + PUSH_RETRY_TICKS) push(k)

        if (tick >= settings.barrageStartTick) {
            if (!looping) {
                looping = true
                exposed = true
                k.playLoop(settings.loopAnimation, lerpTicks = 4)
            }
            barrage(k)
        }
        tick++
        return false
    }

    override fun stop(boss: Boss) {
        if (looping) boss.stopAnimation(settings.loopAnimation)
        looping = false
        exposed = false
        boss.resumeEntityAi()
    }

    /** 全員（高い所にいても。標的の高さ制限は見ない） */
    private fun players(boss: KnightBoss): List<Player> {
        val at = boss.entity.location
        return at.world.players.filter {
            TargetFinder.isValidTarget(boss, it) && it.location.distanceSquared(at) <= settings.range * settings.range
        }
    }

    /**
     * 今の距離によらず、だいたい pushDistance の所まで飛ばす。
     * 地面にいる相手をいきなり横へ飛ばすと最初の 1tick の摩擦で半分近く消えるので、浮かせてから横へ飛ばす
     */
    private fun push(boss: KnightBoss) {
        for (p in players(boss)) {
            if (p.uniqueId in pushed || boss.status.justGuard.guardedNow(p)) continue
            val liftedAt = lifted[p.uniqueId]
            if (liftedAt == null) {
                lifted[p.uniqueId] = tick
                boss.status.fall.protect(p, settings.fallProtectTicks)
                p.velocity = Vector(0.0, LIFT, 0.0)
                continue
            }
            if (tick - liftedAt < LIFT_TICKS) continue
            pushed += p.uniqueId
            val away = Facing.horizontalDir(boss.entity, p) ?: Facing.forward(boss.entity)
            val d = hypot(p.location.x - boss.entity.location.x, p.location.z - boss.entity.location.z)
            val speed = ((settings.pushDistance - d) / BLOCKS_PER_SPEED).coerceIn(MIN_PUSH_SPEED, MAX_PUSH_SPEED)
            p.velocity = away.multiply(speed).setY(PUSH_UP)
        }
    }

    private fun barrage(boss: KnightBoss) {
        val since = tick - settings.barrageStartTick
        if (since % 2 == 0) {
            KnightFx.lastStandHalo(boss.entity, tick)
            Facing.faceTarget(boss, players(boss).minByOrNull { it.location.distanceSquared(boss.entity.location) })
        }
        if (since % HEARTBEAT_TICKS == 0) KnightSounds.lastStandHeartbeat(boss.entity)
        if (since % settings.intervalTicks != 0) return
        val from = boss.entity.location.add(0.0, boss.entity.height * 0.6, 0.0)
        val targets = players(boss)
        for (p in targets) {
            val travel = ceil(from.distance(p.location) / settings.speed).toInt().coerceAtLeast(settings.minFlightTicks)
            // 近づくほど飛ぶ時間が短くなって前の束に追いつくので、届く間隔は間隔の 3/4 より詰めない
            val arriveAt = maxOf(tick + travel, (arrivals[p.uniqueId] ?: -settings.intervalTicks) + settings.intervalTicks * 3 / 4)
            arrivals[p.uniqueId] = arriveAt
            boss.hazards.volley(from, p, settings.count, arriveAt - tick, settings.cueLeadTicks, settings.damage, settings.knockback)
        }
        if (targets.isNotEmpty()) {
            KnightFx.wraithRelease(from)
            KnightSounds.wraithRelease(from)
        }
    }

    private companion object {
        const val GROUND_SEARCH = 40
        const val PUSH_RETRY_TICKS = 6
        const val HEARTBEAT_TICKS = 24

        /** 横へ飛ばす前に浮かせる速さと、その長さ */
        const val LIFT = 0.5
        const val LIFT_TICKS = 2

        /** 速さ 1 あたりに飛ぶ距離（浮かせてから上向き 0.85 で飛ばしたとき。摩擦と空気抵抗を計算した値） */
        const val BLOCKS_PER_SPEED = 10.0
        const val MIN_PUSH_SPEED = 0.8
        const val MAX_PUSH_SPEED = 3.8
        const val PUSH_UP = 0.85
    }
}
