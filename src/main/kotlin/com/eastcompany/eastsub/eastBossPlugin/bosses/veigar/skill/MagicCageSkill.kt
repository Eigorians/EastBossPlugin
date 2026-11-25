package com.eastcompany.eastsub.eastBossPlugin.bosses.veigar.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 魔法陣の檻（ベイガー・本気モードの大技）。
 *
 *   0 〜 activateTick       … 標的の足元に紫の輪が浮かぶ（予告）。この間に輪の外へ出れば閉じ込められない
 *   activateTick 〜 +duration … 輪が檻になる。輪の縁を越える（出る・入る）と、ダメージとスタン
 *                              檻の中の相手の足元には、boltIntervalTicks ごとに魔弾が降る（予告の輪から出てかわす）
 *
 * 閉じ込められたら「中で魔弾をかわし続ける」か「ダメージとスタンを覚悟して縁を越える」かを選ぶ。
 * 檻の外にいる相手には何もしないので、仲間が外からベイガーを叩ける。
 */
class MagicCageSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val castAnimation: String,
        val holdAnimation: String,
        /** 予告が終わって檻になる tick */
        val activateTick: Int,
        /** 檻が続く時間 */
        val durationTicks: Int,
        val radius: Double,
        /** 縁の当たり判定の幅（縁からこれだけ内側・外側まで） */
        val edgeWidth: Double,
        val edgeDamage: Double,
        val stunTicks: Int,
        val boltIntervalTicks: Int,
        val boltWarningTicks: Int,
        val boltRadius: Double,
        val boltDamage: Double,
        val endTicks: Int,
    ) {
        val cageEnd: Int get() = activateTick + durationTicks

        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 18.0),
                castAnimation = section.string(Names.SkillConfig.ANIMATION, "cage_cast"),
                holdAnimation = section.string(Names.SkillConfig.WAIT_ANIMATION, "cage_hold"),
                activateTick = section.int(Names.SkillConfig.ACTIVATE_TICK, 24),
                durationTicks = section.int(Names.SkillConfig.DURATION_TICKS, 100),
                radius = section.double(Names.SkillConfig.AREA_RADIUS, 5.5),
                edgeWidth = section.double(Names.SkillConfig.HIT_WIDTH, 0.7),
                edgeDamage = section.double(Names.SkillConfig.DAMAGE, 6.0),
                stunTicks = section.int(Names.SkillConfig.STUN_TICKS, 30),
                boltIntervalTicks = section.int(Names.SkillConfig.INTERVAL_TICKS, 20).coerceAtLeast(4),
                boltWarningTicks = section.int(Names.SkillConfig.WARNING_TICKS, 16).coerceAtLeast(1),
                boltRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 2.0),
                boltDamage = section.double(Names.SkillConfig.IMPACT_DAMAGE, 7.0),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 140),
            )
        }
    }

    /** 降ってくる魔弾（予告中） */
    private class Bolt(val at: Location, var age: Int = 0)

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var center: Location? = null
    /** 相手ごとの「前の tick に檻の中にいたか」 */
    private val inside = HashMap<UUID, Boolean>()
    /** 縁で当たった相手の、次に当たるまでの待ち */
    private val edgeCooldown = HashMap<UUID, Int>()
    private val bolts = mutableListOf<Bolt>()

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        inside.clear()
        edgeCooldown.clear()
        bolts.clear()
        val target = TargetFinder.findNearest(boss, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        val c = (target ?: boss.entity).location.clone()
        c.y = Ground.groundY(c.world, c.x, c.y + 1, c.z)
        center = c
        boss.playOnce(settings.castAnimation)
    }

    override fun tick(boss: Boss): Boolean {
        val s = settings
        val c = center ?: return true
        Facing.face(boss.entity, Vector(c.x - boss.entity.location.x, 0.0, c.z - boss.entity.location.z))

        when {
            tick < s.activateTick -> {
                // 予告: 薄い輪が少しずつ濃くなる。中心から光の粒が立ちのぼる
                if (tick % 2 == 0) ring(c, s.radius, if (tick > s.activateTick - 8) EDGE else WARN, 0.0)
                if (tick % 4 == 0) c.world.spawnParticle(Particle.WITCH, c.clone().add(0.0, 0.2, 0.0), 6, s.radius * 0.5, 0.05, s.radius * 0.5, 0.0)
            }
            tick < s.cageEnd -> {
                if (tick == s.activateTick) activate(boss, c)
                drawCage(c)
                checkEdge(boss, c)
                val since = tick - s.activateTick
                if (since % s.boltIntervalTicks == s.boltIntervalTicks / 2) dropBolts(boss, c)
            }
            tick == s.cageEnd -> {
                boss.stopAnimation(s.holdAnimation)
                c.world.spawnParticle(Particle.REVERSE_PORTAL, c.clone().add(0.0, 0.5, 0.0), 80, s.radius * 0.6, 0.3, s.radius * 0.6, 0.05)
                c.world.playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.HOSTILE, 2.0f, 1.2f)
            }
        }
        tickBolts(boss)
        edgeCooldown.replaceAll { _, v -> v - 1 }
        edgeCooldown.values.removeIf { it <= 0 }

        tick++
        return tick >= s.endTicks && bolts.isEmpty()
    }

    // ===== 檻 =====

    private fun activate(boss: Boss, c: Location) {
        boss.playLoop(settings.holdAnimation, lerpTicks = 3)
        val world = c.world
        world.spawnParticle(Particle.FLASH, c.clone().add(0.0, 1.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(190, 90, 255))
        world.spawnParticle(Particle.REVERSE_PORTAL, c.clone().add(0.0, 0.3, 0.0), 120, settings.radius * 0.7, 0.2, settings.radius * 0.7, 0.1)
        // 檻ができた瞬間に中にいる相手を覚える（ここから縁を越えると当たる）
        for (e in candidates(boss, c)) inside[e.uniqueId] = flatDistance(e.location, c) < settings.radius
    }

    private fun drawCage(c: Location) {
        val s = settings
        if (tick % 2 == 0) {
            ring(c, s.radius, EDGE, 0.05)
            ring(c, s.radius, EDGE_DIM, 1.2)
        }
        // 縁に立つ光の柱（ゆっくり回る）
        if (tick % 3 == 0) {
            val spin = tick * 0.03
            for (i in 0 until PILLARS) {
                val a = spin + Math.PI * 2 * i / PILLARS
                val base = c.clone().add(cos(a) * s.radius, 0.0, sin(a) * s.radius)
                for (h in 0..5) c.world.spawnParticle(Particle.DUST, base.clone().add(0.0, h * 0.5, 0.0), 1, 0.0, 0.0, 0.0, 0.0, PILLAR)
            }
        }
    }

    /** 縁を越えた（出た・入った）相手、縁の上に立っている相手にダメージとスタン */
    private fun checkEdge(boss: Boss, c: Location) {
        val s = settings
        for (e in candidates(boss, c)) {
            val dist = flatDistance(e.location, c)
            val nowInside = dist < s.radius
            val wasInside = inside.put(e.uniqueId, nowInside) ?: continue
            val onEdge = kotlin.math.abs(dist - s.radius) <= s.edgeWidth
            if (wasInside == nowInside && !onEdge) continue
            if (edgeCooldown.containsKey(e.uniqueId)) continue
            edgeCooldown[e.uniqueId] = EDGE_COOLDOWN
            e.noDamageTicks = 0
            e.damage(s.edgeDamage, boss.entity)
            if (e is Player && s.stunTicks > 0 && !boss.status.justGuard.guardedNow(e)) boss.status.stun.stun(e, s.stunTicks)
            val at = e.location.add(0.0, 1.0, 0.0)
            e.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 20, 0.3, 0.5, 0.3, 0.2)
            e.world.spawnParticle(Particle.DUST, at, 20, 0.3, 0.5, 0.3, 0.0, EDGE)
            e.world.playSound(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.8f)
            e.world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f)
        }
    }

    // ===== 魔弾 =====

    /** 檻の中の相手の足元に、魔弾の予告を出す */
    private fun dropBolts(boss: Boss, c: Location) {
        for (e in candidates(boss, c)) {
            if (flatDistance(e.location, c) >= settings.radius) continue
            val at = e.location.clone()
            at.y = Ground.groundY(at.world, at.x, at.y + 1, at.z)
            bolts += Bolt(at)
            at.world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 1.5f, 1.4f)
        }
    }

    private fun tickBolts(boss: Boss) {
        val s = settings
        for (b in bolts.toList()) {
            b.age++
            val world = b.at.world
            if (b.age < s.boltWarningTicks) {
                if (b.age % 2 == 0) ring(b.at, s.boltRadius, if (b.age > s.boltWarningTicks - 6) EDGE else WARN, 0.05)
                // 空から落ちてくる光
                val f = b.age.toDouble() / s.boltWarningTicks
                world.spawnParticle(Particle.END_ROD, b.at.clone().add(0.0, 9.0 * (1 - f) + 0.5, 0.0), 1, 0.05, 0.05, 0.05, 0.0)
                continue
            }
            bolts.remove(b)
            world.spawnParticle(Particle.FLASH, b.at.clone().add(0.0, 0.3, 0.0), 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(200, 110, 255))
            world.spawnParticle(Particle.DUST, b.at.clone().add(0.0, 0.5, 0.0), 40, s.boltRadius * 0.5, 0.4, s.boltRadius * 0.5, 0.0, EDGE)
            world.spawnParticle(Particle.REVERSE_PORTAL, b.at.clone().add(0.0, 0.3, 0.0), 30, 0.3, 0.2, 0.3, 0.2)
            world.playSound(b.at, Sound.ENTITY_EVOKER_CAST_SPELL, SoundCategory.HOSTILE, 1.5f, 0.6f)
            world.playSound(b.at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.6f, 1.6f)
            for (e in world.getNearbyEntities(b.at, s.boltRadius, 2.0, s.boltRadius)) {
                if (!TargetFinder.isValidTarget(boss, e)) continue
                if (flatDistance(e.location, b.at) > s.boltRadius) continue
                val victim = e as LivingEntity
                victim.noDamageTicks = 0
                victim.damage(s.boltDamage, boss.entity)
                victim.velocity = victim.velocity.setY(0.45)
            }
        }
    }

    // ===== 共通 =====

    private fun candidates(boss: Boss, c: Location): List<LivingEntity> {
        val r = settings.radius + 6
        return c.world.getNearbyEntities(c, r, 6.0, r).filter { TargetFinder.isValidTarget(boss, it) }.map { it as LivingEntity }
    }

    private fun flatDistance(a: Location, b: Location): Double = hypot(a.x - b.x, a.z - b.z)

    private fun ring(c: Location, radius: Double, dust: Particle.DustOptions, height: Double) {
        val n = (radius * 6).toInt().coerceAtLeast(12)
        for (i in 0 until n) {
            val a = Math.PI * 2 * i / n
            c.world.spawnParticle(Particle.DUST, c.clone().add(cos(a) * radius, 0.1 + height, sin(a) * radius), 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    override fun stop(boss: Boss) {
        boss.stopAnimation(settings.holdAnimation)
        boss.entity.setAI(true)
        center = null
        inside.clear()
        bolts.clear()
    }

    private companion object {
        const val PILLARS = 8
        const val EDGE_COOLDOWN = 20
        val WARN = Particle.DustOptions(Color.fromRGB(120, 60, 170), 0.9f)
        val EDGE = Particle.DustOptions(Color.fromRGB(200, 90, 255), 1.4f)
        val EDGE_DIM = Particle.DustOptions(Color.fromRGB(150, 70, 220), 0.8f)
        val PILLAR = Particle.DustOptions(Color.fromRGB(215, 140, 255), 1.0f)
    }
}
