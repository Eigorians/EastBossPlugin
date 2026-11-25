package com.eastcompany.eastsub.eastBossPlugin.status

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player

/** ジャストガードの瞬間の演出（盾の前の火花と澄んだ金属音、アクションバー）。攻撃を受けたときも、弾を弾き返したときも同じ */
object JustGuardFx {

    /** toward = 攻撃が来た方（ボスや弾の位置） */
    fun play(player: Player, toward: Location, actionBar: Component) {
        val eye = player.eyeLocation
        val v = toward.toVector().subtract(eye.toVector())
        val dir = if (v.lengthSquared() > 1e-6) v.normalize() else eye.direction
        val at = eye.clone().add(dir.multiply(0.8)).add(0.0, -0.35, 0.0)
        val world = player.world
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, 18, 0.15, 0.15, 0.15, 0.3)
        world.spawnParticle(Particle.END_ROD, at, 5, 0.05, 0.05, 0.05, 0.08)
        world.playSound(at, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.2f, 1.6f)
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.45f, 2.0f)
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.6f, 1.4f)
        player.sendActionBar(actionBar)
    }
}
