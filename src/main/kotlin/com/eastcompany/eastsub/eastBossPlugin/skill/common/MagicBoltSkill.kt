package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Reflect
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
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

/**
 * 魔法の弾を撃つ。shootTicks の各 tick に、標的へ向けて count 発を扇状（spreadDegrees）に撃つ。
 *
 * 弾は少しだけ標的を追いかけ（homing）、ブロックか相手に当たると爆発して周り（explosionRadius）にもダメージ。
 * maxLifeTicks で消える。
 *
 * reflectable なら、弾が reflectRadius まで来たときにジャストガードすると弾き返せる（skill.util.Reflect）。
 * 跳ね返った弾はボスを追いかけ（ブロックはすり抜ける）、当たると reflectDamage（弾き返した人から）
 */
class MagicBoltSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        /** この距離以内に標的がいるときだけ発動 */
        val range: Double,
        val animation: String,
        /** 撃つ tick（複数書けば連射） */
        val shootTicks: List<Int>,
        /** 1回に撃つ数 */
        val count: Int,
        /** 扇の広がり（両端の角度の差・度） */
        val spreadDegrees: Double,
        /** 1tick に進む距離（ブロック） */
        val speed: Double,
        /** 標的へ曲がる強さ（0 でまっすぐ、1 で毎tick真っ直ぐ標的へ） */
        val homing: Double,
        val maxLifeTicks: Int,
        /** 弾の当たり判定の半径 */
        val hitRadius: Double,
        /** 当たったときの爆発の半径（この中の相手にもダメージ） */
        val explosionRadius: Double,
        val damage: Double,
        /** 撃ち出す位置: 足元からの高さ（ボスの当たり判定の高さに対する割合）と、前方への距離 */
        val launchHeight: Double,
        val launchForward: Double,
        val endTicks: Int,
        /** ジャストガードで弾き返せるか */
        val reflectable: Boolean,
        val reflectRadius: Double,
        val reflectSpeed: Double,
        /** 跳ね返った弾がボスに当たったときのダメージ */
        val reflectDamage: Double,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 20.0),
                animation = section.string(Names.SkillConfig.ANIMATION),
                shootTicks = section.stringList(Names.SkillConfig.SHOOT_TICKS, listOf("8"))
                    .mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(8) },
                count = section.int(Names.SkillConfig.COUNT, 3).coerceAtLeast(1),
                spreadDegrees = section.double(Names.SkillConfig.SPREAD_DEGREES, 30.0),
                speed = section.double(Names.SkillConfig.SPEED, 0.9).coerceAtLeast(0.05),
                homing = section.double(Names.SkillConfig.HOMING, 0.06).coerceIn(0.0, 1.0),
                maxLifeTicks = section.int(Names.SkillConfig.MAX_LIFE_TICKS, 60),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 1.0),
                explosionRadius = section.double(Names.SkillConfig.EXPLOSION_RADIUS, 2.0),
                damage = section.double(Names.SkillConfig.DAMAGE, 7.0),
                launchHeight = section.double(Names.SkillConfig.LAUNCH_HEIGHT, 0.4),
                launchForward = section.double(Names.SkillConfig.LAUNCH_FORWARD, 3.5),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 18),
                reflectable = section.boolean(Names.SkillConfig.REFLECTABLE, false),
                reflectRadius = section.double(Names.SkillConfig.REFLECT_RADIUS, 2.2),
                reflectSpeed = section.double(Names.SkillConfig.REFLECT_SPEED, 1.2).coerceAtLeast(0.1),
                reflectDamage = section.double(Names.SkillConfig.REFLECT_DAMAGE, 25.0),
            )
        }
    }

    /** 飛んでいる弾1発 */
    private class Bolt(var pos: Location, var velocity: Vector, val target: Entity?) {
        var age = 0
        /** 弾き返した人（跳ね返っている間） */
        var reflectedBy: Player? = null
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private val bolts = mutableListOf<Bolt>()

    override fun canStart(boss: Boss): Boolean =
        TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        bolts.clear()
        target = TargetFinder.findNearest(boss, settings.range)
        boss.entity.setAI(false)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        // 撃つまでは標的の方を向き続ける
        if (tick <= (settings.shootTicks.maxOrNull() ?: 0)) Facing.faceTarget(boss, target)
        if (tick in settings.shootTicks) shoot(boss)
        tickBolts(boss)

        tick++
        // 飛んでいる弾が残っていれば、消えるまで待つ
        return tick >= settings.endTicks && bolts.isEmpty()
    }

    override fun stop(boss: Boss) {
        bolts.clear()
        boss.resumeEntityAi()
        target = null
    }

    private fun shoot(boss: Boss) {
        val entity = boss.entity
        val forward = Facing.forward(entity)
        // 標的が近いときは、弾が標的の手前（MIN_GAP ブロック以上手前）に出るよう撃ち出す位置を引き寄せる
        // （杖の先が標的より奥になると、弾が背中側に出て弾き返せない・避けようがない）
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) }
        val near = t?.let { Math.hypot(it.location.x - entity.location.x, it.location.z - entity.location.z) - MIN_GAP }
        val launch = near?.let { minOf(settings.launchForward, it) }?.coerceAtLeast(entity.width * 0.5)
            ?: settings.launchForward
        val origin = entity.location.add(forward.clone().multiply(launch))
            .add(0.0, entity.height * settings.launchHeight, 0.0)

        // 標的の胸元へ向ける
        val aim = t
            ?.let { it.location.add(0.0, it.height * 0.5, 0.0).toVector().subtract(origin.toVector()) }
            ?.takeIf { it.lengthSquared() > 1e-4 }?.normalize()
            ?: forward

        val n = settings.count
        for (i in 0 until n) {
            val angle = if (n == 1) 0.0 else settings.spreadDegrees * (i.toDouble() / (n - 1) - 0.5)
            val dir = aim.clone().rotateAroundY(Math.toRadians(angle))
            bolts += Bolt(origin.clone(), dir.multiply(settings.speed), target)
        }
        origin.world.spawnParticle(Particle.WITCH, origin, 20, 0.4, 0.4, 0.4, 0.1)
    }

    private fun tickBolts(boss: Boss) {
        // 当たったプレイヤーが倒れるとボスが消えて stop() が呼ばれ、一覧が空になることがある。
        // そのため写しを回し、一覧から消えた弾は処理しない
        for (bolt in bolts.toList()) {
            if (bolt !in bolts) continue
            bolt.age++
            if (bolt.age > settings.maxLifeTicks) {
                bolts.remove(bolt)
                continue
            }
            if (bolt.reflectedBy != null) {
                if (flyBack(boss, bolt)) bolts.remove(bolt)
                continue
            }
            if (settings.reflectable) {
                val by = Reflect.tryReflect(boss, bolt.pos, settings.reflectRadius)
                if (by != null) {
                    bolt.reflectedBy = by
                    bolt.age = 0
                    bolt.pos.world.playSound(bolt.pos, Sound.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 1.4f, 1.6f)
                    bolt.pos.world.spawnParticle(Particle.END_ROD, bolt.pos, 12, 0.2, 0.2, 0.2, 0.1)
                    continue
                }
            }
            steer(boss, bolt)

            // 速い弾がすり抜けないよう、0.5ブロックずつ進めて判定する
            val steps = ceil(settings.speed / 0.5).toInt().coerceAtLeast(1)
            val step = bolt.velocity.clone().multiply(1.0 / steps)
            var exploded = false
            for (i in 0 until steps) {
                bolt.pos.add(step)
                if (!bolt.pos.block.isPassable || hitsTarget(boss, bolt.pos)) {
                    bolts.remove(bolt)
                    explode(boss, bolt.pos)
                    exploded = true
                    break
                }
            }
            if (!exploded) drawBolt(bolt.pos)
        }
    }

    /** 跳ね返った弾: ボスを追いかけて飛ぶ（ブロックはすり抜ける）。当たったら true */
    private fun flyBack(boss: Boss, bolt: Bolt): Boolean {
        val by = bolt.reflectedBy ?: return true
        val e = boss.entity
        if (boss.isDying || !e.isValid) return true
        val center = e.location.add(0.0, e.height * 0.5, 0.0)
        val v = center.toVector().subtract(bolt.pos.toVector())
        if (v.length() <= e.width * 0.5 + 1.0 + settings.reflectSpeed) {
            val world = center.world
            world.spawnParticle(Particle.DUST, center, 30, 0.8, 0.8, 0.8, 0.0, REFLECT_DUST)
            world.spawnParticle(Particle.EXPLOSION, center, 1, 0.0, 0.0, 0.0, 0.0)
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.0f, 1.4f)
            e.noDamageTicks = 0
            e.damage(settings.reflectDamage, by)
            return true
        }
        bolt.pos.add(v.normalize().multiply(settings.reflectSpeed))
        val world = bolt.pos.world
        world.spawnParticle(Particle.DUST, bolt.pos, 6, 0.18, 0.18, 0.18, 0.0, REFLECT_DUST)
        world.spawnParticle(Particle.END_ROD, bolt.pos, 1, 0.05, 0.05, 0.05, 0.01)
        return false
    }

    /** 標的へ少しずつ曲がる */
    private fun steer(boss: Boss, bolt: Bolt) {
        if (settings.homing <= 0.0) return
        val t = bolt.target ?: return
        if (!TargetFinder.isValidTarget(boss, t)) return
        val want = t.location.add(0.0, t.height * 0.5, 0.0).toVector().subtract(bolt.pos.toVector())
        if (want.lengthSquared() < 1e-4) return
        val dir = bolt.velocity.clone().normalize()
        dir.multiply(1 - settings.homing).add(want.normalize().multiply(settings.homing))
        bolt.velocity = dir.normalize().multiply(settings.speed)
    }

    private fun hitsTarget(boss: Boss, at: Location): Boolean {
        val r = settings.hitRadius
        return at.world.getNearbyEntities(at, r, r, r).any { canHit(boss, it) }
    }

    private fun canHit(boss: Boss, e: Entity): Boolean {
        if (!TargetFinder.isValidTarget(boss, e)) return false
        val battle = boss.battle
        return battle == null || battle.isParticipant(e)
    }

    private fun explode(boss: Boss, at: Location) {
        val world = at.world
        world.spawnParticle(Particle.DUST, at, 30, 0.6, 0.6, 0.6, 0.0, BOLT_DUST)
        world.spawnParticle(Particle.WITCH, at, 25, 0.5, 0.5, 0.5, 0.2)
        world.spawnParticle(Particle.EXPLOSION, at, 1, 0.0, 0.0, 0.0, 0.0)
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.8f, 1.6f)

        val r = settings.explosionRadius.coerceAtLeast(settings.hitRadius)
        val hit = HashSet<UUID>()
        for (e in world.getNearbyEntities(at, r, r, r)) {
            if (!canHit(boss, e) || !hit.add(e.uniqueId)) continue
            if (e.location.add(0.0, e.height * 0.5, 0.0).distanceSquared(at) > (r + 0.5) * (r + 0.5)) continue
            val victim = e as LivingEntity
            victim.damage(settings.damage, boss.entity)
            val away = victim.location.toVector().subtract(at.toVector()).setY(0.0)
            if (away.lengthSquared() > 1e-4) victim.velocity = away.normalize().multiply(0.5).setY(0.3)
        }
    }

    private fun drawBolt(at: Location) {
        val world = at.world
        world.spawnParticle(Particle.DUST, at, 6, 0.18, 0.18, 0.18, 0.0, BOLT_DUST)
        world.spawnParticle(Particle.DUST, at, 2, 0.05, 0.05, 0.05, 0.0, BOLT_CORE)
        world.spawnParticle(Particle.WITCH, at, 1, 0.1, 0.1, 0.1, 0.0)
    }

    private companion object {
        /** 近い標的に撃つとき、弾を標的からこれだけ手前に出す（ブロック） */
        const val MIN_GAP = 1.6

        val BOLT_DUST = Particle.DustOptions(Color.fromRGB(150, 50, 230), 1.8f)
        val BOLT_CORE = Particle.DustOptions(Color.fromRGB(250, 220, 255), 1.0f)
        /** 跳ね返った弾（白っぽい水色） */
        val REFLECT_DUST = Particle.DustOptions(Color.fromRGB(190, 240, 255), 1.8f)
    }
}
