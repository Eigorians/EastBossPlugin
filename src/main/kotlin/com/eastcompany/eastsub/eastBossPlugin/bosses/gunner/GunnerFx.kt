package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner

import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.random.Random

/** ガンナーの見た目の演出（マズルフラッシュ・レーザーサイト・薬莢など）。音は GunnerSounds */
object GunnerFx {

    val LASER = Particle.DustOptions(Color.fromRGB(255, 40, 40), 0.45f)
    val LASER_LOCK = Particle.DustOptions(Color.fromRGB(255, 255, 255), 0.6f)
    private val FLASH = Particle.DustOptions(Color.fromRGB(255, 220, 120), 1.6f)
    private val FLASH_CORE = Particle.DustOptions(Color.fromRGB(255, 255, 230), 1.0f)
    private val BEAM = Particle.DustOptions(Color.fromRGB(255, 250, 225), 1.5f)
    private val BEAM_EDGE = Particle.DustOptions(Color.fromRGB(255, 170, 70), 1.0f)
    private val CASING = ItemStack.of(Material.GOLD_NUGGET)

    /** 銃口の火花と煙。big なら狙撃・ガトリング用の大きいもの */
    fun muzzleFlash(at: Location, dir: Vector, big: Boolean = false) {
        val world = at.world
        val d = dir.clone().normalize()
        val n = if (big) 3 else 2
        for (i in 0 until n) {
            val p = at.clone().add(d.clone().multiply(0.25 * i))
            world.spawnParticle(Particle.DUST, p, 2, 0.04, 0.04, 0.04, 0.0, FLASH)
        }
        world.spawnParticle(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0, FLASH_CORE)
        world.spawnParticle(Particle.SMALL_FLAME, at, if (big) 6 else 2, 0.05, 0.05, 0.05, 0.03)
        world.spawnParticle(Particle.SMOKE, at, if (big) 8 else 3, 0.06, 0.06, 0.06, 0.02)
        if (big) {
            world.spawnParticle(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(255, 230, 160))
            world.spawnParticle(Particle.POOF, at, 6, 0.1, 0.1, 0.1, 0.05)
        }
    }

    /** 薬莢が横に飛ぶ */
    fun casing(at: Location, side: Vector) {
        val v = side.clone().normalize().multiply(0.25).add(Vector(Random.nextDouble(-0.05, 0.05), 0.2, Random.nextDouble(-0.05, 0.05)))
        // ITEM パーティクルは count 0 のとき offset が速度になる
        at.world.spawnParticle(Particle.ITEM, at, 0, v.x, v.y, v.z, 1.0, CASING)
    }

    /** 空薬莢がこぼれ落ちる（リロード） */
    fun dropCasing(at: Location) {
        val v = Vector(Random.nextDouble(-0.06, 0.06), Random.nextDouble(0.0, 0.08), Random.nextDouble(-0.06, 0.06))
        at.world.spawnParticle(Particle.ITEM, at, 0, v.x, v.y, v.z, 1.0, CASING)
    }

    /**
     * レーザーサイト（細い線）。ブロックに当たるとそこで止まる。
     * @return 線の終わりの位置
     */
    fun laser(from: Location, dir: Vector, maxLength: Double, dust: Particle.DustOptions, step: Double = 0.35): Location {
        val end = blockEnd(from, dir, maxLength)
        line(from, end, dust, step)
        end.world.spawnParticle(Particle.DUST, end, 2, 0.03, 0.03, 0.03, 0.0, dust)
        return end
    }

    /** from から dir へ、ブロックに当たる所（無ければ maxLength 先） */
    fun blockEnd(from: Location, dir: Vector, maxLength: Double): Location {
        val d = dir.clone().normalize()
        val hit = from.world.rayTraceBlocks(from, d, maxLength, FluidCollisionMode.NEVER, true)
        return hit?.hitPosition?.toLocation(from.world) ?: from.clone().add(d.multiply(maxLength))
    }

    fun line(from: Location, to: Location, dust: Particle.DustOptions, step: Double) {
        val world = from.world
        val v = to.toVector().subtract(from.toVector())
        val len = v.length()
        if (len < 1e-3) return
        v.multiply(1 / len)
        var t = 0.0
        while (t <= len) {
            world.spawnParticle(Particle.DUST, from.clone().add(v.clone().multiply(t)), 1, 0.0, 0.0, 0.0, 0.0, dust)
            t += step
        }
    }

    /** 狙撃の弾道（一瞬だけ見える太い光の線） */
    fun sniperBeam(from: Location, to: Location) {
        line(from, to, BEAM, 0.3)
        val world = from.world
        val v = to.toVector().subtract(from.toVector())
        val len = v.length()
        if (len < 1e-3) return
        v.multiply(1 / len)
        var t = 0.0
        while (t <= len) {
            val p = from.clone().add(v.clone().multiply(t))
            world.spawnParticle(Particle.DUST, p, 1, 0.12, 0.12, 0.12, 0.0, BEAM_EDGE)
            if (t.toInt() % 2 == 0) world.spawnParticle(Particle.END_ROD, p, 1, 0.05, 0.05, 0.05, 0.02)
            t += 0.9
        }
        world.spawnParticle(Particle.SONIC_BOOM, from.clone().add(v.clone().multiply(1.2)), 1, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.EXPLOSION, to, 1, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.LAVA, to, 4, 0.2, 0.2, 0.2, 0.0)
    }

    /** 地面に輪を描く（手榴弾の爆発範囲など） */
    fun ring(center: Location, radius: Double, dust: Particle.DustOptions) {
        val world = center.world
        val n = (radius * Math.PI * 2.2).toInt().coerceIn(10, 80)
        for (i in 0 until n) {
            val a = Math.PI * 2 * i / n
            world.spawnParticle(
                Particle.DUST, center.x + Math.cos(a) * radius, center.y + 0.12, center.z + Math.sin(a) * radius,
                1, 0.0, 0.0, 0.0, 0.0, dust,
            )
        }
    }

    /** 狙撃が盾を撃ち抜いた: 盾の木くずが散り、割れる音 */
    fun shieldPierced(victim: LivingEntity) {
        val at = victim.eyeLocation.add(victim.location.direction.multiply(0.6)).add(0.0, -0.4, 0.0)
        val world = at.world
        world.spawnParticle(Particle.BLOCK, at, 20, 0.2, 0.2, 0.2, 0.0, Material.OAK_PLANKS.createBlockData())
        world.spawnParticle(Particle.CRIT, at, 12, 0.15, 0.15, 0.15, 0.4)
        world.playSound(at, Sound.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1.2f, 1.3f)
    }
}
