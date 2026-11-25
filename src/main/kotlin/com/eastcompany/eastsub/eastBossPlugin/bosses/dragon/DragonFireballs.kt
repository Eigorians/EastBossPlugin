package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Reflect
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * ドラゴンの吐く火球。口から狙った地点へまっすぐ飛び、着弾すると爆発して地面を燃やす。
 *
 * - 目に見える速さで飛ぶ（燃える岩のブロックと炎の尾）。着く地点には赤い輪の予告が出る
 * - 途中でブロックに当たると、そこで爆発する（物陰に隠れればかわせる）
 * - 技が終わっても飛び続ける（ボスの毎tickで [tick] を呼ぶ。ボスが消えるときは [clear]）
 * - 弾き返し: 火球が reflect-radius 以内まで来たときにジャストガードすると、ドラゴンへ跳ね返る（skill.util.Reflect）。
 *   跳ね返った火球はドラゴンを追いかけて当たり、reflect-damage のダメージと、撃ち落としのたまり（reflect-knockdown）を与える
 */
class DragonFireballs(private val boss: Boss, private val fireField: DragonFireField) {

    /** 火球の性能（技の設定の fireball セクション） */
    data class Spec(
        /** 1tick に進む距離（ブロック） */
        val speed: Double,
        val damage: Double,
        val explosionRadius: Double,
        val fireTicks: Int,
        val knockback: Double,
        /** 着弾した所の燃える地面 */
        val burn: DragonFireField.Burn,
        /** 見た目のブロックと大きさ */
        val block: Material,
        val size: Float,
        /** 弾き返せるか */
        val reflectable: Boolean,
        /** 火球がこの距離まで来たときにジャストガードすると弾き返す（ブロック） */
        val reflectRadius: Double,
        /** 跳ね返った火球の速さ（ブロック/tick） */
        val reflectSpeed: Double,
        /** 跳ね返った火球がドラゴンに当たったときのダメージ */
        val reflectDamage: Double,
        /** 跳ね返った火球が当たったときにたまる撃ち落とし（撃ち落としに必要な量に対する割合。ダメージのぶんとは別） */
        val reflectKnockdown: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Spec = Spec(
                speed = section.double(Names.SkillConfig.SPEED, 0.8).coerceAtLeast(0.1),
                damage = section.double(Names.SkillConfig.DAMAGE, 7.0),
                explosionRadius = section.double(Names.SkillConfig.EXPLOSION_RADIUS, 3.0),
                fireTicks = section.int(Names.SkillConfig.FIRE_TICKS, 60),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.6),
                burn = burnOf(section),
                block = Material.matchMaterial(section.string(Names.SkillConfig.BLOCK, "MAGMA_BLOCK"))
                    ?.takeIf { it.isBlock } ?: Material.MAGMA_BLOCK,
                size = section.float(Names.SkillConfig.SIZE, 1.1f),
                reflectable = section.boolean(Names.SkillConfig.REFLECTABLE, true),
                reflectRadius = section.double(Names.SkillConfig.REFLECT_RADIUS, 2.5),
                reflectSpeed = section.double(Names.SkillConfig.REFLECT_SPEED, 1.4).coerceAtLeast(0.2),
                reflectDamage = section.double(Names.SkillConfig.REFLECT_DAMAGE, 30.0),
                reflectKnockdown = section.double(Names.SkillConfig.REFLECT_KNOCKDOWN, 0.3),
            )

            /** 燃える地面の設定（burn-radius / burn-ticks / burn-damage / burn-interval-ticks / fire-ticks） */
            fun burnOf(section: ConfigSection): DragonFireField.Burn = DragonFireField.Burn(
                radius = section.double(Names.SkillConfig.BURN_RADIUS, 2.5),
                ticks = section.int(Names.SkillConfig.BURN_TICKS, 60),
                damage = section.double(Names.SkillConfig.BURN_DAMAGE, 2.0),
                intervalTicks = section.int(Names.SkillConfig.BURN_INTERVAL_TICKS, 10).coerceAtLeast(1),
                fireTicks = section.int(Names.SkillConfig.FIRE_TICKS, 60),
            )
        }
    }

    private class Ball(
        val pos: Location,
        val target: Location,
        val dir: Vector,
        val spec: Spec,
        val display: BlockDisplay,
    ) {
        var age = 0
        val total: Double = pos.distance(target)
        var travelled = 0.0
        /** 弾き返した人（跳ね返っている間） */
        var reflectedBy: Player? = null
        var reflectAge = 0
    }

    private val balls = mutableListOf<Ball>()

    /**
     * from から target（地面の点）へ火球を撃つ。
     * @return 撃った火球が着くまでの tick
     */
    fun launch(from: Location, target: Location, spec: Spec): Int {
        val dirVec = target.toVector().subtract(from.toVector())
        if (dirVec.lengthSquared() < 1e-4) return 0
        val world = from.world
        val half = spec.size / 2
        val display = world.spawn(from, BlockDisplay::class.java) { d ->
            d.isPersistent = false
            d.block = spec.block.createBlockData()
            d.brightness = Display.Brightness(15, 15)
            d.teleportDuration = 1
            d.transformation = Transformation(
                Vector3f(-half, -half, -half), Quaternionf(), Vector3f(spec.size, spec.size, spec.size), Quaternionf(),
            )
        }
        val ball = Ball(from.clone(), target.clone(), dirVec.normalize(), spec, display)
        balls += ball
        world.playSound(from, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 2.0f, 0.6f)
        world.playSound(from, Sound.ITEM_FIRECHARGE_USE, SoundCategory.HOSTILE, 1.6f, 0.7f)
        world.spawnParticle(Particle.FLAME, from, 12, 0.2, 0.2, 0.2, 0.08)
        return (ball.total / spec.speed).toInt() + 1
    }

    fun tick() {
        // 着弾でプレイヤーが倒れるとボスが消えて clear() が呼ばれることがあるので、写しを回す
        for (b in balls.toList()) {
            if (b !in balls) continue
            b.age++
            if (step(b)) {
                balls.remove(b)
                b.display.remove()
            }
        }
    }

    fun clear() {
        balls.forEach { it.display.remove() }
        balls.clear()
    }

    /** 1tick 進める。着弾したら true */
    private fun step(b: Ball): Boolean {
        if (b.reflectedBy != null) return stepReflected(b)
        val world = b.pos.world
        // 弾き返し: 近くまで来たときに、誰かがジャストガードした
        if (b.spec.reflectable) {
            val by = Reflect.tryReflect(boss, b.pos, b.spec.reflectRadius)
            if (by != null) {
                b.reflectedBy = by
                world.playSound(b.pos, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1.6f, 1.4f)
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, b.pos, 16, 0.3, 0.3, 0.3, 0.12)
                return false
            }
        }
        val remain = b.total - b.travelled
        val move = minOf(b.spec.speed, remain)

        // 途中のブロックに当たったら、そこで爆発
        val hit = world.rayTraceBlocks(b.pos, b.dir, move, FluidCollisionMode.NEVER, true)
        if (hit != null) {
            explode(hit.hitPosition.toLocation(world).add(b.dir.clone().multiply(-0.3)), b.spec)
            return true
        }
        b.pos.add(b.dir.clone().multiply(move))
        b.travelled += move
        if (b.travelled >= b.total - 1e-3) {
            explode(b.target, b.spec)
            return true
        }

        // 見た目: 回りながら飛ぶ燃える岩と、炎の尾
        val at = b.pos.clone()
        at.yaw = (b.age * 31f) % 360f
        at.pitch = (b.age * 17f) % 180f - 90f
        b.display.teleport(at)
        world.spawnParticle(Particle.FLAME, b.pos, 5, 0.25, 0.25, 0.25, 0.02)
        world.spawnParticle(Particle.LARGE_SMOKE, b.pos, 1, 0.15, 0.15, 0.15, 0.01)
        if (b.age % 3 == 0) world.spawnParticle(Particle.LAVA, b.pos, 1, 0.1, 0.1, 0.1, 0.0)

        // 着く所の予告
        if (b.age % 2 == 0) {
            DragonFx.ring(b.target, b.spec.explosionRadius, DragonFx.WARN)
            DragonFx.ring(b.target, b.spec.explosionRadius * (b.travelled / b.total), DragonFx.WARN_INNER, 14)
        }
        return false
    }

    private companion object {
        /** 跳ね返った火球が、これだけ飛んでも当たらなければ消える（tick） */
        const val REFLECT_MAX_TICKS = 80
    }

    /** 跳ね返った火球: ドラゴンを追いかけて飛ぶ（ブロックはすり抜ける）。当たったら true */
    private fun stepReflected(b: Ball): Boolean {
        val world = b.pos.world
        val dragon = boss.entity
        val by = b.reflectedBy
        b.reflectAge++
        if (boss.isDying || !dragon.isValid || b.reflectAge > REFLECT_MAX_TICKS || by == null) {
            DragonFx.fireBurst(b.pos, 1.0)
            return true
        }
        val center = dragon.location.add(0.0, dragon.height * 0.5, 0.0)
        val v = center.toVector().subtract(b.pos.toVector())
        val reach = dragon.width * 0.5 + 0.8
        if (v.length() <= reach + b.spec.reflectSpeed) {
            DragonFx.fireBurst(center, 2.0)
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.0f, 0.9f)
            (boss as? DragonBoss)?.onFireballReflected(by, b.spec)
            return true
        }
        b.pos.add(v.normalize().multiply(b.spec.reflectSpeed))
        val at = b.pos.clone()
        at.yaw = (b.age * 47f) % 360f
        at.pitch = (b.age * 29f) % 180f - 90f
        b.age++
        b.display.teleport(at)
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, b.pos, 4, 0.2, 0.2, 0.2, 0.02)
        world.spawnParticle(Particle.FLAME, b.pos, 3, 0.2, 0.2, 0.2, 0.02)
        world.spawnParticle(Particle.ELECTRIC_SPARK, b.pos, 1, 0.1, 0.1, 0.1, 0.05)
        return false
    }

    private fun explode(at: Location, spec: Spec) {
        val world = at.world
        DragonFx.fireBurst(at, spec.explosionRadius)
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.6f, 1.1f)
        world.playSound(at, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 1.2f, 0.8f)

        val r = spec.explosionRadius
        for (e in world.getNearbyEntities(at, r, r, r)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            val victim = e as LivingEntity
            // 相手の当たり判定の箱までの距離で判定する
            val bb = victim.boundingBox
            val cx = at.x.coerceIn(bb.minX, bb.maxX)
            val cy = at.y.coerceIn(bb.minY, bb.maxY)
            val cz = at.z.coerceIn(bb.minZ, bb.maxZ)
            val dx = cx - at.x
            val dy = cy - at.y
            val dz = cz - at.z
            if (dx * dx + dy * dy + dz * dz > r * r) continue
            victim.noDamageTicks = 0
            victim.damage(spec.damage, boss.entity)
            if (spec.fireTicks > 0) victim.fireTicks = maxOf(victim.fireTicks, spec.fireTicks)
            val away = Vector(victim.location.x - at.x, 0.0, victim.location.z - at.z)
            if (away.lengthSquared() > 1e-4) victim.velocity = away.normalize().multiply(spec.knockback).setY(0.4)
        }

        // 着弾した所の地面を燃やす
        val ground = at.clone()
        val below = ground.clone().add(0.0, -0.2, 0.0).block
        if (below.isPassable) {
            // 宙で爆発した（壁に当たった）ときは、真下の地面を探す
            var y = at.blockY
            repeat(6) {
                if (!world.getBlockAt(at.blockX, y - 1, at.blockZ).isPassable) {
                    ground.y = y.toDouble()
                    fireField.ignite(ground, spec.burn)
                    return
                }
                y--
            }
            return
        }
        fireField.ignite(ground, spec.burn)
    }
}
