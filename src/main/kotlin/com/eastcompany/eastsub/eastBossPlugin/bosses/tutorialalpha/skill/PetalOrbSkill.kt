package com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha.skill

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
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * 花びらの玉（チュートリアルアルファ）: 弾き返しを教える技。
 *
 *   構える（releaseTick まで。手元に花びらが集まって玉になる）
 *   → 標的へゆっくりまっすぐ飛ばす（speed。ブロックに当たると消える）
 *   → 当たれば damage。玉が reflect-radius まで来たときにジャストガードすると、アルファへ跳ね返る（skill.util.Reflect）
 *   → 跳ね返った玉はアルファを追いかけて当たり、reflect-damage（弾き返した人から）と、少しのよろめき（stun-ticks の間、技が止まる）
 *
 * 玉が消えるか、アルファに当たってよろめき終わるまでが1回の技
 */
class PetalOrbSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val animation: String,
        /** 玉を放つ tick */
        val releaseTick: Int,
        val speed: Double,
        val damage: Double,
        val hitRadius: Double,
        val maxRange: Double,
        val reflectRadius: Double,
        val reflectSpeed: Double,
        val reflectDamage: Double,
        /** 跳ね返った玉が当たった後、よろめいている時間（tick） */
        val stunTicks: Int,
        /** 玉が消えた（外れた）後、技を終えるまでの時間（tick） */
        val endTicks: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 2),
                range = section.double(Names.SkillConfig.RANGE, 20.0),
                animation = section.string(Names.SkillConfig.ANIMATION, "attack5"),
                releaseTick = section.int(Names.SkillConfig.RELEASE_TICKS, 24),
                speed = section.double(Names.SkillConfig.SPEED, 0.35).coerceAtLeast(0.05),
                damage = section.double(Names.SkillConfig.DAMAGE, 5.0),
                hitRadius = section.double(Names.SkillConfig.HIT_RADIUS, 0.9),
                maxRange = section.double(Names.SkillConfig.MAX_RANGE, 28.0),
                reflectRadius = section.double(Names.SkillConfig.REFLECT_RADIUS, 2.5),
                reflectSpeed = section.double(Names.SkillConfig.REFLECT_SPEED, 0.9).coerceAtLeast(0.1),
                reflectDamage = section.double(Names.SkillConfig.REFLECT_DAMAGE, 30.0),
                stunTicks = section.int(Names.SkillConfig.STUN_TICKS, 40),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 10),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private enum class Phase { WINDUP, FLY, REFLECTED, STUNNED, AFTER }

    private var phase = Phase.WINDUP
    private var tick = 0
    private var phaseTick = 0
    private var target: LivingEntity? = null
    private var pos: Location? = null
    private var dir = Vector()
    private var travelled = 0.0
    private var reflectedBy: Player? = null

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        phase = Phase.WINDUP
        phaseTick = 0
        travelled = 0.0
        pos = null
        reflectedBy = null
        target = TargetFinder.findNearest(boss, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        tick++
        phaseTick++
        return when (phase) {
            Phase.WINDUP -> { windup(boss); false }
            Phase.FLY -> { fly(boss); false }
            Phase.REFLECTED -> { flyBack(boss); false }
            Phase.STUNNED -> {
                if (phaseTick % 4 == 0) {
                    val head = boss.entity.location.add(0.0, boss.entity.height + 0.3, 0.0)
                    head.world.spawnParticle(Particle.CRIT, head, 3, 0.25, 0.05, 0.25, 0.05)
                }
                if (phaseTick >= settings.stunTicks) next(Phase.AFTER)
                false
            }
            Phase.AFTER -> phaseTick >= settings.endTicks
        }
    }

    private fun next(p: Phase) {
        phase = p
        phaseTick = 0
    }

    private fun hand(boss: Boss): Location =
        boss.entity.location.add(0.0, boss.entity.height * 0.6, 0.0).add(Facing.forward(boss.entity).multiply(0.8))

    /** 構え: 手元に花びらが渦を巻いて集まる */
    private fun windup(boss: Boss) {
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) }
        if (t != null) Facing.faceTarget(boss, t)
        val at = hand(boss)
        val f = (tick.toDouble() / settings.releaseTick).coerceIn(0.0, 1.0)
        val r = 1.2 - 0.9 * f
        for (k in 0 until 3) {
            val a = tick * 0.5 + k * Math.PI * 2 / 3
            at.world.spawnParticle(Particle.CHERRY_LEAVES, at.clone().add(Math.cos(a) * r, Math.sin(a) * r * 0.5, Math.sin(a) * r), 1, 0.0, 0.0, 0.0, 0.0)
        }
        if (tick % 2 == 0) orb(at, 0.25 + 0.25 * f)
        if (tick < settings.releaseTick) return

        // 放つ
        val aimAt = t?.let { it.location.add(0.0, it.height * 0.55, 0.0) } ?: at.clone().add(Facing.forward(boss.entity).multiply(10.0))
        val v = aimAt.toVector().subtract(at.toVector())
        dir = if (v.lengthSquared() > 1e-4) v.normalize() else Facing.forward(boss.entity)
        pos = at
        at.world.playSound(at, Sound.ENTITY_ALLAY_ITEM_THROWN, SoundCategory.HOSTILE, 1.5f, 1.2f)
        at.world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 1.2f, 1.6f)
        next(Phase.FLY)
    }

    /** 標的へゆっくり飛ぶ。弾き返されたら向きを変える */
    private fun fly(boss: Boss) {
        val p = pos ?: return next(Phase.AFTER)
        val world = p.world

        val by = Reflect.tryReflect(boss, p, settings.reflectRadius)
        if (by != null) {
            reflectedBy = by
            world.playSound(p, Sound.ENTITY_ALLAY_ITEM_GIVEN, SoundCategory.PLAYERS, 1.5f, 1.4f)
            world.spawnParticle(Particle.END_ROD, p, 14, 0.2, 0.2, 0.2, 0.1)
            next(Phase.REFLECTED)
            return
        }

        val hit = world.rayTraceBlocks(p, dir, settings.speed)
        if (hit != null || travelled > settings.maxRange) {
            fizzle(p)
            next(Phase.AFTER)
            return
        }
        p.add(dir.clone().multiply(settings.speed))
        travelled += settings.speed
        orb(p, 0.5)

        for (e in world.getNearbyEntities(p, settings.hitRadius + 1.0, settings.hitRadius + 1.0, settings.hitRadius + 1.0)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            val victim = e as LivingEntity
            if (!victim.boundingBox.clone().expand(settings.hitRadius).contains(p.toVector())) continue
            victim.noDamageTicks = 0
            victim.damage(settings.damage, boss.entity)
            fizzle(p)
            next(Phase.AFTER)
            return
        }
    }

    /** 跳ね返った玉: アルファを追いかけて当たる */
    private fun flyBack(boss: Boss) {
        val p = pos ?: return next(Phase.AFTER)
        val by = reflectedBy
        val e = boss.entity
        val center = e.location.add(0.0, e.height * 0.55, 0.0)
        val v = center.toVector().subtract(p.toVector())
        if (by == null || phaseTick > 60) {
            fizzle(p)
            next(Phase.AFTER)
            return
        }
        if (v.length() <= e.width * 0.5 + 0.5 + settings.reflectSpeed) {
            // 当たった: 弾き返した人からのダメージと、よろめき
            fizzle(center)
            center.world.spawnParticle(Particle.CHERRY_LEAVES, center, 30, 0.5, 0.6, 0.5, 0.0)
            center.world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.HOSTILE, 1.5f, 1.2f)
            e.noDamageTicks = 0
            e.damage(settings.reflectDamage, by)
            boss.playOnce(STAGGER_ANIMATION)
            next(Phase.STUNNED)
            return
        }
        p.add(v.normalize().multiply(settings.reflectSpeed))
        orb(p, 0.5, reflected = true)
    }

    /** 玉の見た目（桃色の花びらの塊。跳ね返った玉は白く光る） */
    private fun orb(at: Location, size: Double, reflected: Boolean = false) {
        val world = at.world
        world.spawnParticle(Particle.DUST, at, 6, size * 0.4, size * 0.4, size * 0.4, 0.0, if (reflected) WHITE else PINK)
        world.spawnParticle(Particle.CHERRY_LEAVES, at, 1, size * 0.3, size * 0.3, size * 0.3, 0.0)
        if (reflected) world.spawnParticle(Particle.END_ROD, at, 1, 0.05, 0.05, 0.05, 0.01)
    }

    private fun fizzle(at: Location) {
        at.world.spawnParticle(Particle.CHERRY_LEAVES, at, 16, 0.3, 0.3, 0.3, 0.0)
        at.world.spawnParticle(Particle.DUST, at, 10, 0.3, 0.3, 0.3, 0.0, PINK)
        at.world.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 1.0f, 1.4f)
    }

    override fun stop(boss: Boss) {
        pos = null
        reflectedBy = null
        boss.resumeEntityAi()
    }

    private companion object {
        /** 跳ね返った玉が当たったときのよろめき（体幹崩しのときに足したアニメを使う） */
        const val STAGGER_ANIMATION = "stagger"
        val PINK = Particle.DustOptions(Color.fromRGB(255, 150, 200), 1.6f)
        val WHITE = Particle.DustOptions(Color.fromRGB(255, 245, 250), 1.6f)
    }
}
