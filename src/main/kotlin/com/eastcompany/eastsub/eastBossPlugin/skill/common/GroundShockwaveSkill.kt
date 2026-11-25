package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地面を叩き、足元から外へ広がる衝撃波（地面の爆発の輪）を出す。ジャンプでかわせる。
 *
 *   0 〜 impactTicks … 振りかぶり。衝撃波の届く範囲（maxRadius）を赤い円で知らせる
 *   impactTicks から waveIntervalTicks ごとに waves 回、衝撃波が startRadius から maxRadius へ広がる
 *   〜 endTicks      … 隙
 *
 * 衝撃波の輪が通過したとき、地面にいる相手にだけ当たる（足が地面から jumpClearance より上ならかわせる）。
 * 当たった相手は上に打ち上げられる。1回の衝撃波で1人1回。
 */
class GroundShockwaveSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        /** この距離以内に標的がいるときだけ発動 */
        val range: Double,
        val animation: String,
        /** 杖が地面に着いて最初の衝撃波が出る tick */
        val impactTicks: Int,
        val waves: Int,
        val waveIntervalTicks: Int,
        /** 衝撃波が 1tick に広がる距離（ブロック） */
        val waveSpeed: Double,
        /** ボスの中心から、衝撃波が出始める距離 */
        val startRadius: Double,
        val maxRadius: Double,
        /** 輪の太さ（ブロック） */
        val waveWidth: Double,
        val damage: Double,
        /** 足が地面からこの高さより上にあれば当たらない（ジャンプでかわせる） */
        val jumpClearance: Double,
        /** 当たった相手を打ち上げる強さ */
        val launch: Double,
        val endTicks: Int,
    ) {
        /** 最後の衝撃波が広がり切る tick */
        val lastWaveEndTick: Int
            get() = impactTicks + (waves - 1) * waveIntervalTicks +
                ((maxRadius - startRadius) / waveSpeed).toInt() + 1

        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 8.0),
                animation = section.string(Names.SkillConfig.ANIMATION),
                impactTicks = section.int(Names.SkillConfig.IMPACT_TICKS, 17),
                waves = section.int(Names.SkillConfig.WAVES, 2).coerceAtLeast(1),
                waveIntervalTicks = section.int(Names.SkillConfig.WAVE_INTERVAL_TICKS, 14).coerceAtLeast(1),
                waveSpeed = section.double(Names.SkillConfig.WAVE_SPEED, 0.7).coerceAtLeast(0.05),
                startRadius = section.double(Names.SkillConfig.START_RADIUS, 1.5),
                maxRadius = section.double(Names.SkillConfig.MAX_RADIUS, 12.0),
                waveWidth = section.double(Names.SkillConfig.WAVE_WIDTH, 1.0),
                damage = section.double(Names.SkillConfig.DAMAGE, 8.0),
                jumpClearance = section.double(Names.SkillConfig.JUMP_CLEARANCE, 0.5),
                launch = section.double(Names.SkillConfig.LAUNCH, 0.9),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 50),
            )
        }
    }

    /** 広がっている途中の衝撃波 */
    private class Wave(var radius: Double) {
        val hit = HashSet<UUID>()
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private lateinit var center: Location
    private val waves = mutableListOf<Wave>()

    override fun canStart(boss: Boss): Boolean =
        TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        waves.clear()
        center = boss.entity.location
        boss.entity.setAI(false)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val s = settings
        center = boss.entity.location

        // 振りかぶり中: 衝撃波の届く範囲を知らせる
        if (tick < s.impactTicks && tick % 3 == 0) {
            drawRing(center, s.maxRadius, WARNING, dense = false)
        }

        // 衝撃波を出す
        val sinceImpact = tick - s.impactTicks
        if (sinceImpact >= 0 && sinceImpact % s.waveIntervalTicks == 0 && sinceImpact / s.waveIntervalTicks < s.waves) {
            waves += Wave(s.startRadius)
            val world = center.world
            world.spawnParticle(Particle.EXPLOSION, center, 2, 0.8, 0.1, 0.8, 0.0)
        }

        tickWaves(boss)

        tick++
        return tick >= max(s.endTicks, s.lastWaveEndTick)
    }

    override fun stop(boss: Boss) {
        waves.clear()
        boss.resumeEntityAi()
    }

    private fun tickWaves(boss: Boss) {
        // 当たったプレイヤーが倒れるとボスが消えて stop() が呼ばれ、一覧が空になることがある。
        // そのため写しを回し、一覧から消えた衝撃波は処理しない
        for (wave in waves.toList()) {
            if (wave !in waves) continue
            drawRing(center, wave.radius, WAVE, dense = true)
            hitAt(boss, wave)
            wave.radius += settings.waveSpeed
            if (wave.radius > settings.maxRadius) waves.remove(wave)
        }
    }

    /** 輪の上にいる、地面に立っている相手に当てる */
    private fun hitAt(boss: Boss, wave: Wave) {
        val s = settings
        val half = s.waveWidth / 2 + s.waveSpeed / 2      // 1tick で進む分も含めて判定
        val reach = wave.radius + half
        for (e in center.world.getNearbyEntities(center, reach, 4.0, reach)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            val battle = boss.battle
            if (battle != null && !battle.isParticipant(e)) continue
            if (e.uniqueId in wave.hit) continue

            val dx = e.location.x - center.x
            val dz = e.location.z - center.z
            val dist = sqrt(dx * dx + dz * dz)
            if (abs(dist - wave.radius) > half) continue

            // ジャンプ中（足が地面から離れている）ならかわせる
            val height = e.location.y - center.y
            if (!e.isOnGround && height > s.jumpClearance) continue
            if (height > s.jumpClearance + 1.5) continue     // 段差の上など、明らかに高い所は対象外

            wave.hit += e.uniqueId
            val victim = e as LivingEntity
            victim.damage(s.damage, boss.entity)
            val out = if (dist < 1e-3) Vector(0.0, 0.0, 0.0) else Vector(dx / dist, 0.0, dz / dist)
            victim.velocity = out.multiply(0.4).setY(s.launch)
        }
    }

    /** 地面の高さに輪を描く。dense なら地面のブロックが砕ける粒も出す */
    private fun drawRing(at: Location, radius: Double, dust: Particle.DustOptions, dense: Boolean) {
        val world = at.world
        val points = (radius * Math.PI * (if (dense) 2.2 else 1.2)).toInt().coerceIn(8, 160)
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points
            val x = at.x + cos(a) * radius
            val z = at.z + sin(a) * radius
            val y = at.y + 0.15
            world.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust)
            if (!dense) continue
            val ground = world.getBlockAt(floor(x).toInt(), at.blockY - 1, floor(z).toInt())
            if (!ground.type.isAir) {
                world.spawnParticle(Particle.BLOCK, x, y + 0.2, z, 3, 0.15, 0.25, 0.15, 0.0, ground.blockData)
            }
            if (i % 6 == 0) world.spawnParticle(Particle.FLAME, x, y + 0.3, z, 1, 0.1, 0.3, 0.1, 0.02)
        }
        if (dense) {
            // 輪のところどころで小さな爆発
            repeat(max(2, (radius / 2).toInt())) {
                val a = Math.random() * Math.PI * 2
                world.spawnParticle(Particle.EXPLOSION, at.x + cos(a) * radius, at.y + 0.4, at.z + sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private companion object {
        val WARNING = Particle.DustOptions(Color.fromRGB(255, 50, 40), 1.4f)
        val WAVE = Particle.DustOptions(Color.fromRGB(255, 140, 40), 1.8f)
    }
}
