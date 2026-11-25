package com.eastcompany.eastsub.eastBossPlugin.bosses.veigar

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.BossSpawn
import com.eastcompany.eastsub.eastBossPlugin.boss.BossType
import com.eastcompany.eastsub.eastBossPlugin.boss.phase.BossPhase
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.veigar.skill.MagicCageSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.GroundShockwaveSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.MagicBoltSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.MeteorShowerSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.RotatingBeamSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.inventory.ItemStack
import kotlin.math.cos
import kotlin.math.sin

/** ベイガー（設定: bosses/veigar.yml） */
object VeigarType : BossType {
    override val id = "veigar"
    override val icon: ItemStack get() = ItemStack.of(Material.AMETHYST_SHARD)
    override fun create(spawn: BossSpawn): Boss = VeigarBoss(spawn)
}

/**
 * ベイガー。杖を持った巨大な魔法使い。
 *
 * - 技: 杖の振り下ろし / 薙ぎ払い / 地面叩き（ジャンプでかわせる衝撃波）/ 魔法の弾 / 回転ビーム（ジャンプで飛び越える）
 *       / 隕石の雨（宙に浮いて大笑いしながら10秒詠唱 → 疲れ果てて隙だらけ）
 * - プレイヤーの3倍ほどの大きさ（model-scale / entity-scale）
 * - HP が rage-health-ratio 以下で本気になる（rage_shift: 杖を掲げて怒り、目が赤く光って周りを吹き飛ばす）。
 *   本気の技は強くなり（弾が増える・衝撃波が3回・ビームが一周・隕石が増える）、魔法陣の檻が加わる。
 *   本気の間は体から紫の瘴気が立ちのぼる
 */
class VeigarBoss(spawn: BossSpawn) : Boss(spawn) {

    override val hurtSounds: List<SoundCue> get() = VeigarSounds.HURT

    /** 本気になったか（第2フェーズ） */
    var isRaging = false
        private set

    private var age = 0

    override fun createPhases(): List<BossPhase> {
        val rageRatio = config.double(Names.BossConfig.RAGE_HEALTH_RATIO, 0.5)
        return listOf(
            BossPhase(NORMAL, 1.0, listOf(
                staffSlam(STAFF_SLAM), sweep(SWEEP), groundSlam(GROUND_SLAM),
                magicBolt(MAGIC_BOLT), rotatingBeam(ROTATING_BEAM), meteorShower(METEOR_SHOWER),
            )),
            // 本気: 強化版の技と、魔法陣の檻
            BossPhase(RAGE, rageRatio, listOf(
                staffSlam(STAFF_SLAM_RAGE), sweep(SWEEP_RAGE), groundSlam(GROUND_SLAM_RAGE),
                magicBolt(MAGIC_BOLT_RAGE), rotatingBeam(ROTATING_BEAM_RAGE), meteorShower(METEOR_SHOWER_RAGE),
                magicCage(),
            )),
        )
    }

    private fun staffSlam(id: String) =
        BoneStrikeSkill.Settings.load(skillSection(id)).let { BoneStrikeSkill(id, it, VeigarSounds.staffSlam(it)) }

    private fun sweep(id: String) =
        BoneStrikeSkill.Settings.load(skillSection(id)).let { BoneStrikeSkill(id, it, VeigarSounds.sweep(it)) }

    private fun groundSlam(id: String) =
        GroundShockwaveSkill.Settings.load(skillSection(id)).let { GroundShockwaveSkill(id, it, VeigarSounds.groundSlam(it)) }

    private fun magicBolt(id: String) =
        MagicBoltSkill.Settings.load(skillSection(id)).let { MagicBoltSkill(id, it, VeigarSounds.magicBolt(it)) }

    private fun rotatingBeam(id: String) =
        RotatingBeamSkill.Settings.load(skillSection(id)).let { RotatingBeamSkill(id, it, VeigarSounds.rotatingBeam(it)) }

    private fun meteorShower(id: String) =
        MeteorShowerSkill.Settings.load(skillSection(id)).let { MeteorShowerSkill(id, it, VeigarSounds.meteorShower(it)) }

    private fun magicCage(): BossSkill =
        MagicCageSkill.Settings.load(skillSection(MAGIC_CAGE)).let { MagicCageSkill(MAGIC_CAGE, it, VeigarSounds.magicCage(it)) }

    // ===== 毎tick・フェーズ =====

    override fun onTick() {
        age++
        // 召喚直後はモデルの表示がまだ届いていないことがあるので、少し後にもう一度合わせる
        if (isRaging && (age == 5 || age == 40)) tintBones(rageEyeColor(), *EYES)
        if (isRaging && age % 3 == 0) aura()
    }

    override fun onPhaseChange(from: BossPhase?, to: BossPhase) {
        if (from == null || to.id != RAGE) return
        // 本気モード: 杖を掲げて怒り、change-tick で目が赤く光って周りを吹き飛ばす
        val section = skillSection(RAGE_SHIFT)
        val s = PerformSkill.Settings.load(section)
        val changeTick = section.int(Names.SkillConfig.CHANGE_TICK, 30)
        val pushRadius = section.double(Names.SkillConfig.IMPACT_RADIUS, 7.0)
        val push = section.double(Names.SkillConfig.KNOCKBACK, 1.4)
        queueSkill(PerformSkill(RAGE_SHIFT, s, VeigarSounds.rageShift(s, changeTick)) { boss, tick ->
            val v = boss as VeigarBoss
            if (tick < changeTick && tick % 2 == 0) v.gatherEffect(tick.toDouble() / changeTick)
            if (tick == changeTick) v.enrage(pushRadius, push)
        })
    }

    /** 溜め: 周りから紫の光が杖へ吸い込まれる */
    private fun gatherEffect(progress: Double) {
        val center = entity.location.add(0.0, entity.height * 0.6, 0.0)
        val r = 6.0 * (1 - progress) + 1.0
        for (i in 0 until 10) {
            val a = Math.PI * 2 * i / 10 + progress * 4
            val p = center.clone().add(cos(a) * r, (i % 3 - 1) * 0.8, sin(a) * r)
            entity.world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, AURA)
        }
        entity.world.spawnParticle(Particle.REVERSE_PORTAL, center, 6, 1.0, 1.0, 1.0, 0.05)
    }

    /** 本気になる: 目が赤くなり、周りの相手を吹き飛ばす（ダメージはない） */
    private fun enrage(pushRadius: Double, push: Double) {
        isRaging = true
        tintBones(rageEyeColor(), *EYES)
        val world = entity.world
        val at = entity.location
        world.spawnParticle(Particle.FLASH, at.clone().add(0.0, entity.height * 0.6, 0.0), 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(210, 80, 255))
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at.clone().add(0.0, 1.0, 0.0), 1)
        world.spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0.0, 2.0, 0.0), 200, 2.5, 2.0, 2.5, 0.3)
        // 地面を走る衝撃の輪
        for (ring in 1..3) {
            val r = pushRadius * ring / 3
            val n = (r * 8).toInt()
            for (i in 0 until n) {
                val a = Math.PI * 2 * i / n
                world.spawnParticle(Particle.DUST, at.clone().add(cos(a) * r, 0.2, sin(a) * r), 1, 0.0, 0.0, 0.0, 0.0, AURA_BRIGHT)
            }
        }
        for (e in entity.getNearbyEntities(pushRadius, 4.0, pushRadius)) {
            if (!TargetFinder.isValidTarget(this, e)) continue
            val away = e.location.toVector().subtract(at.toVector()).setY(0)
            if (away.lengthSquared() < 1e-4) continue
            e.velocity = away.normalize().multiply(push).setY(0.6)
        }
    }

    /** 本気の間、体から紫の瘴気が立ちのぼる */
    private fun aura() {
        val at = entity.location
        val w = entity.width * 0.5
        entity.world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.3, 0.0), 3, w, 0.2, w, 0.0, AURA)
        entity.world.spawnParticle(Particle.WITCH, at.clone().add(0.0, entity.height * 0.5, 0.0), 2, w, entity.height * 0.3, w, 0.0)
        if (age % 12 == 0) entity.world.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0.0, 0.1, 0.0), 4, w, 0.05, w, 0.02)
    }

    private fun rageEyeColor(): Int =
        config.string(Names.BossConfig.RAGE_EYE_COLOR).trim().removePrefix("#").toIntOrNull(16) ?: 0xFF3030

    private companion object {
        const val NORMAL = "normal"
        const val RAGE = "rage"

        /** 目のボーン名（glow_ を除いたもの） */
        val EYES = arrayOf("eye_r", "eye_l")

        val AURA = Particle.DustOptions(Color.fromRGB(140, 50, 200), 1.2f)
        val AURA_BRIGHT = Particle.DustOptions(Color.fromRGB(215, 110, 255), 1.6f)

        // 技ID（設定ファイルの skills.<ID>）
        const val STAFF_SLAM = "staff_slam"
        const val SWEEP = "sweep"
        const val GROUND_SLAM = "ground_slam"
        const val MAGIC_BOLT = "magic_bolt"
        const val ROTATING_BEAM = "rotating_beam"
        const val METEOR_SHOWER = "meteor_shower"

        // 本気
        const val RAGE_SHIFT = "rage_shift"
        const val STAFF_SLAM_RAGE = "staff_slam_rage"
        const val SWEEP_RAGE = "sweep_rage"
        const val GROUND_SLAM_RAGE = "ground_slam_rage"
        const val MAGIC_BOLT_RAGE = "magic_bolt_rage"
        const val ROTATING_BEAM_RAGE = "rotating_beam_rage"
        const val METEOR_SHOWER_RAGE = "meteor_shower_rage"
        const val MAGIC_CAGE = "magic_cage"
    }
}
