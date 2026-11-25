package com.eastcompany.eastsub.eastBossPlugin.bosses.giant

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

/** 大巨人の見た目の演出（範囲の予告・地響き・土煙） */
object GiantFx {

    /** 予告の縁（橙） */
    val WARN_EDGE = Particle.DustOptions(Color.fromRGB(255, 150, 40), 1.4f)
    /** 予告の満ちていく所（赤） */
    val WARN_FILL = Particle.DustOptions(Color.fromRGB(255, 50, 30), 1.6f)
    /** 本気のとき体から立ちのぼる火の粉 */
    val EMBER = Particle.DustOptions(Color.fromRGB(255, 110, 40), 1.2f)

    // ===== 範囲の予告 =====

    /** 円: 縁と、progress（0〜1）に合わせて広がる内側の円 */
    fun warnCircle(center: Location, radius: Double, progress: Double) {
        ring(center, radius, WARN_EDGE)
        if (progress > 0.02) ring(center, radius * progress, WARN_FILL)
    }

    /** 扇: 縁（弧と両端の線）と、progress に合わせて広がる弧 */
    fun warnCone(center: Location, facing: Vector, radius: Double, degrees: Double, progress: Double) {
        arc(center, facing, radius, degrees, WARN_EDGE)
        val half = Math.toRadians(degrees / 2)
        for (sign in listOf(-1.0, 1.0)) {
            val edge = facing.clone().rotateAroundY(half * sign)
            line(center, edge, radius, WARN_EDGE)
        }
        if (progress > 0.02) arc(center, facing, radius * progress, degrees, WARN_FILL)
    }

    /** 帯: 縁（四角）と、progress に合わせて奥へ伸びる中の線 */
    fun warnLine(origin: Location, facing: Vector, length: Double, width: Double, progress: Double) {
        val side = Vector(-facing.z, 0.0, facing.x).multiply(width / 2)
        line(origin.clone().add(side), facing, length, WARN_EDGE)
        line(origin.clone().subtract(side), facing, length, WARN_EDGE)
        val end = origin.clone().add(facing.clone().multiply(length))
        line(end.clone().subtract(side), side.clone().normalize(), width, WARN_EDGE)
        if (progress > 0.02) {
            line(origin, facing, length * progress, WARN_FILL)
            line(origin.clone().add(side.clone().multiply(0.5)), facing, length * progress, WARN_FILL)
            line(origin.clone().subtract(side.clone().multiply(0.5)), facing, length * progress, WARN_FILL)
        }
    }

    private fun ring(center: Location, radius: Double, dust: Particle.DustOptions) {
        if (radius < 0.1) return
        val points = (radius * 7).toInt().coerceIn(10, 80)
        val world = center.world
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points
            world.spawnParticle(Particle.DUST, center.x + cos(a) * radius, center.y + 0.15, center.z + sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    private fun arc(center: Location, facing: Vector, radius: Double, degrees: Double, dust: Particle.DustOptions) {
        if (radius < 0.1) return
        val steps = (radius * degrees / 360 * 7).toInt().coerceIn(6, 70)
        val world = center.world
        for (i in 0..steps) {
            val a = Math.toRadians(-degrees / 2 + degrees * i / steps)
            val d = facing.clone().rotateAroundY(a).multiply(radius)
            world.spawnParticle(Particle.DUST, center.x + d.x, center.y + 0.15, center.z + d.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    private fun line(from: Location, dir: Vector, length: Double, dust: Particle.DustOptions) {
        val d = dir.clone().setY(0)
        if (d.lengthSquared() < 1e-6 || length <= 0) return
        d.normalize()
        val world = from.world
        var t = 0.0
        while (t <= length) {
            world.spawnParticle(Particle.DUST, from.x + d.x * t, from.y + 0.15, from.z + d.z * t, 1, 0.0, 0.0, 0.0, 0.0, dust)
            t += 0.6
        }
    }

    // ===== 当たり・地響き =====

    /** 地面を叩いた所の土煙と、砕けた地面のかけら */
    fun groundBurst(at: Location, radius: Double) {
        val world = at.world
        val below = at.clone().add(0.0, -0.5, 0.0).block
        val n = (radius * radius * 3).toInt().coerceIn(12, 120)
        if (!below.isPassable) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.3, 0.0), n, radius * 0.45, 0.2, radius * 0.45, 0.0, below.blockData)
        }
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0.0, 0.3, 0.0), (radius * 3).toInt().coerceIn(4, 30), radius * 0.4, 0.2, radius * 0.4, 0.02)
        world.spawnParticle(Particle.EXPLOSION, at.clone().add(0.0, 0.5, 0.0), (radius / 2).toInt().coerceAtLeast(1), radius * 0.3, 0.1, radius * 0.3, 0.0)
    }

    /**
     * 地響き: radius 以内のプレイヤーに低い音を聞かせ、地面にいる相手（ダメージを与えてよい相手だけ）を少し跳ね上げる。
     * 近いほど強い
     */
    fun shake(boss: Boss, at: Location, radius: Double, power: Double = 1.0) {
        if (radius <= 0) return
        for (p in at.world.getNearbyPlayers(at, radius * 1.6, radius, radius * 1.6)) {
            val d = p.location.distance(at)
            val f = (1.0 - d / (radius * 1.6)).coerceIn(0.0, 1.0)
            if (f <= 0) continue
            p.playSound(p.location, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, (0.4 + 0.6 * f).toFloat(), 0.5f)
            p.playSound(p.location, Sound.BLOCK_BASALT_BREAK, SoundCategory.HOSTILE, (0.6 * f).toFloat(), 0.5f)
            if (d <= radius && p.isOnGround && TargetFinder.isValidTarget(boss, p)) {
                p.velocity = p.velocity.setY(0.12 + 0.18 * f * power)
            }
        }
    }

    /** 高い所の相手に届いた一撃: 地面から巻き上がる土煙の柱と、相手の所で弾けるかけら */
    fun airBurst(groundY: Double, at: Location) {
        val world = at.world
        var y = groundY + 1.0
        while (y < at.y) {
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.x, y, at.z, 1, 0.3, 0.3, 0.3, 0.02)
            y += 1.5
        }
        world.spawnParticle(Particle.EXPLOSION, at, 2, 0.5, 0.4, 0.5, 0.0)
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) world.spawnParticle(Particle.BLOCK, at, 30, 0.6, 0.3, 0.6, 0.0, below.blockData)
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.2f, 0.7f)
    }

    /** 足音（歩くとき） */
    fun footstep(boss: Boss, at: Location) {
        val world = at.world
        world.playSound(at, Sound.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 2.0f, 0.4f)
        world.playSound(at, Sound.BLOCK_DEEPSLATE_STEP, SoundCategory.HOSTILE, 1.6f, 0.5f)
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.2, 0.0), 14, 0.8, 0.1, 0.8, 0.0, below.blockData)
        for (p in world.getNearbyPlayers(at, 16.0)) {
            p.playSound(p.location, Sound.ENTITY_WARDEN_STEP, SoundCategory.HOSTILE, 0.5f, 0.5f)
        }
    }

    /** 本気の間、体から立ちのぼる火の粉 */
    fun embers(boss: Boss) {
        val e = boss.entity
        val at = e.location.add(0.0, e.height * 0.6, 0.0)
        at.world.spawnParticle(Particle.DUST, at, 3, e.width * 0.5, e.height * 0.3, e.width * 0.5, 0.0, EMBER)
        at.world.spawnParticle(Particle.SMALL_FLAME, at, 1, e.width * 0.4, e.height * 0.3, e.width * 0.4, 0.01)
    }
}
