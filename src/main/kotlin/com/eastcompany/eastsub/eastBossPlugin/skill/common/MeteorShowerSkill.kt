package com.eastcompany.eastsub.eastBossPlugin.skill.common

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 宙に浮いて詠唱し、周囲のランダムな地点に隕石を降らせ続ける。詠唱の後は疲れ果てて隙だらけになる。
 *
 *   0 〜 castStartTicks             … 飛び上がる（animation）
 *   castStartTicks 〜 castEndTicks   … intervalTicks ごとに meteorsPerWave 個の隕石を降らせる
 *   〜 endTicks                      … 着地（animation の終わり）
 *   〜 + exhaustedStartTicks         … 疲れ始め（exhaustedStartAnimation）
 *   〜 + exhaustedTicks              … 疲れ果てる（exhaustedAnimation を繰り返す）。受けるダメージが増える
 *   〜 + exhaustedEndTicks           … 立ち直る（exhaustedEndAnimation）
 *
 * 隕石は落ちる場所に予測マーカー（赤い円）を warningTicks の間出してから着弾する。
 * マーカーの内側の円が外側に届いたときが着弾の瞬間。一部（aimRatio）は標的の足元を狙う。
 * 同じ相手の足元は aimCooldownTicks に1回までしか狙わない（狙われ続けて逃げ場がなくならないように）。
 */
class MeteorShowerSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        /** この距離以内に標的がいるときだけ発動 */
        val range: Double,
        val animation: String,
        val castStartTicks: Int,
        val castEndTicks: Int,
        /** animation の長さ（着地まで） */
        val endTicks: Int,
        val intervalTicks: Int,
        val meteorsPerWave: Int,
        /** 詠唱を始めた位置からこの半径の中に降らせる */
        val areaRadius: Double,
        /** 標的の足元を狙う割合（0〜1） */
        val aimRatio: Double,
        /** 同じ相手の足元を狙う間隔（tick）。この間は、その人を狙わない（0 なら毎回狙ってよい） */
        val aimCooldownTicks: Int,
        /** 予測マーカーが出てから着弾するまで */
        val warningTicks: Int,
        val fallHeight: Double,
        val impactRadius: Double,
        val damage: Double,
        val fireTicks: Int,
        val knockback: Double,
        val meteorBlock: Material,
        /** 詠唱中（浮いている間）に受けるダメージの倍率 */
        val castDamageMultiplier: Double,
        val exhaustedStartAnimation: String,
        val exhaustedStartTicks: Int,
        val exhaustedAnimation: String,
        val exhaustedTicks: Int,
        val exhaustedEndAnimation: String,
        val exhaustedEndTicks: Int,
        /** 疲れている間に受けるダメージの倍率 */
        val exhaustedDamageMultiplier: Double,
    ) {
        /** 技全体の長さ */
        val totalTicks: Int get() = endTicks + exhaustedStartTicks + exhaustedTicks + exhaustedEndTicks

        /** 疲れの繰り返しが始まる tick */
        val exhaustedLoopTick: Int get() = endTicks + exhaustedStartTicks

        /** 立ち直り始める tick */
        val recoverTick: Int get() = exhaustedLoopTick + exhaustedTicks

        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 16.0),
                animation = section.string(Names.SkillConfig.ANIMATION),
                castStartTicks = section.int(Names.SkillConfig.CAST_START_TICKS, 12),
                castEndTicks = section.int(Names.SkillConfig.CAST_END_TICKS, 212),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 224),
                intervalTicks = section.int(Names.SkillConfig.INTERVAL_TICKS, 6).coerceAtLeast(1),
                meteorsPerWave = section.int(Names.SkillConfig.METEORS_PER_WAVE, 2).coerceAtLeast(1),
                areaRadius = section.double(Names.SkillConfig.AREA_RADIUS, 14.0),
                aimRatio = section.double(Names.SkillConfig.AIM_RATIO, 0.35).coerceIn(0.0, 1.0),
                aimCooldownTicks = section.int(Names.SkillConfig.AIM_COOLDOWN_TICKS, 0).coerceAtLeast(0),
                warningTicks = section.int(Names.SkillConfig.WARNING_TICKS, 18).coerceAtLeast(1),
                fallHeight = section.double(Names.SkillConfig.FALL_HEIGHT, 18.0),
                impactRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 2.5),
                damage = section.double(Names.SkillConfig.IMPACT_DAMAGE, 10.0),
                fireTicks = section.int(Names.SkillConfig.FIRE_TICKS, 60),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.7),
                meteorBlock = Material.matchMaterial(section.string(Names.SkillConfig.METEOR_BLOCK, "MAGMA_BLOCK"))
                    ?.takeIf { it.isBlock } ?: Material.MAGMA_BLOCK,
                castDamageMultiplier = section.double(Names.SkillConfig.CAST_DAMAGE_MULTIPLIER, 0.5),
                exhaustedStartAnimation = section.string(Names.SkillConfig.EXHAUSTED_START_ANIMATION),
                exhaustedStartTicks = section.int(Names.SkillConfig.EXHAUSTED_START_TICKS, 16),
                exhaustedAnimation = section.string(Names.SkillConfig.EXHAUSTED_ANIMATION),
                exhaustedTicks = section.int(Names.SkillConfig.EXHAUSTED_TICKS, 100),
                exhaustedEndAnimation = section.string(Names.SkillConfig.EXHAUSTED_END_ANIMATION),
                exhaustedEndTicks = section.int(Names.SkillConfig.EXHAUSTED_END_TICKS, 14),
                exhaustedDamageMultiplier = section.double(Names.SkillConfig.EXHAUSTED_DAMAGE_MULTIPLIER, 1.5),
            )
        }
    }

    /** 落下中の隕石1つ */
    private class Meteor(val impact: Location, val from: Location, val display: BlockDisplay) {
        var age = 0
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private lateinit var center: Location
    private val meteors = mutableListOf<Meteor>()

    /** 足元を最後に狙った tick（相手ごと） */
    private val lastAimed = HashMap<java.util.UUID, Int>()

    override fun canStart(boss: Boss): Boolean =
        TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        lastAimed.clear()
        center = boss.entity.location
        boss.entity.setAI(false)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val s = settings

        // 隕石を降らせる
        if (tick in s.castStartTicks..s.castEndTicks && (tick - s.castStartTicks) % s.intervalTicks == 0) {
            repeat(s.meteorsPerWave) { spawnMeteor(boss) }
        }
        tickMeteors(boss)

        // 着地後: 疲れ始め → 疲れの繰り返し → 立ち直り
        when (tick) {
            s.endTicks -> boss.playOnce(s.exhaustedStartAnimation)
            s.exhaustedLoopTick -> boss.playLoop(s.exhaustedAnimation, lerpTicks = 2)
            s.recoverTick -> {
                boss.stopAnimation(s.exhaustedAnimation)
                boss.playOnce(s.exhaustedEndAnimation)
            }
        }

        tick++
        // 落下中の隕石が残っていれば、着弾し終わるまで待つ
        return tick >= s.totalTicks && meteors.isEmpty()
    }

    override fun stop(boss: Boss) {
        meteors.forEach { it.display.remove() }
        meteors.clear()
        boss.stopAnimation(settings.exhaustedAnimation)
        boss.resumeEntityAi()
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        val multiplier = when {
            tick < settings.endTicks -> settings.castDamageMultiplier
            tick < settings.recoverTick -> settings.exhaustedDamageMultiplier
            else -> 1.0
        }
        event.damage *= multiplier
    }

    // ===== 隕石 =====

    private fun spawnMeteor(boss: Boss) {
        val world = center.world
        val impact = pickImpact(boss, world) ?: return

        // 少し斜めから落ちてくる
        val angle = Random.nextDouble(Math.PI * 2)
        val from = impact.clone().add(cos(angle) * settings.fallHeight * 0.35, settings.fallHeight, sin(angle) * settings.fallHeight * 0.35)

        val display = world.spawn(from, BlockDisplay::class.java) { d ->
            d.isPersistent = false
            d.block = settings.meteorBlock.createBlockData()
            d.brightness = Display.Brightness(15, 15)
            d.teleportDuration = 1
            // ブロックの中心を位置に合わせ、少し大きくする
            d.transformation = Transformation(
                Vector3f(-0.65f, -0.65f, -0.65f), Quaternionf(), Vector3f(1.3f, 1.3f, 1.3f), Quaternionf(),
            )
        }
        meteors += Meteor(impact, from, display)
    }

    /**
     * 着弾地点を決める（一部は標的の足元。ただし同じ人は aimCooldownTicks に1回まで）。
     * 地面が見つからなければ null
     */
    private fun pickImpact(boss: Boss, world: World): Location? {
        val base = if (Random.nextDouble() < settings.aimRatio) {
            val targets = boss.entity.getNearbyEntities(settings.areaRadius, settings.areaRadius, settings.areaRadius)
                .filter { TargetFinder.isTargetable(boss, it) }
                .filter { (lastAimed[it.uniqueId] ?: Int.MIN_VALUE / 2) + settings.aimCooldownTicks <= tick }
            targets.randomOrNull()?.let { t ->
                lastAimed[t.uniqueId] = tick
                t.location.add(Random.nextDouble(-1.0, 1.0), 0.0, Random.nextDouble(-1.0, 1.0))
            }
        } else null

        val point = base ?: run {
            val r = settings.areaRadius * sqrt(Random.nextDouble())
            val a = Random.nextDouble(Math.PI * 2)
            center.clone().add(cos(a) * r, 0.0, sin(a) * r)
        }
        val groundY = groundAt(world, point.blockX, point.blockZ, point.blockY) ?: return null
        return Location(world, point.x, groundY.toDouble(), point.z)
    }

    /** baseY の近くで、上が空いている地面の高さ */
    private fun groundAt(world: World, x: Int, z: Int, baseY: Int): Int? {
        for (y in (baseY + 4) downTo (baseY - 8)) {
            val block = world.getBlockAt(x, y, z)
            if (block.isSolid && world.getBlockAt(x, y + 1, z).isPassable) return y + 1
        }
        return null
    }

    private fun tickMeteors(boss: Boss) {
        // 着弾でプレイヤーが倒れるとボスが消えて stop() が呼ばれ、一覧が空になることがある。
        // そのため写しを回し、一覧から消えた隕石は処理しない
        for (m in meteors.toList()) {
            if (m !in meteors) continue
            m.age++
            val progress = m.age.toDouble() / settings.warningTicks

            if (progress >= 1.0) {
                meteors.remove(m)
                m.display.remove()
                explode(boss, m)
                continue
            }

            // 落下（回転させながら）
            val pos = m.from.clone().add(m.impact.toVector().subtract(m.from.toVector()).multiply(progress))
            pos.yaw = (m.age * 25f) % 360f
            pos.pitch = (m.age * 15f) % 180f - 90f
            m.display.teleport(pos)
            val world = pos.world
            world.spawnParticle(Particle.FLAME, pos, 4, 0.25, 0.25, 0.25, 0.02)
            world.spawnParticle(Particle.LARGE_SMOKE, pos, 1, 0.2, 0.2, 0.2, 0.01)

            // 予測マーカー: 外側の赤い円（着弾範囲）と、着弾に向けて広がる内側の円
            if (m.age % 2 == 0) {
                drawRing(m.impact, settings.impactRadius, MARKER_OUTER, 28)
                drawRing(m.impact, settings.impactRadius * progress, MARKER_INNER, 16)
            }
        }
    }

    private fun drawRing(center: Location, radius: Double, dust: Particle.DustOptions, points: Int) {
        if (radius <= 0.05) return
        val world = center.world
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points
            world.spawnParticle(
                Particle.DUST, center.x + cos(a) * radius, center.y + 0.1, center.z + sin(a) * radius,
                1, 0.0, 0.0, 0.0, 0.0, dust,
            )
        }
    }

    private fun explode(boss: Boss, m: Meteor) {
        val at = m.impact
        val world = at.world
        world.spawnParticle(Particle.EXPLOSION, at, 3, 0.6, 0.3, 0.6, 0.0)
        world.spawnParticle(Particle.FLAME, at, 40, settings.impactRadius * 0.4, 0.3, settings.impactRadius * 0.4, 0.08)
        world.spawnParticle(Particle.LAVA, at, 10, 0.8, 0.2, 0.8, 0.0)
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.2f, 1.2f)

        val r = settings.impactRadius
        for (e in world.getNearbyEntities(at, r, r + 1.0, r)) {
            if (!TargetFinder.isValidTarget(boss, e)) continue
            // 旅商人からのボス戦では参加者にだけ当てる
            val battle = boss.battle
            if (battle != null && !battle.isParticipant(e)) continue
            val dx = e.location.x - at.x
            val dz = e.location.z - at.z
            if (dx * dx + dz * dz > r * r) continue

            val victim = e as LivingEntity
            victim.damage(settings.damage, boss.entity)
            if (settings.fireTicks > 0) victim.fireTicks = maxOf(victim.fireTicks, settings.fireTicks)
            val away = Vector(dx, 0.0, dz).let { if (it.lengthSquared() < 1e-4) Vector(0.0, 0.0, 0.0) else it.normalize() }
            victim.velocity = away.multiply(settings.knockback).setY(0.45)
        }
    }

    private companion object {
        val MARKER_OUTER = Particle.DustOptions(Color.fromRGB(255, 40, 30), 1.3f)
        val MARKER_INNER = Particle.DustOptions(Color.fromRGB(255, 170, 40), 1.0f)
    }
}
