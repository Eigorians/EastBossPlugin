package com.eastcompany.eastsub.eastBossPlugin.boss.sound

import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity

/**
 * 「行動開始から何 tick 目に何の音を鳴らすか」。1秒 = 20tick。
 * 技（BossSkill.sounds）や被ダメージ時の声（Boss.hurtSounds）に使う。
 */
data class SoundCue(
    val tick: Int,
    val sound: Sound,
    val volume: Float = 2.0f,
    val pitch: Float = 1.0f,
) {
    /** エンティティの位置で鳴らす */
    fun play(at: Entity) {
        at.world.playSound(at.location, sound, SoundCategory.HOSTILE, volume, pitch)
    }
}
