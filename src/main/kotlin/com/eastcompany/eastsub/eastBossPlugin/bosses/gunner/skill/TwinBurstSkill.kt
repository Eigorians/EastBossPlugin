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
import org.bukkit.entity.LivingEntity

/**
 * 二丁拳銃の乱れ撃ち。両手の銃を構え、shootTicks の各 tick に左右交互に1発ずつ撃つ（右から）。
 *
 *   0 〜 最初の弾 … 構える。両方の銃口から標的へ赤いレーザーサイト（これが消えたら撃ってくる）
 *   shootTicks    … 撃つ瞬間の標的の胸元を狙う（先読みはしない）。横に動き続ければかわせる
 *   〜 endTicks   … 銃を下ろす
 */
class TwinBurstSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val minRange: Double,
        val shootTicks: List<Int>,
        val endTicks: Int,
        val bullet: BulletField.Spec,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 22.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 4.0),
                shootTicks = section.stringList(Names.SkillConfig.SHOOT_TICKS, listOf("8", "12", "16", "20", "24", "28"))
                    .mapNotNull { it.trim().toIntOrNull() }.sorted(),
                endTicks = section.int(Names.SkillConfig.END_TICKS, 40),
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var target: LivingEntity? = null

    override fun canStart(boss: Boss): Boolean =
        (boss as GunnerBoss).findTarget(settings.minRange, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        target = (boss as GunnerBoss).findTarget(settings.minRange, settings.range)
        boss.entity.setAI(false)
        Facing.faceTarget(boss, target)
        boss.playOnce(settings.animation)
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GunnerBoss
        val first = settings.shootTicks.firstOrNull() ?: 0
        val last = settings.shootTicks.lastOrNull() ?: 0

        if (tick <= last) Facing.faceTarget(boss, target)

        // 撃ち始めるまで、両手の銃口からレーザーサイト
        if (tick in 4 until first && tick % 2 == 0) {
            for (m in listOf(GunnerBoss.Muzzle.RIGHT, GunnerBoss.Muzzle.LEFT)) {
                val from = g.muzzle(m)
                GunnerFx.laser(from, g.aimDir(from, target), LASER_LENGTH, GunnerFx.LASER)
            }
        }

        val index = settings.shootTicks.indexOf(tick)
        if (index >= 0) {
            val m = if (index % 2 == 0) GunnerBoss.Muzzle.RIGHT else GunnerBoss.Muzzle.LEFT
            g.shoot(m, target, settings.bullet)
        }

        tick++
        return tick >= settings.endTicks
    }

    override fun stop(boss: Boss) {
        boss.entity.setAI(true)
        target = null
    }

    private companion object {
        const val LASER_LENGTH = 30.0
    }
}
