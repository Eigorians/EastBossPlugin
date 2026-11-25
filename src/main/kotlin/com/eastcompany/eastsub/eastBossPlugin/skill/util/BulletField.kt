package com.eastcompany.eastsub.eastBossPlugin.skill.util

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random

/**
 * ボスが撃つ弾（パーティクルで描く、目に見える速さの弾）。
 *
 * - 毎tick speed ブロック進み、ブロックに当たると止まる（壁の陰に隠れればかわせる）
 * - 当たり判定は相手の当たり判定の箱を hitRadius だけ広げたもの
 * - 撃った後は技が終わっても飛び続ける（[tick] をボスの毎tickで呼ぶこと）
 * - 当たらずに近くをかすめたプレイヤーには、かすめる音を聞かせる
 * - bounces が 1 以上なら、その回数までブロックで跳ね返る（跳弾。壁の陰でも安心できない）
 * - [fire] に onHit を渡すと、当たった相手ごとに呼ばれる（鈍足にするなど。ダメージの後）
 */
class BulletField(private val boss: Boss) {

    /** 弾の性能（技の設定の bullet セクション） */
    data class Spec(
        /** 1tick に進む距離（ブロック） */
        val speed: Double,
        val damage: Double,
        /** 相手の当たり判定をこれだけ広げて判定する */
        val hitRadius: Double,
        /** これだけ進んだら消える */
        val maxRange: Double,
        /** 狙いのぶれ（度） */
        val spreadDegrees: Double,
        /** 無敵時間を無視して当てる（連射の弾が続けて当たるように）。ガトリングなど弾の多い技では false */
        val ignoreInvulnerability: Boolean,
        /** 当たった相手を弾の向きに押す強さ */
        val knockback: Double,
        /** 弾の色 */
        val color: Color,
        /** 弾の太さ（パーティクルの大きさ） */
        val size: Float,
        /** ブロックで跳ね返る回数（0 なら跳ね返らずに止まる） */
        val bounces: Int = 0,
    ) {
        companion object {
            fun load(section: ConfigSection, def: Spec = DEFAULT): Spec = Spec(
                speed = section.double(Names.SkillConfig.SPEED, def.speed).coerceAtLeast(0.1),
                damage = section.double(Names.SkillConfig.DAMAGE, def.damage),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, def.hitRadius),
                maxRange = section.double(Names.SkillConfig.MAX_RANGE, def.maxRange),
                spreadDegrees = section.double(Names.SkillConfig.SPREAD_DEGREES, def.spreadDegrees),
                ignoreInvulnerability = section.boolean(Names.SkillConfig.IGNORE_INVULNERABILITY, def.ignoreInvulnerability),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, def.knockback),
                color = parseColor(section.string(Names.SkillConfig.COLOR), def.color),
                size = section.float(Names.SkillConfig.SIZE, def.size),
                bounces = section.int(Names.SkillConfig.BOUNCES, def.bounces).coerceAtLeast(0),
            )

            val DEFAULT = Spec(2.0, 3.0, 0.3, 40.0, 1.5, true, 0.15, Color.fromRGB(255, 214, 120), 0.7f)

            /** "#RRGGBB" または "R,G,B" */
            fun parseColor(raw: String, def: Color): Color {
                val s = raw.trim()
                if (s.isEmpty()) return def
                return runCatching {
                    if (s.startsWith("#")) Color.fromRGB(s.substring(1).toInt(16))
                    else s.split(",").map { it.trim().toInt() }.let { Color.fromRGB(it[0], it[1], it[2]) }
                }.getOrDefault(def)
            }
        }
    }

    private class Bullet(val pos: Location, val dir: Vector, val spec: Spec, val onHit: ((LivingEntity) -> Unit)?) {
        var travelled = 0.0
        var bouncesLeft = spec.bounces
        /** かすめる音を聞かせたプレイヤー */
        val whizzed = HashSet<UUID>()
    }

    private val bullets = mutableListOf<Bullet>()

    val isEmpty: Boolean get() = bullets.isEmpty()

    /**
     * 弾を撃つ。
     * @param from 銃口の位置
     * @param aim 狙う向き（長さは問わない）。spec.spreadDegrees だけランダムにぶれる
     * @param onHit 当たった相手ごとに呼ばれる（ダメージを与えた後）
     */
    fun fire(from: Location, aim: Vector, spec: Spec, onHit: ((LivingEntity) -> Unit)? = null) {
        if (aim.lengthSquared() < 1e-6) return
        val dir = spread(aim.clone().normalize(), spec.spreadDegrees)
        bullets += Bullet(from.clone(), dir, spec, onHit)
    }

    /** 毎tick呼ぶ。弾を進めて当たり判定をする */
    fun tick() {
        // 当たった相手が倒れるとボスが消えて clear() が呼ばれることがあるので、写しを回す
        for (b in bullets.toList()) {
            if (b !in bullets) continue
            if (!step(b)) bullets.remove(b)
        }
    }

    fun clear() = bullets.clear()

    /** 1tick 分進める。消えたら false */
    private fun step(b: Bullet): Boolean {
        val spec = b.spec
        val world = b.pos.world
        val n = ceil(spec.speed / SUBSTEP).toInt().coerceAtLeast(1)
        val stepLen = spec.speed / n
        var delta = b.dir.clone().multiply(stepLen)
        val core = Particle.DustOptions(spec.color, spec.size)

        for (i in 0 until n) {
            b.pos.add(delta)
            b.travelled += stepLen

            // 弾の筋
            world.spawnParticle(Particle.DUST, b.pos, 1, 0.0, 0.0, 0.0, 0.0, core)
            if (i == n - 1) world.spawnParticle(Particle.DUST, b.pos, 1, 0.0, 0.0, 0.0, 0.0, HEAD)

            // ブロックに当たった
            val block = b.pos.block
            if (!block.isPassable && b.bouncesLeft > 0 && ricochet(b, delta)) {
                delta = b.dir.clone().multiply(stepLen)
                continue
            }
            if (!block.isPassable) {
                world.spawnParticle(Particle.BLOCK, b.pos, 6, 0.08, 0.08, 0.08, 0.0, block.blockData)
                world.spawnParticle(Particle.SMOKE, b.pos, 2, 0.05, 0.05, 0.05, 0.01)
                world.playSound(b.pos, block.blockSoundGroup.hitSound, SoundCategory.HOSTILE, 0.6f, 1.4f)
                return false
            }

            // 相手に当たった
            val victim = hitEntity(b.pos, spec.hitRadius)
            if (victim != null) {
                if (spec.ignoreInvulnerability) victim.noDamageTicks = 0
                victim.damage(spec.damage, boss.entity)
                if (spec.knockback > 0) {
                    victim.velocity = victim.velocity.add(b.dir.clone().setY(0).multiply(spec.knockback)).setY(0.12)
                }
                b.onHit?.invoke(victim)
                world.spawnParticle(Particle.DAMAGE_INDICATOR, b.pos, 2, 0.1, 0.1, 0.1, 0.05)
                world.spawnParticle(Particle.CRIT, b.pos, 6, 0.15, 0.15, 0.15, 0.2)
                return false
            }

            if (b.travelled >= spec.maxRange) return false
        }

        whiz(b)
        return true
    }

    /**
     * 跳弾: 1つ前の位置からどの面を通ってブロックに入ったかを見て、その向きの成分を反転する。
     * 跳ね返せたら true（弾は1つ前の位置に戻る）
     */
    private fun ricochet(b: Bullet, delta: Vector): Boolean {
        val prev = b.pos.clone().subtract(delta)
        if (!prev.block.isPassable) return false
        var flipped = false
        if (prev.blockX != b.pos.blockX) { b.dir.setX(-b.dir.x); flipped = true }
        if (prev.blockY != b.pos.blockY) { b.dir.setY(-b.dir.y); flipped = true }
        if (prev.blockZ != b.pos.blockZ) { b.dir.setZ(-b.dir.z); flipped = true }
        if (!flipped) return false
        val world = b.pos.world
        world.spawnParticle(Particle.ELECTRIC_SPARK, b.pos, 8, 0.05, 0.05, 0.05, 0.15)
        world.spawnParticle(Particle.BLOCK, b.pos, 4, 0.05, 0.05, 0.05, 0.0, b.pos.block.blockData)
        world.playSound(b.pos, Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 0.35f, 2.0f)
        world.playSound(b.pos, Sound.ENTITY_ARROW_HIT, SoundCategory.HOSTILE, 0.8f, 1.8f)
        b.pos.x = prev.x
        b.pos.y = prev.y
        b.pos.z = prev.z
        b.bouncesLeft--
        // 跳ね返った弾は、かすめる音をもう一度聞かせる
        b.whizzed.clear()
        return true
    }

    private fun hitEntity(at: Location, radius: Double): LivingEntity? {
        val r = radius + 1.5
        for (e in at.world.getNearbyEntities(at, r, r + 1.0, r)) {
            if (!canHit(e)) continue
            if (e.boundingBox.clone().expand(radius).contains(at.toVector())) return e as LivingEntity
        }
        return null
    }

    private fun canHit(e: Entity): Boolean {
        if (!TargetFinder.isValidTarget(boss, e)) return false
        val battle = boss.battle
        return battle == null || battle.isParticipant(e)
    }

    /** 弾がすぐそばを通ったプレイヤーに、かすめる音を聞かせる（1発につき1人1回） */
    private fun whiz(b: Bullet) {
        for (p in b.pos.world.getNearbyEntities(b.pos, 2.5, 2.5, 2.5)) {
            if (p !is Player || !b.whizzed.add(p.uniqueId)) continue
            p.playSound(b.pos, Sound.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 0.5f, 2.0f)
        }
    }

    private companion object {
        const val SUBSTEP = 0.4
        val HEAD = Particle.DustOptions(Color.fromRGB(255, 250, 230), 0.5f)

        /** 向きを最大 degrees 度ランダムにぶらす */
        fun spread(dir: Vector, degrees: Double): Vector {
            if (degrees <= 0) return dir
            val yaw = Math.toRadians(Random.nextDouble(-degrees, degrees))
            val pitch = Math.toRadians(Random.nextDouble(-degrees, degrees) * 0.6)
            val side = Vector(-dir.z, 0.0, dir.x)
            val out = dir.clone().rotateAroundY(yaw)
            if (side.lengthSquared() > 1e-6) out.rotateAroundAxis(side.normalize(), pitch)
            return out.normalize()
        }
    }
}
