package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 燃える地面（火球の着弾・ブレスの跡）。しばらく残り、中に立っている相手を焼く。
 * ブロックの火は置かない（地形を壊さない）。パーティクルだけ。
 *
 * 技が終わっても燃え続けるので、ボスの毎tickで [tick] を呼ぶ
 */
class DragonFireField(private val boss: Boss) {

    /** 燃える地面の性能 */
    data class Burn(
        val radius: Double,
        val ticks: Int,
        val damage: Double,
        val intervalTicks: Int,
        val fireTicks: Int,
    ) {
        val enabled: Boolean get() = radius > 0 && ticks > 0
    }

    private class Patch(val center: Location, val burn: Burn) {
        var age = 0
    }

    private val patches = mutableListOf<Patch>()

    /** 燃える地面を置く（center は地面の高さ） */
    fun ignite(center: Location, burn: Burn) {
        if (!burn.enabled) return
        // 近くに燃えている所があれば、新しく置かずに燃える時間だけ延ばす（パーティクルを増やしすぎない）
        val near = patches.firstOrNull {
            it.center.world == center.world && it.center.distanceSquared(center) < (burn.radius * 0.6).let { r -> r * r }
        }
        if (near != null) {
            near.age = 0
            return
        }
        patches += Patch(center.clone(), burn)
        if (patches.size > MAX_PATCHES) patches.removeAt(0)
    }

    fun tick() {
        val iter = patches.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.age++
            if (p.age > p.burn.ticks) {
                iter.remove()
                continue
            }
            draw(p)
            if (p.age % p.burn.intervalTicks.coerceAtLeast(1) == 0) burnTargets(p)
        }
    }

    fun clear() = patches.clear()

    private fun draw(p: Patch) {
        val world = p.center.world
        val r = p.burn.radius
        // 消える前の1秒はだんだん弱くなる
        val fade = ((p.burn.ticks - p.age) / 20.0).coerceIn(0.2, 1.0)
        val count = (r * r * 1.2 * fade).toInt().coerceAtLeast(1)
        repeat(count) {
            val d = r * sqrt(Random.nextDouble())
            val a = Random.nextDouble(Math.PI * 2)
            val at = p.center.clone().add(Math.cos(a) * d, 0.1, Math.sin(a) * d)
            world.spawnParticle(Particle.FLAME, at, 0, 0.0, 1.0, 0.0, 0.04 + Random.nextDouble(0.04))
        }
        if (p.age % 4 == 0) world.spawnParticle(Particle.LARGE_SMOKE, p.center.clone().add(0.0, 0.4, 0.0), 1, r * 0.4, 0.2, r * 0.4, 0.01)
        if (p.age % 6 == 0) world.spawnParticle(Particle.LAVA, p.center, 1, r * 0.4, 0.0, r * 0.4, 0.0)
        if (p.age % 20 == 1) world.playSound(p.center, Sound.BLOCK_FIRE_AMBIENT, SoundCategory.HOSTILE, 0.8f, 0.9f)
    }

    private fun burnTargets(p: Patch) {
        val r = p.burn.radius
        for (e in p.center.world.getNearbyEntities(p.center, r, 1.6, r)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            val dx = e.location.x - p.center.x
            val dz = e.location.z - p.center.z
            if (dx * dx + dz * dz > r * r) continue
            if (e.location.y - p.center.y > 1.2 || p.center.y - e.location.y > 0.8) continue
            val victim = e as LivingEntity
            victim.damage(p.burn.damage, boss.entity)
            if (p.burn.fireTicks > 0) victim.fireTicks = maxOf(victim.fireTicks, p.burn.fireTicks)
        }
    }

    private companion object {
        /** 同時に燃やしておける場所の数（多すぎるとパーティクルが重い） */
        const val MAX_PATCHES = 24
    }
}
