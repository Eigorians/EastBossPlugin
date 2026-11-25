package com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha

import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha.skill.LeapSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.DashSkill
import org.bukkit.Sound

/**
 * チュートリアルアルファの効果音（メイド系のかわいい雰囲気）。
 * タイミングは技の設定値から決めるので、設定を変えても音がずれない。
 *
 * 声  … アレイ（高くてかわいい声）
 * 剣  … スイープ攻撃音 ＋ アメジストの鈴の音
 */
object TutorialAlphaSounds {

    /** 被ダメージの声 */
    val HURT = listOf(
        SoundCue(0, Sound.ENTITY_ALLAY_HURT, 1.0f, 1.3f)
    )

    /** 突進：「えいっ」→ 飛び出す風切り音 */
    fun dash(s: DashSkill.Settings) = listOf(
        SoundCue(0, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.0f, 1.4f),
        SoundCue(s.windupTicks, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.9f, 1.6f)
    )

    /** 剣振り：構えでキラッ → 一閃 → （2回目の判定があれば）もう一閃 */
    fun swordSlash(s: BoneStrikeSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(SoundCue(0, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.5f))
        s.hitWindows.getOrNull(0)?.let { w ->
            cues += SoundCue(w.first - 2, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.0f, 1.5f)
            cues += SoundCue(w.first, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.3f)
        }
        // 2回目の振りはアニメ上、判定開始から少し遅れて剣が届く
        s.hitWindows.getOrNull(1)?.let { w ->
            cues += SoundCue(w.first + 7, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.0f, 1.6f)
            cues += SoundCue(w.first + 9, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.5f)
        }
        return cues
    }

    /** 跳躍：「よいしょ」→ ジャンプ → 最高点でキラッ → 着地のドスン（軽め） */
    fun leap(s: LeapSkill.Settings) = listOf(
        SoundCue(0, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.0f, 1.2f),
        SoundCue(s.jumpStartTicks, Sound.ENTITY_GOAT_LONG_JUMP, 1.0f, 1.5f),
        SoundCue(s.peakTicks, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 2.0f),
        SoundCue(s.landTicks, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f),
        SoundCue(s.landTicks, Sound.ENTITY_ALLAY_HURT, 1.0f, 1.2f)
    )

    /** ジャブ：短く軽いパンチ音 */
    fun jab(s: BoneStrikeSkill.Settings): List<SoundCue> {
        val start = s.hitWindows.firstOrNull()?.first ?: return emptyList()
        return listOf(
            SoundCue(start - 2, Sound.ENTITY_ALLAY_ITEM_THROWN, 1.0f, 1.6f),
            SoundCue(start, Sound.ENTITY_PLAYER_ATTACK_WEAK, 1.0f, 1.5f)
        )
    }

    /** 練習の素振り: 合図の tick ごとに音が高くなり（最後は澄んだ鈴）、振る */
    fun lessonSwing(countdownTicks: List<Int>, s: BoneStrikeSkill.Settings): List<SoundCue> {
        val start = s.hitWindows.firstOrNull()?.first ?: return emptyList()
        val cues = countdownTicks.mapIndexed { i, t ->
            if (i == countdownTicks.lastIndex) SoundCue(t, Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, 1.8f)
            else SoundCue(t, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, 1.0f + 0.3f * i)
        }
        return cues + SoundCue(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.3f)
    }

    /** 溜め斬り：剣が光る（溜め）→ 斬る */
    fun chargeSlash(s: BoneStrikeSkill.Settings): List<SoundCue> {
        val start = s.hitWindows.firstOrNull()?.first ?: return emptyList()
        return listOf(
            SoundCue(0, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.6f),
            SoundCue(start - 2, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.0f, 1.4f),
            SoundCue(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f),
            SoundCue(start, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 2.0f)
        )
    }

    /** 登場の挨拶：「よろしくね」→ お辞儀の鈴 */
    fun greeting() = listOf(
        SoundCue(0, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.0f, 1.3f),
        SoundCue(4, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f),
        SoundCue(12, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f),
        SoundCue(20, Sound.ENTITY_ALLAY_ITEM_GIVEN, 1.0f, 1.4f),
    )

    /** 少しだけ本気：花びらが集まる → changeTick で「ここからが本番！」 */
    fun serious(changeTick: Int) = listOf(
        SoundCue(0, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.2f),
        SoundCue(changeTick / 2, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.5f),
        SoundCue(changeTick, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.2f, 1.1f),
        SoundCue(changeTick, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, 1.4f),
        SoundCue(changeTick, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f),
    )
}
