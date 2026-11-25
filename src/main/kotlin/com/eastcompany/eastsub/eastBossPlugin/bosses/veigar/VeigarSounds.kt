package com.eastcompany.eastsub.eastBossPlugin.bosses.veigar

import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.veigar.skill.MagicCageSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.GroundShockwaveSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.MagicBoltSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.MeteorShowerSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.RotatingBeamSkill
import org.bukkit.Sound

/**
 * ベイガーの効果音（悪の魔法使い）。
 * 声 … ウィッチ（高めのピッチで小柄に）
 */
object VeigarSounds {

    val HURT = listOf(
        SoundCue(0, Sound.ENTITY_WITCH_HURT, 1.0f, 1.5f)
    )

    /** 杖の振り下ろし：振りかぶりで「ふんっ」→ 叩きつけ */
    fun staffSlam(s: BoneStrikeSkill.Settings): List<SoundCue> {
        val hit = s.hitWindows.firstOrNull()?.first ?: 10
        return listOf(
            SoundCue(0, Sound.ENTITY_WITCH_AMBIENT, 1.0f, 1.6f),
            SoundCue(hit, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.7f),
            SoundCue(hit, Sound.BLOCK_ANVIL_LAND, 0.4f, 1.8f),
        )
    }

    /** 薙ぎ払い：溜めの唸り → 振り抜きの風切り音 */
    fun sweep(s: BoneStrikeSkill.Settings): List<SoundCue> {
        val hit = s.hitWindows.firstOrNull()?.first ?: 8
        return listOf(
            SoundCue(0, Sound.ENTITY_WITCH_AMBIENT, 1.2f, 1.4f),
            SoundCue(hit - 1, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.5f, 0.7f),
            SoundCue(hit, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2.0f, 0.6f),
        )
    }

    /** 弾発射：宝石に力を溜める音 → 撃つたびに発射音 */
    fun magicBolt(s: MagicBoltSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(SoundCue(0, Sound.BLOCK_BEACON_POWER_SELECT, 1.5f, 1.7f))
        for (t in s.shootTicks) {
            cues += SoundCue(t, Sound.ENTITY_EVOKER_CAST_SPELL, 1.5f, 1.3f)
            cues += SoundCue(t, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.8f)
        }
        return cues
    }

    /** 回転ビーム：溜め → 発射の轟音 → 撃っている間の唸り → 途中で高笑い */
    fun rotatingBeam(s: RotatingBeamSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(
            SoundCue(0, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2.0f, 1.4f),
            SoundCue(s.fireStartTicks, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.4f),
        )
        for (t in s.fireStartTicks until s.fireEndTicks step 10) {
            cues += SoundCue(t, Sound.BLOCK_BEACON_AMBIENT, 2.0f, 1.8f)
        }
        cues += SoundCue((s.fireStartTicks + s.fireEndTicks) / 2, Sound.ENTITY_WITCH_CELEBRATE, 2.0f, 1.5f)
        return cues
    }

    /** 地面叩き：溜めの唸り → 衝撃波ごとに地響き＋爆発 */
    fun groundSlam(s: GroundShockwaveSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(
            SoundCue(0, Sound.ENTITY_WITCH_AMBIENT, 1.5f, 1.3f),
            SoundCue(4, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5f, 1.2f),
        )
        repeat(s.waves) { i ->
            val t = s.impactTicks + i * s.waveIntervalTicks
            cues += SoundCue(t, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 2.0f, 0.7f)
            cues += SoundCue(t, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.9f)
        }
        return cues
    }

    /**
     * 隕石の雨：詠唱の気配 → 浮き上がる → 大笑い（約1.5秒ごと）→ 着地 → ゼェゼェ（疲れている間）
     */
    fun meteorShower(s: MeteorShowerSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(
            SoundCue(0, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 1.4f),
            SoundCue(s.castStartTicks, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.5f, 1.2f),
        )
        var laugh = 0
        for (t in s.castStartTicks..s.castEndTicks step 30) {
            cues += SoundCue(t, Sound.ENTITY_WITCH_CELEBRATE, 2.0f, if (laugh++ % 2 == 0) 1.45f else 1.6f)
        }
        // アニメでは着地がおよそ endTicks の 5tick 前
        cues += SoundCue((s.endTicks - 5).coerceAtLeast(0), Sound.ENTITY_GENERIC_BIG_FALL, 1.5f, 1.3f)
        for (t in s.endTicks + s.exhaustedStartTicks until s.recoverTick step 24) {
            cues += SoundCue(t, Sound.ENTITY_WOLF_PANT, 1.0f, 1.6f)
        }
        return cues
    }

    /** 本気モード: 低い唸り → 力が集まる → 怒りの叫びと衝撃 → 高笑い */
    fun rageShift(s: PerformSkill.Settings, changeTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITCH_AMBIENT, 2.0f, 0.7f),
        SoundCue(0, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 0.8f),
        SoundCue(changeTick / 2, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2.0f, 0.7f),
        SoundCue(changeTick - 4, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2.0f, 0.8f),
        SoundCue(changeTick, Sound.ENTITY_RAVAGER_ROAR, 2.0f, 1.3f),
        SoundCue(changeTick, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.7f),
        SoundCue(changeTick, Sound.BLOCK_END_PORTAL_SPAWN, 1.2f, 1.4f),
        SoundCue(changeTick + 10, Sound.ENTITY_WITCH_CELEBRATE, 2.0f, 1.1f),
        SoundCue(changeTick + 20, Sound.ENTITY_WITCH_CELEBRATE, 2.0f, 1.3f),
    )

    /** 魔法陣の檻: 詠唱 → 檻が閉じる重い音 → 途中で高笑い（縁の衝撃・魔弾の音は技の中で鳴らす） */
    fun magicCage(s: MagicCageSkill.Settings): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2.0f, 1.2f),
        SoundCue(0, Sound.ENTITY_WITCH_AMBIENT, 1.5f, 1.5f),
        SoundCue(s.activateTick - 6, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 2.0f, 0.8f),
        SoundCue(s.activateTick, Sound.BLOCK_END_PORTAL_SPAWN, 1.5f, 1.6f),
        SoundCue(s.activateTick, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2.0f, 1.2f),
        SoundCue(s.activateTick + s.durationTicks / 2, Sound.ENTITY_WITCH_CELEBRATE, 2.0f, 1.5f),
    )
}
