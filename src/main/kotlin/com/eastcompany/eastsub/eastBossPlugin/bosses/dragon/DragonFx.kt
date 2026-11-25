package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** ドラゴンの見た目の演出（パーティクル） */
object DragonFx {

    /** 予告の輪（外側: 着弾の範囲） */
    val WARN = Particle.DustOptions(Color.fromRGB(255, 60, 20), 1.3f)
    /** 予告の輪（内側: 着弾に向けて広がる） */
    val WARN_INNER = Particle.DustOptions(Color.fromRGB(255, 190, 60), 1.0f)
    /** 急降下の狙い（追っている間 / 決まった後） */
    val MARK = Particle.DustOptions(Color.fromRGB(255, 120, 40), 1.4f)
    val MARK_LOCKED = Particle.DustOptions(Color.fromRGB(255, 20, 20), 1.8f)
    val EMBER = Particle.DustOptions(Color.fromRGB(255, 110, 30), 1.1f)
    val ASH = Particle.DustOptions(Color.fromRGB(60, 50, 50), 1.5f)

    /** 地面に輪を描く */
    fun ring(center: Location, radius: Double, dust: Particle.DustOptions, points: Int = (radius * 6).toInt().coerceIn(10, 48)) {
        if (radius <= 0.05) return
        val world = center.world
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points
            world.spawnParticle(Particle.DUST, center.x + cos(a) * radius, center.y + 0.15, center.z + sin(a) * radius,
                1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    /** 炎の爆発（火球の着弾など） */
    fun fireBurst(at: Location, radius: Double) {
        val world = at.world
        world.spawnParticle(Particle.EXPLOSION, at, 2, radius * 0.25, 0.2, radius * 0.25, 0.0)
        world.spawnParticle(Particle.FLAME, at, (radius * 14).toInt(), radius * 0.4, 0.3, radius * 0.4, 0.09)
        world.spawnParticle(Particle.LAVA, at, (radius * 3).toInt(), radius * 0.3, 0.2, radius * 0.3, 0.0)
        world.spawnParticle(Particle.LARGE_SMOKE, at, (radius * 4).toInt(), radius * 0.3, 0.4, radius * 0.3, 0.03)
    }

    /** 地面を走る衝撃（急降下の着地など）: 外へ広がる炎と土ぼこり */
    fun groundShock(at: Location, radius: Double) {
        val world = at.world
        val ground = at.clone().add(0.0, -0.5, 0.0).block.type.takeIf { it.isSolid } ?: Material.DIRT
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1)
        world.spawnParticle(Particle.BLOCK, at, 60, radius * 0.4, 0.2, radius * 0.4, 0.2, ground.createBlockData())
        val n = (radius * 10).toInt()
        for (i in 0 until n) {
            val a = Math.PI * 2 * i / n
            val dir = Vector(cos(a), 0.05, sin(a))
            world.spawnParticle(Particle.FLAME, at.clone().add(dir.clone().multiply(0.8)), 0, dir.x, dir.y, dir.z, radius * 0.08)
            world.spawnParticle(Particle.CLOUD, at.clone().add(dir.clone().multiply(1.2)), 0, dir.x, 0.0, dir.z, radius * 0.06)
        }
    }

    /** 鼻から立ちのぼる煙（待機中・疲れたとき） */
    fun nostrilSmoke(at: Location, heavy: Boolean) {
        val world = at.world
        world.spawnParticle(Particle.SMOKE, at, if (heavy) 6 else 2, 0.15, 0.1, 0.15, 0.02)
        if (heavy) world.spawnParticle(Particle.LARGE_SMOKE, at, 2, 0.1, 0.1, 0.1, 0.02)
        if (Random.nextInt(4) == 0) world.spawnParticle(Particle.SMALL_FLAME, at, 1, 0.05, 0.05, 0.05, 0.005)
    }

    /** 口の端からこぼれる火（溜めているとき） */
    fun mawGlow(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.FLAME, at, 3, 0.15, 0.1, 0.15, 0.01)
        world.spawnParticle(Particle.SMALL_FLAME, at, 2, 0.2, 0.1, 0.2, 0.01)
    }
}
