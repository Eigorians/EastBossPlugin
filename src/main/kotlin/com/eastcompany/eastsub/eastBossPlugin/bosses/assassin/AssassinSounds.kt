package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin

import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity

/**
 * 影の暗殺者の効果音。声はほとんど出さず、足音・刃音・影の音で気配を伝える。
 * 技の決まった tick に鳴らすものは SoundCue、動きに合わせて鳴らすものは play 関数で鳴らす。
 */
object AssassinSounds {

    val HURT = listOf(
        SoundCue(0, Sound.ENTITY_PLAYER_HURT, 1.2f, 0.75f),
        SoundCue(0, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.7f),
    )

    fun play(at: Location, sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f) {
        at.world.playSound(at, sound, SoundCategory.HOSTILE, volume, pitch)
    }

    private fun play(e: Entity, sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f) = play(e.location, sound, volume, pitch)

    /** 小刻みなステップ（軽い踏み切りと、布のはためき） */
    fun step(e: Entity) {
        play(e, Sound.BLOCK_WOOL_STEP, 0.8f, 1.4f)
        play(e, Sound.ENTITY_PHANTOM_FLAP, 0.35f, 1.9f)
    }

    /** 見切り（攻撃をすり抜ける。残像が残る） */
    fun dodge(e: Entity) {
        play(e, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1.6f)
        play(e, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 1.0f, 1.3f)
    }

    /** 苦無を投げる */
    fun throwKunai(e: Entity) {
        play(e, Sound.ITEM_TRIDENT_THROW, 1.0f, 1.8f)
        play(e, Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.5f)
    }

    /** 影に沈む */
    fun sink(e: Entity) {
        play(e, Sound.ENTITY_SQUID_SQUIRT, 1.4f, 0.6f)
        play(e, Sound.BLOCK_SCULK_SPREAD, 1.4f, 0.6f)
        play(e, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.5f)
    }

    /** 影が地面を這う（数 tick おき） */
    fun creep(at: Location) {
        play(at, Sound.BLOCK_SCULK_SPREAD, 0.7f, 0.8f)
    }

    /** 飛び出す前の予告（鼓動のような低い音） */
    fun emergeWarn(at: Location) {
        play(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.6f, 1.5f)
        play(at, Sound.BLOCK_SCULK_SENSOR_CLICKING, 1.0f, 0.7f)
    }

    /** 影から飛び出して斬り上げる */
    fun emerge(e: Entity) {
        play(e, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.4f, 1.2f)
        play(e, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.8f)
        play(e, Sound.ENTITY_SQUID_SQUIRT, 1.0f, 1.2f)
    }

    /** 分身が弾ける */
    fun decoyBurst(at: Location) {
        play(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.8f)
        play(at, Sound.ENTITY_SQUID_SQUIRT, 1.0f, 0.9f)
    }

    /** 光にあぶり出される */
    fun exposed(e: Entity) {
        play(e, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.4f, 0.8f)
        play(e, Sound.ENTITY_PLAYER_HURT_ON_FIRE, 1.2f, 0.7f)
        play(e, Sound.ENTITY_SQUID_SQUIRT, 1.2f, 0.5f)
    }

    /** 影縫いの苦無が地面に刺さる */
    fun pin(at: Location) {
        play(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.2f, 1.6f)
        play(at, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f)
    }

    /** 影を縫いとめた */
    fun bound(at: Location) {
        play(at, Sound.ENTITY_EVOKER_CAST_SPELL, 1.2f, 1.4f)
        play(at, Sound.BLOCK_CHAIN_HIT, 1.2f, 0.6f)
    }

    /** 影舞: 構えて溜める */
    fun dashReady(e: Entity) {
        play(e, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 1.5f)
    }

    /** 影舞: 一閃 */
    fun dash(e: Entity) {
        play(e, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.2f, 1.7f)
        play(e, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 1.3f)
    }

    /** 影舞: 跳び上がる */
    fun leap(e: Entity) {
        play(e, Sound.ENTITY_PHANTOM_FLAP, 1.2f, 1.2f)
        play(e, Sound.ITEM_TRIDENT_RIPTIDE_2, 1.0f, 1.4f)
    }

    /** 影舞: 突き下ろして着地 */
    fun finishImpact(at: Location) {
        play(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.6f, 0.7f)
        play(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.6f)
        play(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.4f, 0.8f)
    }

    /** 疲れて肩で息をする（数 tick おき） */
    fun pant(e: Entity) {
        play(e, Sound.ENTITY_WOLF_PANT, 0.7f, 0.6f)
    }

    fun death(e: Entity) {
        play(e, Sound.ENTITY_PLAYER_DEATH, 1.2f, 0.7f)
        play(e, Sound.ENTITY_SQUID_SQUIRT, 1.4f, 0.5f)
    }

    /** 死んで影に溶ける */
    fun dissolve(e: Entity) {
        play(e, Sound.BLOCK_SCULK_SPREAD, 1.6f, 0.5f)
        play(e, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.5f)
    }

    // ===== 技の決まった tick に鳴らす音 =====

    /** 連撃: 判定区間の始まりごとに刃音 */
    fun combo(s: BoneStrikeSkill.Settings): List<SoundCue> = s.hitWindows.flatMapIndexed { i, w ->
        val pitch = 1.2f + 0.08f * i
        listOf(
            SoundCue(w.first, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, pitch),
            SoundCue(w.first, Sound.ITEM_TRIDENT_THROW, 0.7f, 1.9f),
        )
    }

    /** 登場: 影が広がる音 → 浮かび上がる → 短刀を回す */
    fun spawn(s: PerformSkill.Settings): List<SoundCue> = listOf(
        SoundCue(0, Sound.BLOCK_SCULK_SPREAD, 1.8f, 0.5f),
        SoundCue(0, Sound.ENTITY_WARDEN_HEARTBEAT, 1.6f, 1.2f),
        SoundCue(6, Sound.ENTITY_SQUID_SQUIRT, 1.2f, 0.6f),
        SoundCue(16, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 0.9f),
        SoundCue(23, Sound.ITEM_TRIDENT_THROW, 1.0f, 1.8f),
        SoundCue(27, Sound.ITEM_TRIDENT_THROW, 1.0f, 2.0f),
        SoundCue(36, Sound.ITEM_ARMOR_EQUIP_IRON, 1.2f, 1.4f),
    ).filter { it.tick < s.endTicks }

    /** 本気: 力を溜める → changeTick で影がはじける */
    fun rageShift(changeTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 1.4f, 0.7f),
        SoundCue(8, Sound.ENTITY_WARDEN_HEARTBEAT, 1.8f, 1.0f),
        SoundCue(16, Sound.ENTITY_WARDEN_HEARTBEAT, 1.8f, 1.2f),
        SoundCue(changeTick, Sound.ENTITY_WITHER_SHOOT, 1.2f, 0.6f),
        SoundCue(changeTick, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.6f, 0.7f),
        SoundCue(changeTick, Sound.ENTITY_SQUID_SQUIRT, 1.6f, 0.4f),
    )

    /** 挑発: 血を払う → 短刀を回す */
    fun taunt(): List<SoundCue> = listOf(
        SoundCue(6, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 1.8f),
        SoundCue(11, Sound.ITEM_TRIDENT_THROW, 0.9f, 2.0f),
        SoundCue(16, Sound.ITEM_TRIDENT_THROW, 0.9f, 2.0f),
        SoundCue(22, Sound.ITEM_ARMOR_EQUIP_IRON, 1.0f, 1.5f),
    )

    /** 疲れから立ち直る */
    fun recover(): List<SoundCue> = listOf(SoundCue(0, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.9f))
}
