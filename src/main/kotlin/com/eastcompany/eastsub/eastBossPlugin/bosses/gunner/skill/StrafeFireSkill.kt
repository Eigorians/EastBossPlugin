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
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.random.Random

/**
 * 横走りしながら撃つ。標的の周りを回るように横へ動き（左右はランダム）、右手の銃で一定間隔に撃つ。
 *
 * - 標的との距離を preferredDistance に近づけるよう、少しずつ寄る／離れる
 * - 壁で進めなくなったら反対へ走る
 * - 撃つ warningTicks 前から、右手の銃口から赤いレーザーサイトが出る
 */
class StrafeFireSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val animation: String,
        val range: Double,
        val minRange: Double,
        val durationTicks: Int,
        /** 1tick に進む距離 */
        val speed: Double,
        val preferredDistance: Double,
        val firstShotTick: Int,
        val shootIntervalTicks: Int,
        val warningTicks: Int,
        val bullet: BulletField.Spec,
    ) {
        /** 撃つ tick の一覧（効果音用） */
        val shotTicks: List<Int>
            get() = (firstShotTick until durationTicks step shootIntervalTicks.coerceAtLeast(1)).toList()

        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                animation = section.string(Names.SkillConfig.ANIMATION),
                range = section.double(Names.SkillConfig.RANGE, 20.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 4.0),
                durationTicks = section.int(Names.SkillConfig.DURATION_TICKS, 50),
                speed = section.double(Names.SkillConfig.SPEED, 0.3),
                preferredDistance = section.double(Names.SkillConfig.PREFERRED_DISTANCE, 11.0),
                firstShotTick = section.int(Names.SkillConfig.FIRST_SHOT_TICK, 10),
                shootIntervalTicks = section.int(Names.SkillConfig.SHOOT_INTERVAL_TICKS, 12).coerceAtLeast(1),
                warningTicks = section.int(Names.SkillConfig.WARNING_TICKS, 6),
                bullet = BulletField.Spec.load(section.section(Names.SkillConfig.BULLET)),
            )
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var side = 1
    private var turns = 0
    private var target: LivingEntity? = null

    override fun canStart(boss: Boss): Boolean =
        (boss as GunnerBoss).findTarget(settings.minRange, settings.range) != null

    override fun start(boss: Boss) {
        tick = 0
        turns = 0
        side = if (Random.nextBoolean()) 1 else -1
        target = (boss as GunnerBoss).findTarget(settings.minRange, settings.range)
        boss.entity.setAI(false)
        boss.playLoop(settings.animation, lerpTicks = 3)
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GunnerBoss
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) } ?: return true
        val s = settings

        move(g, t)

        // 次に撃つ tick
        if (tick >= s.firstShotTick) {
            val since = tick - s.firstShotTick
            if (since % s.shootIntervalTicks == 0) g.shoot(GunnerBoss.Muzzle.RIGHT, t, s.bullet)
        }
        val next = if (tick < s.firstShotTick) s.firstShotTick
        else s.firstShotTick + ((tick - s.firstShotTick) / s.shootIntervalTicks + 1) * s.shootIntervalTicks
        if (next - tick in 1..s.warningTicks && next < s.durationTicks) {
            val from = g.muzzle(GunnerBoss.Muzzle.RIGHT)
            GunnerFx.laser(from, g.aimDir(from, t), 30.0, GunnerFx.LASER)
        }

        tick++
        return tick >= s.durationTicks
    }

    /** 標的の周りを横へ回る。距離は preferredDistance に寄せる */
    private fun move(g: GunnerBoss, t: LivingEntity) {
        val entity = g.entity
        val from = entity.location
        val toTarget = Facing.horizontalDir(entity, t) ?: return
        val dist = Math.hypot(t.location.x - from.x, t.location.z - from.z)

        val tangent = Vector(-toTarget.z, 0.0, toTarget.x).multiply(side.toDouble())
        var radialAmount = ((dist - settings.preferredDistance) * 0.08).coerceIn(-0.7, 0.7)
        // 壁を背にしていたら下がらず、少し前に出ながら回り込む（壁際に張り付かない）
        if (radialAmount < 0 && Ground.freeLength(from, toTarget.clone().multiply(-1), BACK_SPACE) < BACK_SPACE) radialAmount = 0.3
        val radial = toTarget.clone().multiply(radialAmount)
        // 召喚地点から離れすぎたら戻る方へ寄る
        val step = tangent.add(radial).add(g.homePull()).normalize().multiply(settings.speed)

        val dest = Ground.step(from.clone().add(step))
        if (dest == null) {
            // 壁: 反対へ（何度もぶつかるならその場で撃つだけにする）
            if (turns < 2) {
                side = -side
                turns++
            }
            Facing.faceYaw(entity, Facing.yawOf(toTarget))
            return
        }
        dest.yaw = Facing.yawOf(toTarget)
        dest.pitch = 0f
        entity.teleport(dest)
        if (tick % 4 == 0) entity.world.spawnParticle(Particle.CLOUD, from.clone().add(0.0, 0.1, 0.0), 1, 0.2, 0.0, 0.2, 0.01)
    }

    override fun stop(boss: Boss) {
        boss.stopAnimation(settings.animation)
        boss.entity.setAI(true)
        target = null
    }

    private companion object {
        /** 背中側にこれだけの余裕がなければ、壁を背にしているとみなす（ブロック） */
        const val BACK_SPACE = 3.0
    }
}
