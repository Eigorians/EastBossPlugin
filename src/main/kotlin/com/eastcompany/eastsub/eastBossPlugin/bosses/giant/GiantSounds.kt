package com.eastcompany.eastsub.eastBossPlugin.bosses.giant

import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity

/**
 * 大巨人の効果音。どれも低く重い音（ピッチを下げる）で、大きさを伝える。
 * 動きの決まった tick に鳴らすものは SoundCue、位置で鳴らすものは play 関数で鳴らす。
 */
object GiantSounds {

    val HURT = listOf(
        SoundCue(0, Sound.ENTITY_IRON_GOLEM_HURT, 1.6f, 0.5f),
        SoundCue(0, Sound.ENTITY_RAVAGER_HURT, 1.0f, 0.6f),
    )

    fun play(at: Location, sound: Sound, volume: Float = 2.0f, pitch: Float = 1.0f) {
        at.world.playSound(at, sound, SoundCategory.HOSTILE, volume, pitch)
    }

    private fun play(e: Entity, sound: Sound, volume: Float = 2.0f, pitch: Float = 1.0f) = play(e.location, sound, volume, pitch)

    /** 動きの溜めに入る（低いうなり） */
    fun windup(e: Entity) {
        play(e, Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.5f)
        play(e, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.4f, 0.5f)
    }

    /** 棍棒が風を切る（当たる少し前） */
    fun whoosh(e: Entity) {
        play(e, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2.0f, 0.5f)
        play(e, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.4f, 0.5f)
    }

    /** 地面を叩く・踏む */
    fun impact(at: Location) {
        play(at, Sound.ENTITY_GENERIC_EXPLODE, 2.4f, 0.6f)
        play(at, Sound.BLOCK_ANVIL_LAND, 1.4f, 0.5f)
        play(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.4f, 0.5f)
    }

    /** 地面が割れていく（大地割りの1区間ごと） */
    fun crack(at: Location) {
        play(at, Sound.BLOCK_DEEPSLATE_BREAK, 1.6f, 0.6f)
        play(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.0f, 0.6f)
    }

    /** 岩をつかむ・投げる・岩が落ちる */
    fun grab(e: Entity) = play(e, Sound.BLOCK_STONE_BREAK, 2.0f, 0.5f)
    fun throwRock(e: Entity) {
        play(e, Sound.ENTITY_SNOWBALL_THROW, 2.0f, 0.4f)
        play(e, Sound.ENTITY_RAVAGER_ATTACK, 1.6f, 0.6f)
    }

    /** 大跳躍: 跳ぶ */
    fun leap(e: Entity) {
        play(e, Sound.ENTITY_GOAT_LONG_JUMP, 2.0f, 0.4f)
        play(e, Sound.ENTITY_RAVAGER_ROAR, 1.6f, 0.7f)
    }

    /** 疲れて肩で息をする（数 tick おき） */
    fun pant(e: Entity) = play(e, Sound.ENTITY_RAVAGER_STUNNED, 1.2f, 0.5f)

    /** 登場: 岩が崩れる → 胸を叩く → 吠える */
    fun spawn(): List<SoundCue> = listOf(
        SoundCue(0, Sound.BLOCK_DEEPSLATE_BREAK, 2.0f, 0.5f),
        SoundCue(10, Sound.ENTITY_IRON_GOLEM_REPAIR, 1.6f, 0.5f),
        SoundCue(35, Sound.ENTITY_IRON_GOLEM_ATTACK, 2.0f, 0.4f),
        SoundCue(41, Sound.ENTITY_IRON_GOLEM_ATTACK, 2.0f, 0.4f),
        SoundCue(46, Sound.ENTITY_RAVAGER_ROAR, 2.6f, 0.45f),
        SoundCue(46, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.5f),
    )

    /** 本気: 力を溜める → changeTick で吠える */
    fun roar(changeTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_RAVAGER_STUNNED, 1.6f, 0.4f),
        SoundCue(changeTick / 2, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.6f),
        SoundCue(changeTick, Sound.ENTITY_RAVAGER_ROAR, 3.0f, 0.4f),
        SoundCue(changeTick, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.8f, 0.45f),
        SoundCue(changeTick, Sound.ENTITY_BLAZE_SHOOT, 1.6f, 0.4f),
    )

    fun death(e: Entity) {
        play(e, Sound.ENTITY_IRON_GOLEM_DEATH, 2.4f, 0.4f)
        play(e, Sound.ENTITY_RAVAGER_DEATH, 2.0f, 0.5f)
    }

    /** 倒れ込んだ地響き */
    fun fallen(at: Location) {
        play(at, Sound.ENTITY_GENERIC_EXPLODE, 3.0f, 0.4f)
        play(at, Sound.BLOCK_DEEPSLATE_BREAK, 2.4f, 0.4f)
    }
}
