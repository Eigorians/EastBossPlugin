package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerFx
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.BulletField
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 手榴弾。左手で弾帯から取ってピンを抜き、count 個を標的とその周りへ放り投げる。その後、右手の銃で追い打ち。
 *
 *   grabTick   … 左手の銃をしまい、手榴弾を持つ
 *   throwTick  … 投げる。1個目は標的の足元、残りは標的の周り（spreadRadius）に散らばる
 *   着地まで flightTicks。着地点には最初から赤い輪（爆発の範囲）が出る
 *   着地から fuseTicks で爆発（輪の点滅と音がだんだん速くなる）
 *   shootTicks … 右手の銃で追い打ち（逃げ回る相手を狙う）
 *   returnTick … 左手の銃を戻す
 *
 * 技は、全部の手榴弾が爆発するまで続く。
 */
class GrenadeSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val minRange: Double,
        val grabTick: Int,
        val throwTick: Int,
        val returnTick: Int,
        val endTicks: Int,
        val count: Int,
        val spreadRadius: Double,
        val flightTicks: Int,
        val arcHeight: Double,
        val fuseTicks: Int,
        val explosionRadius: Double,
        val damage: Double,
        val knockback: Double,
        val grenadeBlock: Material,
        val shootTicks: List<Int>,
        val bullet: BulletField.Spec,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 16.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 5.0),
                grabTick = section.int(Names.SkillConfig.GRAB_TICK, 4),
                throwTick = section.int(Names.SkillConfig.THROW_TICK, 15),
                returnTick = section.int(Names.SkillConfig.RETURN_TICK, 24),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 48),
                count = section.int(Names.SkillConfig.COUNT, 3).coerceAtLeast(1),
                spreadRadius = section.double(Names.SkillConfig.SPREAD_RADIUS, 4.0),
                flightTicks = section.int(Names.SkillConfig.FLIGHT_TICKS, 18).coerceAtLeast(1),
                arcHeight = section.double(Names.SkillConfig.ARC_HEIGHT, 4.0),
                fuseTicks = section.int(Names.SkillConfig.FUSE_TICKS, 30),
                explosionRadius = section.double(Names.SkillConfig.EXPLOSION_RADIUS, 3.5),
                damage = section.double(Names.SkillConfig.DAMAGE, 8.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.9),
                grenadeBlock = Material.matchMaterial(section.string(Names.SkillConfig.GRENADE_BLOCK, "TNT"))
                    ?.takeIf { it.isBlock } ?: Material.TNT,
                shootTicks = section.stringList(Names.SkillConfig.SHOOT_TICKS, listOf("32", "40"))
                    .mapNotNull { it.trim().toIntOrNull() },
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
            )
        }
    }

    private class Grenade(val from: Location, val to: Location, val display: BlockDisplay) {
        var age = 0
        var spin = 0f
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null
    private val grenades = mutableListOf<Grenade>()

    override fun canStart(boss: Boss): Boolean =
        (boss as GunnerBoss).findTarget(settings.minRange, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        grenades.clear()
        target = (boss as GunnerBoss).findTarget(settings.minRange, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GunnerBoss
        val s = settings

        if (tick <= s.throwTick || tick in s.shootTicks) Facing.faceTarget(boss, target)

        if (tick == s.grabTick) {
            g.setPartVisible("pistol_l", false)
            g.setPartVisible("grenade_hand", true)
        }
        if (tick == s.throwTick) {
            g.setPartVisible("grenade_hand", false)
            throwAll(g)
        }
        if (tick == s.returnTick && g.weapon == GunnerBoss.Weapon.PISTOLS) g.setPartVisible("pistol_l", true)
        if (tick in s.shootTicks) g.shoot(GunnerBoss.Muzzle.RIGHT, target?.takeIf { TargetFinder.isValidTarget(g, it) }, s.bullet)

        tickGrenades(g)

        tick++
        return tick >= s.endTicks && tick > s.throwTick && grenades.isEmpty()
    }

    private fun throwAll(g: GunnerBoss) {
        val s = settings
        val from = g.bonePoint("grenade_hand") ?: g.entity.location.add(0.0, g.entity.height * 0.8, 0.0)
        val t = target?.takeIf { TargetFinder.isValidTarget(g, it) }
        val center = t?.location ?: g.entity.location.add(Facing.forward(g.entity).multiply(8.0))
        val base = Random.nextDouble(Math.PI * 2)
        for (i in 0 until s.count) {
            val spot = if (i == 0) center.clone() else {
                // 残りは標的の周りに均等に散らす（少しずらす）
                val a = base + Math.PI * 2 * (i - 1) / (s.count - 1).coerceAtLeast(1)
                val r = s.spreadRadius * Random.nextDouble(0.7, 1.0)
                center.clone().add(cos(a) * r, 0.0, sin(a) * r)
            }
            spot.y = Ground.groundY(spot.world, spot.x, center.y + 3, spot.z)
            grenades += Grenade(from.clone(), spot, spawnDisplay(from))
        }
    }

    private fun spawnDisplay(at: Location): BlockDisplay =
        at.world.spawn(at, BlockDisplay::class.java) { d ->
            d.isPersistent = false
            d.block = settings.grenadeBlock.createBlockData()
            d.teleportDuration = 1
            d.interpolationDuration = 1
            d.brightness = Display.Brightness(15, 15)
            d.transformation = transformation(0f)
        }

    private fun transformation(spin: Float): Transformation {
        val size = GRENADE_SIZE
        val rot = Quaternionf().rotateXYZ(spin, spin * 0.7f, 0f)
        // 回転の中心をブロックの中心にする
        val offset = rot.transform(Vector3f(-size / 2, -size / 2, -size / 2))
        return Transformation(offset, rot, Vector3f(size, size, size), Quaternionf())
    }

    private fun tickGrenades(g: GunnerBoss) {
        val s = settings
        // 爆発で相手が倒れるとボスが消えて stop() が呼ばれることがあるので、写しを回す
        for (gr in grenades.toList()) {
            if (gr !in grenades) continue
            gr.age++
            val f = (gr.age.toDouble() / s.flightTicks).coerceAtMost(1.0)

            if (f < 1.0) {
                // 放物線で飛ぶ
                val p = gr.from.clone().add(gr.to.toVector().subtract(gr.from.toVector()).multiply(f))
                    .add(0.0, s.arcHeight * 4 * f * (1 - f), 0.0)
                gr.display.teleport(p)
                gr.spin += 0.6f
                gr.display.interpolationDelay = 0
                gr.display.transformation = transformation(gr.spin)
                gr.display.world.spawnParticle(Particle.SMOKE, p, 1, 0.02, 0.02, 0.02, 0.0)
                if (gr.age % 3 == 0) GunnerFx.ring(gr.to, s.explosionRadius, WARN_DIM)
                continue
            }

            val fuse = gr.age - s.flightTicks
            val world = gr.to.world
            if (fuse == 0) {
                gr.display.teleport(gr.to.clone().add(0.0, GRENADE_SIZE / 2.0, 0.0))
                world.playSound(gr.to, Sound.ENTITY_TNT_PRIMED, SoundCategory.HOSTILE, 0.8f, 1.6f)
                world.spawnParticle(Particle.BLOCK, gr.to, 6, 0.2, 0.05, 0.2, 0.0, gr.to.clone().add(0.0, -0.5, 0.0).block.blockData)
            }
            // 点滅と音が、爆発が近づくほど速くなる
            val remain = s.fuseTicks - fuse
            val period = when {
                remain <= 8 -> 2
                remain <= 16 -> 4
                else -> 6
            }
            if (fuse % period == 0) {
                GunnerFx.ring(gr.to, s.explosionRadius, WARN)
                world.playSound(gr.to, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.HOSTILE, 1.0f, (1.2f + fuse * 0.02f).coerceAtMost(2.0f))
            }
            world.spawnParticle(Particle.SMALL_FLAME, gr.to.clone().add(0.0, 0.45, 0.0), 1, 0.02, 0.02, 0.02, 0.01)

            if (fuse >= s.fuseTicks) {
                grenades.remove(gr)
                gr.display.remove()
                explode(g, gr.to)
            }
        }
    }

    private fun explode(g: GunnerBoss, at: Location) {
        val s = settings
        val world = at.world
        val c = at.clone().add(0.0, 0.5, 0.0)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(Particle.FLAME, c, 30, s.explosionRadius * 0.3, 0.3, s.explosionRadius * 0.3, 0.08)
        world.spawnParticle(Particle.LARGE_SMOKE, c, 20, s.explosionRadius * 0.3, 0.4, s.explosionRadius * 0.3, 0.03)
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.0f, 1.0f)

        val r = s.explosionRadius
        for (e in world.getNearbyEntities(c, r + 1, r + 1, r + 1)) {
            if (!TargetFinder.isValidTarget(g, e)) continue
            val battle = g.battle
            if (battle != null && !battle.isParticipant(e)) continue
            val center = e.boundingBox.center
            if (center.distanceSquared(c.toVector()) > r * r) continue
            val victim = e as LivingEntity
            victim.noDamageTicks = 0
            victim.damage(s.damage, g.entity)
            val away = victim.location.toVector().subtract(at.toVector()).setY(0)
            val out = if (away.lengthSquared() > 1e-4) away.normalize() else Vector(0, 0, 0)
            victim.velocity = out.multiply(s.knockback).setY(0.5)
        }
    }

    override fun stop(boss: Boss) {
        grenades.forEach { it.display.remove() }
        grenades.clear()
        val g = boss as GunnerBoss
        g.setPartVisible("grenade_hand", false)
        if (g.weapon == GunnerBoss.Weapon.PISTOLS) g.setPartVisible("pistol_l", true)
        boss.entity.setAI(true)
        target = null
    }

    private companion object {
        const val GRENADE_SIZE = 0.32f
        val WARN = Particle.DustOptions(Color.fromRGB(255, 60, 40), 1.3f)
        val WARN_DIM = Particle.DustOptions(Color.fromRGB(200, 70, 50), 0.9f)
    }
}
