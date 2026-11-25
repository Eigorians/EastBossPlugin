package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon

import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity

/**
 * ドラゴンの効果音。
 * 長さの決まらない動き（標的への接近など）の後に鳴らす音が多いので、技の中から [play] で直接鳴らす。
 * 演出（PerformSkill）の音だけ SoundCue の一覧で渡す。
 */
object DragonSounds {

    /** 大きな体なので、遠くまで聞こえるよう大きめに鳴らす */
    fun play(at: Entity, sound: Sound, volume: Float = 3.0f, pitch: Float = 1.0f) {
        at.world.playSound(at.location, sound, SoundCategory.HOSTILE, volume, pitch)
    }

    fun play(at: Location, sound: Sound, volume: Float = 3.0f, pitch: Float = 1.0f) {
        at.world.playSound(at, sound, SoundCategory.HOSTILE, volume, pitch)
    }

    /** 殴られたときのうめき声 */
    val HURT = listOf(
        SoundCue(0, Sound.ENTITY_ENDER_DRAGON_HURT, 1.6f, 1.15f),
    )

    /** 羽ばたき（飛び回っている間、翼を打ち下ろすたびに） */
    fun flap(at: Entity, heavy: Boolean = false) {
        play(at, Sound.ENTITY_ENDER_DRAGON_FLAP, if (heavy) 3.0f else 2.0f, if (heavy) 0.75f else 0.95f)
    }

    /** 吠える（登場・本気・技の始まり） */
    fun roar(at: Entity, pitch: Float = 0.9f) {
        play(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 5.0f, pitch)
        play(at, Sound.ENTITY_RAVAGER_ROAR, 3.0f, pitch * 0.6f)
    }

    /** うなる（技の予告） */
    fun growl(at: Entity, pitch: Float = 0.8f) {
        play(at, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 4.0f, pitch)
    }

    /** 登場: 羽ばたきながら降りてきて、2秒（40tick）で吠える */
    fun spawn(s: PerformSkill.Settings, roarTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_ENDER_DRAGON_FLAP, 4.0f, 0.7f),
        SoundCue(0, Sound.ITEM_ELYTRA_FLYING, 2.0f, 0.6f),
        SoundCue(14, Sound.ENTITY_ENDER_DRAGON_FLAP, 4.0f, 0.75f),
        SoundCue(28, Sound.ENTITY_ENDER_DRAGON_FLAP, 4.0f, 0.8f),
        SoundCue(roarTick, Sound.ENTITY_ENDER_DRAGON_GROWL, 6.0f, 0.85f),
        SoundCue(roarTick, Sound.ENTITY_RAVAGER_ROAR, 4.0f, 0.5f),
        SoundCue(roarTick + 2, Sound.ENTITY_BLAZE_SHOOT, 3.0f, 0.5f),
    ).filter { it.tick < s.endTicks }

    /** 本気モード: 翼で体を包んで力を溜め（体の中の炎がうなる）、changeTick で翼を広げて吠える */
    fun rageShift(s: PerformSkill.Settings, changeTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 4.0f, 0.6f),
        SoundCue(4, Sound.ENTITY_BLAZE_BURN, 3.0f, 0.5f),
        SoundCue(12, Sound.BLOCK_FIRE_AMBIENT, 4.0f, 0.6f),
        SoundCue(18, Sound.ENTITY_BLAZE_AMBIENT, 3.0f, 0.5f),
        SoundCue(changeTick, Sound.ENTITY_ENDER_DRAGON_GROWL, 6.0f, 0.7f),
        SoundCue(changeTick, Sound.ENTITY_GENERIC_EXPLODE, 3.0f, 0.6f),
        SoundCue(changeTick, Sound.ENTITY_RAVAGER_ROAR, 4.0f, 0.45f),
        SoundCue(changeTick + 4, Sound.ENTITY_BLAZE_SHOOT, 3.0f, 0.4f),
    ).filter { it.tick < s.endTicks }

    /** 死ぬとき: 体の中の炎に焼かれて崩れる */
    fun death(at: Entity) {
        play(at, Sound.ENTITY_ENDER_DRAGON_DEATH, 2.5f, 1.3f)
    }

    fun burnOut(at: Location) {
        play(at, Sound.ENTITY_GENERIC_EXPLODE, 3.0f, 0.6f)
        play(at, Sound.ENTITY_BLAZE_DEATH, 3.0f, 0.5f)
        play(at, Sound.BLOCK_FIRE_EXTINGUISH, 3.0f, 0.6f)
    }
}
