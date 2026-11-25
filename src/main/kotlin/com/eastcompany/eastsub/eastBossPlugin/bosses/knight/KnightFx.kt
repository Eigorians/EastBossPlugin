package com.eastcompany.eastsub.eastBossPlugin.bosses.knight

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 髑髏騎士の見た目の演出（予告の輪・地割れ・斬撃波・浮遊・打ち上げ・串刺し・怨霊・雷・本気の気配・灰） */
object KnightFx {

    /** 予告の縁（暗い赤） */
    val WARN_EDGE = Particle.DustOptions(Color.fromRGB(150, 20, 30), 1.4f)
    /** 予告の満ちていく所（明るい赤） */
    val WARN_FILL = Particle.DustOptions(Color.fromRGB(255, 50, 40), 1.6f)
    /** 地割れの光（赤熱） */
    val CRACK = Particle.DustOptions(Color.fromRGB(255, 70, 30), 1.5f)
    /** 斬撃波（深紅） */
    val CRESCENT = Particle.DustOptions(Color.fromRGB(200, 10, 30), 2.0f)
    val CRESCENT_CORE = Particle.DustOptions(Color.fromRGB(255, 120, 110), 1.3f)
    /** 本気の気配（マントからこぼれる赤い塵） */
    val AURA = Particle.DustOptions(Color.fromRGB(170, 10, 24), 1.2f)
    /** 灰（死亡） */
    val ASH = Particle.DustOptions(Color.fromRGB(70, 66, 64), 1.4f)
    /** 宙に浮いている間、真下の地面に落ちる影 */
    val SHADOW = Particle.DustOptions(Color.fromRGB(28, 8, 12), 1.5f)

    // ===== 予告 =====

    /** 跳躍斬りの着地点: 縁の輪と、progress（0〜1）に合わせて広がる内側の輪 */
    fun warnCircle(center: Location, radius: Double, progress: Double) {
        ring(center, radius, WARN_EDGE)
        if (progress > 0.02) ring(center, radius * progress, WARN_FILL)
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

    // ===== 大剣の衝撃 =====

    /** 大剣を地面に叩きつけた所: 土煙・砕けた地面・赤い火花 */
    fun groundBurst(at: Location, radius: Double) {
        val world = at.world
        val below = at.clone().add(0.0, -0.5, 0.0).block
        val n = (radius * radius * 3).toInt().coerceIn(12, 100)
        if (!below.isPassable) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.3, 0.0), n, radius * 0.45, 0.2, radius * 0.45, 0.0, below.blockData)
        }
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0.0, 0.3, 0.0), (radius * 3).toInt().coerceIn(4, 24), radius * 0.4, 0.2, radius * 0.4, 0.02)
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.4, 0.0), 24, radius * 0.4, 0.2, radius * 0.4, 0.0, CRACK)
        world.spawnParticle(Particle.EXPLOSION, at.clone().add(0.0, 0.5, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** 地割れが1ブロック進んだ所: 割れた地面が跳ね、赤い光が噴き出す */
    fun crackBurst(at: Location, width: Double) {
        val world = at.world
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.2, 0.0), 16, width * 0.35, 0.15, width * 0.35, 0.0, below.blockData)
        }
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.5, 0.0), 10, width * 0.3, 0.4, width * 0.3, 0.0, CRACK)
        world.spawnParticle(Particle.LAVA, at.clone().add(0.0, 0.2, 0.0), 1, 0.2, 0.0, 0.2, 0.0)
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0.0, 0.2, 0.0), 1, 0.2, 0.0, 0.2, 0.01)
    }

    /** 地割れの先端が次に進むまでの間、ちらちら光る */
    fun crackGlow(at: Location) {
        at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.15, 0.0), 2, 0.25, 0.02, 0.25, 0.0, CRACK)
    }

    // ===== 斬撃波 =====

    /** 斬撃波（進む向きに膨らんだ三日月）。fade（0〜1）が大きいほど薄く */
    fun crescent(center: Location, dir: Vector, width: Double, fade: Double) {
        val world = center.world
        val side = Vector(-dir.z, 0.0, dir.x)
        val half = width / 2
        val n = (width * 4).toInt().coerceIn(8, 40)
        val count = if (fade > 0.7) 1 else 2
        for (i in 0..n) {
            val s = -1.0 + 2.0 * i / n                     // -1〜1（端から端）
            val bulge = (1 - s * s) * 0.9                  // 真ん中ほど前へ膨らむ
            val p = center.clone().add(side.clone().multiply(s * half)).add(dir.clone().multiply(bulge))
            world.spawnParticle(Particle.DUST, p.x, p.y + (1 - s * s) * 0.3, p.z, count, 0.04, 0.12, 0.04, 0.0, CRESCENT)
            if (i % 2 == 0) world.spawnParticle(Particle.DUST, p.x, p.y + 0.1, p.z, 1, 0.0, 0.05, 0.0, 0.0, CRESCENT_CORE)
        }
        if (Random.nextInt(3) == 0) world.spawnParticle(Particle.SWEEP_ATTACK, center, 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** 斬撃波が壁に当たって砕ける */
    fun crescentBreak(at: Location) {
        at.world.spawnParticle(Particle.DUST, at, 30, 0.6, 0.6, 0.6, 0.0, CRESCENT)
        at.world.spawnParticle(Particle.CRIT, at, 12, 0.4, 0.4, 0.4, 0.3)
    }

    // ===== 体 =====

    /** 本気の間、マントからこぼれる赤い塵 */
    fun rageAura(e: LivingEntity) {
        val at = e.location.add(0.0, e.height * 0.55, 0.0)
        at.world.spawnParticle(Particle.DUST, at, 3, e.width * 0.6, e.height * 0.35, e.width * 0.6, 0.0, AURA)
        if (Random.nextInt(3) == 0) at.world.spawnParticle(Particle.FALLING_LAVA, at, 1, e.width * 0.4, e.height * 0.3, e.width * 0.4, 0.0)
    }

    /** 本気の溜め: 周りから赤い塵が吸い寄せられる（progress = 0〜1） */
    fun gather(e: LivingEntity, progress: Double) {
        val center = e.location.add(0.0, e.height * 0.5, 0.0)
        val radius = 4.0 - 2.5 * progress
        repeat(10) {
            val a = Random.nextDouble(Math.PI * 2)
            val p = center.clone().add(cos(a) * radius, Random.nextDouble(-1.2, 1.2), sin(a) * radius)
            center.world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, AURA)
        }
    }

    /** 本気になる瞬間: 赤い衝撃が広がる */
    fun rageBurst(e: LivingEntity, radius: Double) {
        val at = e.location
        ring(at, radius, WARN_FILL)
        ring(at, radius * 0.6, WARN_EDGE)
        at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, e.height * 0.6, 0.0), 60, e.width, e.height * 0.4, e.width, 0.0, AURA)
        at.world.spawnParticle(Particle.EXPLOSION, at.clone().add(0.0, 1.0, 0.0), 2, 0.5, 0.3, 0.5, 0.0)
    }

    /** 登場: 足元から灰と赤い塵が舞い上がる */
    fun rise(at: Location, progress: Double) {
        at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.3, 0.0), 6, 1.2, 0.2, 1.2, 0.0, ASH)
        at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.5 + progress * 2, 0.0), 3, 0.8, 0.6, 0.8, 0.0, AURA)
    }

    /**
     * 溜め斬りの溜め: 右後ろの低い所へ引いた大剣に沿って赤い光が集まり、切っ先が地面をこすって火花が散る
     * （progress = 0〜1。溜まるほど明るく、多く）
     */
    fun charge(e: LivingEntity, progress: Double) {
        val at = e.location
        val world = at.world
        val yaw = Math.toRadians(at.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw))
        val right = Vector(-forward.z, 0.0, forward.x)
        val scale = e.height / 3.5
        // 柄（腰の後ろ）から切っ先（右後ろの地面）まで
        val grip = at.clone().add(0.0, 1.5 * scale, 0.0).add(forward.clone().multiply(-0.5 * scale))
        val tip = at.clone().add(0.0, 0.1, 0.0).add(right.clone().multiply(1.5 * scale)).add(forward.clone().multiply(-1.9 * scale))
        val n = (4 + progress * 8).toInt()
        val dust = if (progress < 0.6) WARN_EDGE else WARN_FILL
        repeat(n) {
            val f = Random.nextDouble()
            val p = grip.clone().add(tip.toVector().subtract(grip.toVector()).multiply(f))
            world.spawnParticle(Particle.DUST, p, 1, 0.08, 0.08, 0.08, 0.0, dust)
        }
        if (Random.nextDouble() < 0.3 + progress * 0.5) world.spawnParticle(Particle.CRIT, tip, 3, 0.2, 0.05, 0.2, 0.15)
        if (progress > 0.7) world.spawnParticle(Particle.SMALL_FLAME, tip, 1, 0.15, 0.05, 0.15, 0.01)
        // 周りから赤い塵が吸い寄せられる
        val r = 2.6 - 1.6 * progress
        repeat(3) {
            val a = Random.nextDouble(Math.PI * 2)
            world.spawnParticle(Particle.DUST, grip.clone().add(cos(a) * r, Random.nextDouble(-0.6, 0.8), sin(a) * r), 1, 0.0, 0.0, 0.0, 0.0, AURA)
        }
    }

    /** 浮き上がる瞬間: 足元から灰が輪になって吹き散る */
    fun liftOff(at: Location) {
        ring(at, 1.6, WARN_EDGE)
        at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.3, 0.0), 24, 1.2, 0.15, 1.2, 0.0, ASH)
        at.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0.0, 0.2, 0.0), 6, 0.8, 0.1, 0.8, 0.02)
        at.world.spawnParticle(Particle.SOUL, at.clone().add(0.0, 0.4, 0.0), 6, 0.8, 0.2, 0.8, 0.02)
    }

    /**
     * 宙に浮いている間: マントの裾から灰と赤い塵がこぼれ、真下の地面に暗い影が落ちる（groundY は真下の地面の高さ）
     */
    fun floatTrail(e: LivingEntity, groundY: Double) {
        val at = e.location
        val world = at.world
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.2, 0.0), 3, e.width * 0.5, 0.3, e.width * 0.5, 0.0, ASH)
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, e.height * 0.4, 0.0), 2, e.width * 0.6, e.height * 0.3, e.width * 0.6, 0.0, AURA)
        if (Random.nextInt(3) == 0) world.spawnParticle(Particle.ASH, at, 4, e.width * 0.5, 0.2, e.width * 0.5, 0.0)
        val shadow = Location(world, at.x, groundY, at.z)
        ring(shadow, 1.1, SHADOW)
        world.spawnParticle(Particle.DUST, shadow.clone().add(0.0, 0.1, 0.0), 4, 0.5, 0.0, 0.5, 0.0, SHADOW)
    }

    /** 宙から降り立った瞬間: 足元の土煙 */
    fun touchDown(at: Location) {
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) {
            at.world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.2, 0.0), 20, 0.9, 0.1, 0.9, 0.0, below.blockData)
        }
        at.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0.0, 0.2, 0.0), 5, 0.7, 0.1, 0.7, 0.02)
        at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.3, 0.0), 14, 1.0, 0.1, 1.0, 0.0, ASH)
    }

    /** 死亡: 灰になって崩れる */
    fun crumble(e: LivingEntity, progress: Double) {
        val at = e.location.add(0.0, e.height * (0.6 - 0.4 * progress), 0.0)
        at.world.spawnParticle(Particle.DUST, at, 8, e.width * 0.6, e.height * 0.3, e.width * 0.6, 0.0, ASH)
        at.world.spawnParticle(Particle.ASH, at, 10, e.width * 0.6, e.height * 0.3, e.width * 0.6, 0.0)
        if (Random.nextInt(2) == 0) at.world.spawnParticle(Particle.DUST, at, 2, e.width * 0.5, e.height * 0.3, e.width * 0.5, 0.0, AURA)
    }

    // ===== 打ち上げ・空中連撃 =====

    /** 斬り上げの軌跡（前方の扇に、下から上へ赤い弧） */
    fun upwardArc(e: LivingEntity, radius: Double) {
        val at = e.location
        val forward = at.direction.setY(0).normalize()
        val world = at.world
        for (i in 0..14) {
            val f = i / 14.0
            val a = Math.toRadians(-60.0 + 120.0 * f)
            val d = forward.clone().rotateAroundY(a).multiply(radius * 0.75)
            world.spawnParticle(Particle.DUST, at.x + d.x, at.y + 0.3 + 3.5 * f, at.z + d.z, 2, 0.05, 0.1, 0.05, 0.0, CRESCENT)
        }
        world.spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(forward.clone().multiply(2.0)).add(0.0, 1.5, 0.0), 2, 0.4, 0.6, 0.4, 0.0)
    }

    /** 打ち上げられた所: 土煙が噴き上がる */
    fun launchBurst(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.CLOUD, at.clone().add(0.0, 0.3, 0.0), 20, 0.4, 0.2, 0.4, 0.15)
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 1.0, 0.0), 20, 0.3, 1.0, 0.3, 0.0, CRESCENT)
        world.spawnParticle(Particle.EXPLOSION, at.clone().add(0.0, 0.5, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** 跳び立つ・着地する所の小さな土煙 */
    fun groundBurstSmall(at: Location) {
        val world = at.world
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.2, 0.0), 30, 1.0, 0.1, 1.0, 0.0, below.blockData)
        world.spawnParticle(Particle.CLOUD, at.clone().add(0.0, 0.2, 0.0), 14, 0.8, 0.1, 0.8, 0.05)
    }

    /** 空へ追いかける間の残像（赤い塵） */
    fun airTrail(e: LivingEntity) {
        val at = e.location.add(0.0, e.height * 0.5, 0.0)
        at.world.spawnParticle(Particle.DUST, at, 10, e.width * 0.4, e.height * 0.4, e.width * 0.4, 0.0, AURA)
        at.world.spawnParticle(Particle.CLOUD, e.location, 3, 0.3, 0.1, 0.3, 0.02)
    }

    /** 空中の一太刀（n で斬る向きを変える） */
    fun airSlash(at: Location, n: Int) {
        val world = at.world
        val tilt = if (n % 2 == 0) 1.0 else -1.0
        for (i in -6..6) {
            val f = i / 6.0
            world.spawnParticle(Particle.DUST, at.x + f * 1.4, at.y + f * 0.9 * tilt, at.z + f * 0.3 * tilt, 1, 0.0, 0.0, 0.0, 0.0, CRESCENT_CORE)
        }
        world.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.CRIT, at, 10, 0.3, 0.3, 0.3, 0.4)
    }

    /** 叩き落とす一撃 */
    fun smashDown(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.EXPLOSION, at.clone().add(0.0, 1.0, 0.0), 2, 0.3, 0.3, 0.3, 0.0)
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 1.0, 0.0), 30, 0.5, 0.8, 0.5, 0.0, CRESCENT)
    }

    // ===== 溜め刺し =====

    /** 踏み込みの土煙 */
    fun dashDust(at: Location) {
        at.world.spawnParticle(Particle.CLOUD, at.clone().add(0.0, 0.2, 0.0), 4, 0.4, 0.05, 0.4, 0.02)
    }

    /** 刺さった瞬間 */
    fun impale(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.DUST, at, 30, 0.3, 0.3, 0.3, 0.0, CRESCENT)
        world.spawnParticle(Particle.CRIT, at, 20, 0.3, 0.3, 0.3, 0.5)
        world.spawnParticle(Particle.DAMAGE_INDICATOR, at, 6, 0.3, 0.3, 0.3, 0.1)
    }

    /** 刺さっている相手から散る赤い塵 */
    fun impaleTrail(at: Location) {
        at.world.spawnParticle(Particle.DUST, at, 4, 0.25, 0.25, 0.25, 0.0, CRESCENT)
    }

    /** 投げ飛ばした瞬間 */
    fun throwBurst(at: Location) {
        at.world.spawnParticle(Particle.SWEEP_ATTACK, at, 3, 0.5, 0.5, 0.5, 0.0)
        at.world.spawnParticle(Particle.CLOUD, at, 16, 0.4, 0.4, 0.4, 0.1)
    }

    // ===== 怨霊 =====

    /** 怨霊の色（青白い魂と、暗い青緑の尾） */
    val WRAITH_CORE = Particle.DustOptions(Color.fromRGB(170, 240, 255), 1.3f)
    val WRAITH_TAIL = Particle.DustOptions(Color.fromRGB(30, 80, 90), 1.6f)

    /** 飛んでいる怨霊（青白い核・魂の炎・後ろへ流れる暗い尾） */
    fun wraith(at: Location, dir: Vector, age: Int) {
        val world = at.world
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 2, 0.08, 0.08, 0.08, 0.01)
        world.spawnParticle(Particle.DUST, at, 2, 0.1, 0.1, 0.1, 0.0, WRAITH_CORE)
        val back = at.clone().subtract(dir.clone().multiply(0.5))
        world.spawnParticle(Particle.DUST, back, 2, 0.15, 0.15, 0.15, 0.0, WRAITH_TAIL)
        if (age % 3 == 0) world.spawnParticle(Particle.SOUL, back, 1, 0.1, 0.1, 0.1, 0.01)
        if (age % 7 == 0) world.spawnParticle(Particle.SCULK_SOUL, at, 1, 0.05, 0.05, 0.05, 0.01)
    }

    /** 怨霊がはじける */
    fun wraithBurst(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.SOUL, at, 12, 0.3, 0.3, 0.3, 0.05)
        world.spawnParticle(Particle.DUST, at, 16, 0.3, 0.3, 0.3, 0.0, WRAITH_CORE)
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 8, 0.2, 0.2, 0.2, 0.05)
    }

    /** 怨霊が消えていく */
    fun wraithFade(at: Location) {
        at.world.spawnParticle(Particle.SOUL, at, 4, 0.2, 0.2, 0.2, 0.02)
    }

    /** 詠唱の間、体へ魂が集まる */
    fun wraithGather(e: LivingEntity) {
        val center = e.location.add(0.0, e.height * 0.6, 0.0)
        repeat(4) {
            val a = Random.nextDouble(Math.PI * 2)
            val p = center.clone().add(cos(a) * 2.5, Random.nextDouble(-1.0, 1.0), sin(a) * 2.5)
            center.world.spawnParticle(Particle.SOUL, p, 0, center.x - p.x, center.y - p.y, center.z - p.z, 0.08)
        }
    }

    /** 怨霊が体から飛び出す */
    fun wraithRelease(at: Location) {
        at.world.spawnParticle(Particle.SCULK_SOUL, at, 4, 0.2, 0.2, 0.2, 0.03)
        at.world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 6, 0.2, 0.2, 0.2, 0.03)
    }

    // ===== 瀕死 =====

    /** 吹き飛ばしの前: 四方の地面から魂が吸い込まれ、だんだん増える */
    fun lastStandGather(e: LivingEntity, progress: Double) {
        val center = e.location.add(0.0, e.height * 0.5, 0.0)
        repeat(4 + (progress * 14).toInt()) {
            val a = Random.nextDouble(Math.PI * 2)
            val r = 3.0 + Random.nextDouble(6.0)
            val p = center.clone().add(cos(a) * r, -e.height * 0.4 + Random.nextDouble(2.0), sin(a) * r)
            center.world.spawnParticle(Particle.SOUL, p, 0, center.x - p.x, center.y - p.y, center.z - p.z, 0.1)
        }
        ring(e.location, 9.0 * (1.0 - progress), WRAITH_CORE)
    }

    /** 全員を吹き飛ばす絶叫: 何重もの輪と、四方へ飛び散る魂の壁 */
    fun lastStandBlast(e: LivingEntity) {
        val at = e.location
        for (r in 1..5) ring(at, r * 2.5, if (r % 2 == 0) WRAITH_TAIL else WRAITH_CORE)
        val center = at.clone().add(0.0, e.height * 0.5, 0.0)
        for (i in 0 until 72) {
            val a = Math.PI * 2 * i / 72
            val from = center.clone().add(0.0, Random.nextDouble(-2.0, 1.5), 0.0)
            at.world.spawnParticle(Particle.SOUL_FIRE_FLAME, from, 0, cos(a), 0.05, sin(a), 0.9)
            at.world.spawnParticle(Particle.SOUL, from, 0, cos(a), 0.1, sin(a), 0.5)
        }
        at.world.spawnParticle(Particle.EXPLOSION, center, 4, 1.0, 0.8, 1.0, 0.0)
    }

    /** 弾幕の間: 体のまわりを怨霊が3本の渦になって昇る */
    fun lastStandHalo(e: LivingEntity, age: Int) {
        val at = e.location
        for (i in 0 until 3) {
            val a = age * 0.16 + Math.PI * 2 * i / 3
            for (j in 0 until 3) {
                val h = ((age * 0.025 + j / 3.0 + i / 9.0) % 1.0)
                val r = 2.4 - h
                val p = at.clone().add(cos(a + h * 4) * r, 0.3 + h * e.height, sin(a + h * 4) * r)
                at.world.spawnParticle(Particle.SOUL_FIRE_FLAME, p, 1, 0.0, 0.0, 0.0, 0.0)
                at.world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0.0, WRAITH_TAIL)
            }
        }
    }

    /** 怨霊の束が届く直前の合図: 狙われた人の足元に輪 */
    fun volleyCue(e: LivingEntity) = ring(e.location, 0.9, WRAITH_CORE)

    /** 攻撃が効かなかった */
    fun warded(e: LivingEntity) {
        val at = e.location.add(0.0, e.height * 0.55, 0.0)
        at.world.spawnParticle(Particle.SCULK_SOUL, at, 4, 0.6, 0.8, 0.6, 0.03)
    }

    // ===== 雷 =====

    val LIGHTNING_EDGE = Particle.DustOptions(Color.fromRGB(120, 170, 255), 1.4f)
    val LIGHTNING_FILL = Particle.DustOptions(Color.fromRGB(230, 240, 255), 1.6f)

    /** 雷の予告: 青い輪と、満ちていく白い輪。火花が散る */
    fun lightningWarn(center: Location, radius: Double, progress: Double) {
        ring(center, radius, LIGHTNING_EDGE)
        if (progress > 0.02) ring(center, radius * progress, LIGHTNING_FILL)
        center.world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0.0, 0.3, 0.0), 3, radius * 0.4, 0.1, radius * 0.4, 0.05)
    }

    /** 雷が落ちた所 */
    fun lightningImpact(at: Location, radius: Double) {
        val world = at.world
        world.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0.0, 0.5, 0.0), 40, radius * 0.5, 0.5, radius * 0.5, 0.3)
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0.0, 0.2, 0.0), 6, radius * 0.3, 0.1, radius * 0.3, 0.02)
        val below = at.clone().add(0.0, -0.5, 0.0).block
        if (!below.isPassable) world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.2, 0.0), 20, radius * 0.4, 0.1, radius * 0.4, 0.0, below.blockData)
    }

    /** 雷を呼んでいる間、掲げた大剣の切っ先に火花 */
    fun channelSpark(boss: KnightBoss) {
        val tip = boss.bladePoint(1.0) ?: boss.entity.location.add(0.0, boss.entity.height + 2.0, 0.0)
        tip.world.spawnParticle(Particle.ELECTRIC_SPARK, tip, 6, 0.3, 0.3, 0.3, 0.2)
        tip.world.spawnParticle(Particle.END_ROD, tip, 1, 0.2, 0.2, 0.2, 0.02)
    }
}
