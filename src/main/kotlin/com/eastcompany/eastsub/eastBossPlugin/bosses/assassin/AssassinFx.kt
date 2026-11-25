package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Entity
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 影の暗殺者の見た目の演出（パーティクル） */
object AssassinFx {

    /** 影（地面に落ちた黒いしみ） */
    val SHADOW = Particle.DustOptions(Color.fromRGB(10, 8, 18), 2.4f)
    /** 影の縁（紫がかった黒） */
    val SHADOW_EDGE = Particle.DustOptions(Color.fromRGB(46, 20, 70), 1.5f)
    /** 本物の影だけに浮かぶ、目の光 */
    val EYE = Particle.DustOptions(Color.fromRGB(190, 120, 255), 0.6f)
    val EYE_RAGE = Particle.DustOptions(Color.fromRGB(255, 40, 60), 0.6f)
    /** 予告（紫） */
    val WARN = Particle.DustOptions(Color.fromRGB(176, 70, 255), 1.1f)
    /** 予告（もうすぐ来る。赤みがかった紫） */
    val WARN_HOT = Particle.DustOptions(Color.fromRGB(255, 60, 140), 1.3f)
    /** 苦無の光 */
    val STEEL = Particle.DustOptions(Color.fromRGB(205, 212, 230), 0.7f)
    /** 残像 */
    val AFTERIMAGE = Particle.DustOptions(Color.fromRGB(24, 22, 36), 1.6f)
    val AURA = Particle.DustOptions(Color.fromRGB(120, 40, 200), 1.2f)

    /**
     * 地面の影を描く（毎tick呼ぶ）。radius の円の中に黒いしみ、縁に紫がかった黒。
     * real なら、影の前の方に2つの目の光を浮かべる（分身と見分ける手がかり）
     */
    fun shadow(at: Location, radius: Double, real: Boolean = false, rage: Boolean = false, forward: Vector? = null) {
        val world = at.world
        val base = at.clone().add(0.0, 0.08, 0.0)
        val n = (radius * radius * 7).toInt().coerceIn(4, 30)
        repeat(n) {
            val r = radius * sqrt(Random.nextDouble())
            val a = Random.nextDouble(Math.PI * 2)
            world.spawnParticle(Particle.DUST, base.clone().add(cos(a) * r, 0.0, sin(a) * r), 1, 0.0, 0.0, 0.0, 0.0, SHADOW)
        }
        repeat((radius * 5).toInt().coerceAtLeast(3)) {
            val a = Random.nextDouble(Math.PI * 2)
            world.spawnParticle(Particle.DUST, base.clone().add(cos(a) * radius, 0.0, sin(a) * radius), 1, 0.0, 0.0, 0.0, 0.0, SHADOW_EDGE)
        }
        if (Random.nextInt(3) == 0) world.spawnParticle(Particle.SQUID_INK, base, 1, radius * 0.4, 0.05, radius * 0.4, 0.01)
        if (real && forward != null) {
            val f = forward.clone().setY(0)
            if (f.lengthSquared() > 1e-6) {
                f.normalize().multiply(radius * 0.45)
                val side = Vector(-f.z, 0.0, f.x).normalize().multiply(0.18)
                val eye = if (rage) EYE_RAGE else EYE
                val c = base.clone().add(f).add(0.0, 0.04, 0.0)
                world.spawnParticle(Particle.DUST, c.clone().add(side), 1, 0.0, 0.0, 0.0, 0.0, eye)
                world.spawnParticle(Particle.DUST, c.clone().subtract(side), 1, 0.0, 0.0, 0.0, 0.0, eye)
            }
        }
    }

    /** 地面の輪（予告） */
    fun ring(at: Location, radius: Double, dust: Particle.DustOptions, points: Int = (radius * 10).toInt().coerceIn(10, 48)) {
        val world = at.world
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points
            world.spawnParticle(Particle.DUST, at.clone().add(cos(a) * radius, 0.12, sin(a) * radius), 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    /** 地面に沿った線（突進の予告） */
    fun line(from: Location, to: Location, dust: Particle.DustOptions, spacing: Double = 0.45, height: Double = 0.15) {
        val world = from.world
        val v = to.toVector().subtract(from.toVector()).setY(0)
        val len = v.length()
        if (len < 1e-3) return
        v.multiply(1 / len)
        val side = Vector(-v.z, 0.0, v.x).multiply(0.35)
        var d = 0.0
        while (d <= len) {
            val p = from.clone().add(v.clone().multiply(d)).add(0.0, height, 0.0)
            world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, dust)
            // 両端の点線（幅が分かるように）
            if (((d / spacing).toInt()) % 3 == 0) {
                world.spawnParticle(Particle.DUST, p.clone().add(side), 1, 0.0, 0.0, 0.0, 0.0, dust)
                world.spawnParticle(Particle.DUST, p.clone().subtract(side), 1, 0.0, 0.0, 0.0, 0.0, dust)
            }
            d += spacing
        }
    }

    /** ステップの足元の砂ぼこりと影 */
    fun stepDust(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.CLOUD, at.clone().add(0.0, 0.1, 0.0), 2, 0.2, 0.02, 0.2, 0.01)
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.3, 0.0), 3, 0.25, 0.25, 0.25, 0.0, AFTERIMAGE)
    }

    /** 残像（人の形に黒い粒を残す。見切り・突進の跡） */
    fun afterimage(at: Location, height: Double) {
        val world = at.world
        var y = 0.15
        while (y < height) {
            val w = if (y > height * 0.78) 0.18 else 0.28
            world.spawnParticle(Particle.DUST, at.clone().add(0.0, y, 0.0), 2, w, 0.08, w, 0.0, AFTERIMAGE)
            y += 0.25
        }
        world.spawnParticle(Particle.SMOKE, at.clone().add(0.0, height * 0.5, 0.0), 6, 0.2, height * 0.3, 0.2, 0.01)
    }

    /** 影からの飛び出し・分身が弾けるときの、黒いしぶき */
    fun inkBurst(at: Location, power: Double = 1.0) {
        val world = at.world
        val c = at.clone().add(0.0, 0.3, 0.0)
        world.spawnParticle(Particle.SQUID_INK, c, (24 * power).toInt(), 0.5, 0.3, 0.5, 0.12 * power)
        world.spawnParticle(Particle.DUST, c, (30 * power).toInt(), 0.9, 0.5, 0.9, 0.0, SHADOW)
        world.spawnParticle(Particle.LARGE_SMOKE, c, (8 * power).toInt(), 0.5, 0.3, 0.5, 0.03)
    }

    /** 斬撃の弧（水平の円の一部） */
    fun slashArc(center: Location, radius: Double, forward: Vector, degrees: Double = 200.0) {
        val world = center.world
        val f = forward.clone().setY(0)
        if (f.lengthSquared() < 1e-6) return
        f.normalize()
        val steps = (degrees / 12).toInt()
        for (i in 0..steps) {
            val a = Math.toRadians(-degrees / 2 + degrees * i / steps)
            val d = f.clone().rotateAroundY(a).multiply(radius)
            world.spawnParticle(Particle.SWEEP_ATTACK, center.clone().add(d), 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** 見切り: 攻撃をかわした瞬間の火花 */
    fun parry(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, 10, 0.2, 0.3, 0.2, 0.2)
        world.spawnParticle(Particle.CRIT, at, 8, 0.3, 0.3, 0.3, 0.3)
    }

    /** 本気の間、体から立ちのぼる紫の影 */
    fun rageAura(entity: Entity) {
        val at = entity.location
        val world = at.world
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, entity.height * 0.5, 0.0), 3, 0.3, entity.height * 0.35, 0.3, 0.0, AURA)
        world.spawnParticle(Particle.SQUID_INK, at.clone().add(0.0, 0.1, 0.0), 1, 0.3, 0.0, 0.3, 0.01)
    }

    /** 苦無の通り道の光（影縫いの苦無） */
    fun kunaiTrail(at: Location) {
        at.world.spawnParticle(Particle.DUST, at, 2, 0.02, 0.02, 0.02, 0.0, STEEL)
        at.world.spawnParticle(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0.0, WARN)
    }
}
