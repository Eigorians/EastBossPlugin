package com.eastcompany.eastsub.eastBossPlugin.bosses.knight

import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

/**
 * 髑髏騎士の効果音。乾いた骨の音と、重い鉄の大剣の音。
 * 技の決まった tick に鳴らすものは SoundCue（tick は振り始めから）、位置で鳴らすものは関数で鳴らす。
 */
object KnightSounds {

    val HURT = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_HURT, 1.4f, 0.6f),
        SoundCue(0, Sound.BLOCK_BONE_BLOCK_HIT, 1.6f, 0.7f),
    )

    fun play(at: Location, sound: Sound, volume: Float = 2.0f, pitch: Float = 1.0f) {
        at.world.playSound(at, sound, SoundCategory.HOSTILE, volume, pitch)
    }

    private fun play(e: Entity, sound: Sound, volume: Float = 2.0f, pitch: Float = 1.0f) = play(e.location, sound, volume, pitch)

    // ===== 大剣の技（SoundCue の tick は振り始めから） =====

    /** 薙ぎ払い: 引き絞る（骨のきしみ）→ 風を切る → 振り抜く */
    fun slash(hitTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.2f, 0.5f),
        SoundCue(4, Sound.BLOCK_BONE_BLOCK_STEP, 1.6f, 0.6f),
        SoundCue(hitTick - 2, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.6f, 0.6f),
        SoundCue(hitTick, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2.4f, 0.5f),
        SoundCue(hitTick, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.4f, 0.6f),
    )

    /** 溜め斬り: 身をひねる（骨のきしみ）→ 切っ先が地面をこする → 鼓動のように力を溜める → 振り抜く（重い一閃） */
    fun chargeSlash(chargeTick: Int, hitTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.4f, 0.45f),
        SoundCue(chargeTick, Sound.BLOCK_GRINDSTONE_USE, 1.4f, 0.5f),
        SoundCue(chargeTick + 2, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.8f),
        SoundCue((chargeTick + hitTick) / 2, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 1.0f),
        SoundCue(hitTick - 4, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.8f, 0.55f),
        SoundCue(hitTick, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2.6f, 0.45f),
        SoundCue(hitTick, Sound.ENTITY_PLAYER_ATTACK_STRONG, 2.0f, 0.5f),
        SoundCue(hitTick, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.6f, 0.5f),
    )

    /** 突き: 引く → 踏み込みの風 → 刺突 */
    fun thrust(hitTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.BLOCK_BONE_BLOCK_STEP, 1.6f, 0.6f),
        SoundCue(hitTick - 3, Sound.ITEM_TRIDENT_RIPTIDE_2, 1.8f, 0.7f),
        SoundCue(hitTick, Sound.ITEM_TRIDENT_THROW, 2.0f, 0.5f),
        SoundCue(hitTick, Sound.ENTITY_PLAYER_ATTACK_STRONG, 2.0f, 0.5f),
    )

    /** 振り下ろし: 振りかぶる → 落ちる風 → 地面に叩きつける */
    fun slam(impactTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.4f, 0.45f),
        SoundCue(6, Sound.BLOCK_CHAIN_HIT, 1.6f, 0.5f),
        SoundCue(impactTick - 3, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.6f, 0.5f),
        SoundCue(impactTick, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2.4f, 0.6f),
        SoundCue(impactTick, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f),
        SoundCue(impactTick, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.5f),
    )

    /** 跳躍斬り: 屈む → 跳ぶ → 着地 */
    fun leap(jumpTick: Int, landTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.4f, 0.5f),
        SoundCue(jumpTick, Sound.ENTITY_GOAT_LONG_JUMP, 2.0f, 0.5f),
        SoundCue(jumpTick, Sound.ENTITY_PHANTOM_SWOOP, 1.4f, 0.5f),
        SoundCue(landTick - 3, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.6f, 0.5f),
        SoundCue(landTick, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2.6f, 0.55f),
        SoundCue(landTick, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f),
    )

    /** 地割れが進む */
    fun crack(at: Location) {
        play(at, Sound.BLOCK_DEEPSLATE_BREAK, 1.4f, 0.6f)
        play(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 0.5f)
    }

    /** 斬撃波を放つ */
    fun crescentRelease(at: Location) {
        play(at, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.6f)
        play(at, Sound.ENTITY_BREEZE_WIND_BURST, 1.6f, 0.6f)
    }

    // ===== 体 =====

    /** 歩く（骨の足音と、マントを引きずる音） */
    fun step(e: Entity) {
        play(e, Sound.ENTITY_WITHER_SKELETON_STEP, 1.4f, 0.6f)
        play(e, Sound.BLOCK_WOOL_STEP, 0.8f, 0.5f)
    }

    /** 横ステップ（地を蹴る音と、マントのはためき） */
    fun sideStep(e: Entity) {
        play(e, Sound.ENTITY_WITHER_SKELETON_STEP, 1.6f, 0.8f)
        play(e, Sound.ENTITY_PHANTOM_FLAP, 1.2f, 0.7f)
        play(e, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.4f)
    }

    /** 浮き上がる: 低いうなりと、マントがはためく音 */
    fun floatRise(e: Entity) {
        play(e, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.6f, 0.4f)
        play(e, Sound.ENTITY_PHANTOM_FLAP, 1.8f, 0.5f)
        play(e, Sound.BLOCK_SOUL_SAND_BREAK, 1.4f, 0.5f)
    }

    /** 宙に留まっている間（マントがはためく） */
    fun floatHum(e: Entity) {
        play(e, Sound.ENTITY_PHANTOM_FLAP, 1.2f, 0.45f)
    }

    /** 宙から舞い降りる */
    fun floatDive(e: Entity) {
        play(e, Sound.ENTITY_PHANTOM_SWOOP, 1.8f, 0.5f)
        play(e, Sound.ENTITY_VEX_CHARGE, 1.2f, 0.5f)
    }

    /** 降り立つ */
    fun touchDown(e: Entity) {
        play(e, Sound.ENTITY_BREEZE_LAND, 1.6f, 0.5f)
        play(e, Sound.ENTITY_WITHER_SKELETON_STEP, 1.6f, 0.5f)
    }

    /** 体勢を崩される（ジャストガードを重ねられた） */
    fun guardBroken(e: Entity) {
        play(e, Sound.ITEM_SHIELD_BREAK, 2.0f, 0.5f)
        play(e, Sound.BLOCK_BONE_BLOCK_BREAK, 2.0f, 0.6f)
        play(e, Sound.ENTITY_WITHER_SKELETON_HURT, 1.6f, 0.4f)
    }

    /** 登場: 灰が舞う → 目が灯る → 立ち上がる → 大剣を引き抜く */
    fun spawn(): List<SoundCue> = listOf(
        SoundCue(0, Sound.BLOCK_SCULK_SPREAD, 1.6f, 0.5f),
        SoundCue(20, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.4f, 0.6f),
        SoundCue(21, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.8f, 0.4f),
        SoundCue(28, Sound.BLOCK_BONE_BLOCK_PLACE, 1.6f, 0.5f),
        SoundCue(40, Sound.BLOCK_BONE_BLOCK_STEP, 1.6f, 0.5f),
        SoundCue(46, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 2.0f, 0.5f),
        SoundCue(48, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.4f, 0.6f),
    )

    /** 本気: 震えながら力を溜める → changeTick で天を仰いで吠える */
    fun rageShift(changeTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_HURT, 1.6f, 0.4f),
        SoundCue(6, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.7f),
        SoundCue(14, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.7f),
        SoundCue(changeTick - 4, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.4f, 0.8f),
        SoundCue(changeTick, Sound.ENTITY_WITHER_SKELETON_DEATH, 2.4f, 0.4f),
        SoundCue(changeTick, Sound.ENTITY_RAVAGER_ROAR, 2.4f, 0.6f),
        SoundCue(changeTick, Sound.ENTITY_BLAZE_SHOOT, 1.6f, 0.5f),
    )

    /** 挑発: 首をかしげる */
    fun taunt(): List<SoundCue> = listOf(
        SoundCue(6, Sound.BLOCK_BONE_BLOCK_STEP, 1.4f, 0.7f),
        SoundCue(18, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.6f, 0.5f),
        SoundCue(28, Sound.BLOCK_CHAIN_HIT, 1.4f, 0.6f),
    )

    /** 疲れ: 大剣を地面に突き立てる → 肩で荒く息をする → 構え直す */
    fun tired(): List<SoundCue> = listOf(
        SoundCue(5, Sound.BLOCK_BONE_BLOCK_PLACE, 1.8f, 0.5f),
        SoundCue(6, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f),
        SoundCue(14, Sound.ENTITY_HORSE_BREATHE, 1.6f, 0.5f),
        SoundCue(27, Sound.ENTITY_HORSE_BREATHE, 1.6f, 0.45f),
        SoundCue(40, Sound.ENTITY_HORSE_BREATHE, 1.6f, 0.5f),
        SoundCue(53, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.2f, 0.4f),
        SoundCue(58, Sound.BLOCK_BONE_BLOCK_STEP, 1.6f, 0.6f),
    )

    /** よろめき（体勢崩し） */
    fun stagger(): List<SoundCue> = listOf(
        SoundCue(10, Sound.BLOCK_BONE_BLOCK_FALL, 1.6f, 0.5f),
        SoundCue(26, Sound.ENTITY_WITHER_SKELETON_STEP, 1.4f, 0.5f),
    )

    fun death(e: Entity) {
        play(e, Sound.ENTITY_WITHER_SKELETON_DEATH, 2.4f, 0.45f)
        play(e, Sound.ENTITY_SKELETON_DEATH, 1.6f, 0.5f)
    }

    /** 両膝をつく */
    fun kneel(e: Entity) {
        play(e, Sound.BLOCK_BONE_BLOCK_FALL, 2.0f, 0.5f)
        play(e, Sound.ITEM_MACE_SMASH_GROUND, 1.6f, 0.6f)
    }

    /** 灰になって崩れる */
    fun crumble(e: Entity) {
        play(e, Sound.BLOCK_BONE_BLOCK_BREAK, 2.0f, 0.5f)
        play(e, Sound.BLOCK_SCULK_SPREAD, 1.6f, 0.5f)
    }

    // ===== 打ち上げ・空中連撃 =====

    /** 打ち上げ: 沈む → 斬り上げる */
    fun launch(hitTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.4f, 0.5f),
        SoundCue(4, Sound.BLOCK_BONE_BLOCK_STEP, 1.6f, 0.6f),
        SoundCue(hitTick - 2, Sound.ITEM_TRIDENT_RIPTIDE_2, 1.8f, 0.7f),
        SoundCue(hitTick, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2.4f, 0.6f),
    )

    /** 斬り上げが当たった（打ち上がる） */
    fun launchHit(e: Entity) {
        play(e, Sound.ENTITY_BREEZE_WIND_BURST, 2.0f, 0.7f)
        play(e, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 2.0f, 0.6f)
    }

    /** 追って跳ぶ */
    fun leapUp(e: Entity) {
        play(e, Sound.ENTITY_GOAT_LONG_JUMP, 2.0f, 0.5f)
        play(e, Sound.ENTITY_PHANTOM_SWOOP, 1.6f, 0.6f)
    }

    /** 空中の一太刀（n で少しずつ高く） */
    fun airSlash(e: Entity, n: Int) {
        play(e, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.8f, 0.8f + 0.06f * n)
        play(e, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.4f, 0.9f + 0.05f * n)
    }

    /** 叩き落とす */
    fun airSmash(e: Entity) {
        play(e, Sound.ITEM_MACE_SMASH_AIR, 2.4f, 0.6f)
        play(e, Sound.ENTITY_PLAYER_ATTACK_STRONG, 2.0f, 0.5f)
    }

    /** 着地 */
    fun land(e: Entity) {
        play(e, Sound.ITEM_MACE_SMASH_GROUND, 2.0f, 0.6f)
        play(e, Sound.BLOCK_BONE_BLOCK_FALL, 1.6f, 0.5f)
    }

    // ===== 溜め刺し =====

    /** 溜め刺し: 長い溜め（心音が速くなる）→ 突く */
    fun impaleCharge(thrustTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.6f, 0.4f),
        SoundCue(8, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.7f),
        SoundCue(16, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.8f),
        SoundCue(22, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.9f),
        SoundCue(thrustTick - 4, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.4f),
        SoundCue(thrustTick, Sound.ITEM_TRIDENT_RIPTIDE_3, 2.0f, 0.7f),
        SoundCue(thrustTick, Sound.ITEM_TRIDENT_THROW, 2.0f, 0.5f),
    )

    /** 刺さった */
    fun impale(e: Entity) {
        play(e, Sound.ITEM_TRIDENT_HIT, 2.4f, 0.5f)
        play(e, Sound.ENTITY_PLAYER_ATTACK_CRIT, 2.0f, 0.5f)
    }

    /** 刺した相手ごと掲げる */
    fun impaleLift(e: Entity) {
        play(e, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 2.0f, 0.4f)
        play(e, Sound.BLOCK_CHAIN_HIT, 1.6f, 0.5f)
    }

    /** 投げ飛ばす */
    fun throwAway(e: Entity) {
        play(e, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2.4f, 0.5f)
        play(e, Sound.ENTITY_BREEZE_WIND_BURST, 2.0f, 0.6f)
    }

    // ===== 怨霊 =====

    fun wraithCast(e: Entity) {
        play(e, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.6f, 0.6f)
        play(e, Sound.PARTICLE_SOUL_ESCAPE, 2.0f, 0.6f)
    }

    fun wraithRelease(at: Location) {
        play(at, Sound.ENTITY_VEX_CHARGE, 1.4f, 0.5f)
        play(at, Sound.PARTICLE_SOUL_ESCAPE, 1.6f, 0.8f)
    }

    /** 飛んでいる怨霊のうめき */
    fun wraithWail(at: Location) = play(at, Sound.ENTITY_VEX_AMBIENT, 0.8f, 0.4f)

    fun wraithBurst(at: Location) {
        play(at, Sound.ENTITY_VEX_DEATH, 1.4f, 0.5f)
        play(at, Sound.PARTICLE_SOUL_ESCAPE, 1.6f, 1.2f)
    }

    // ===== 雷 =====

    // ===== 瀕死 =====

    /** HP 1 で踏みとどまる: 心臓の音 → 怨霊を吸い込む */
    fun lastStand(): List<SoundCue> = listOf(
        SoundCue(0, Sound.ITEM_TOTEM_USE, 1.4f, 0.5f),
        SoundCue(0, Sound.ENTITY_WITHER_SKELETON_DEATH, 2.0f, 0.5f),
        SoundCue(4, Sound.ENTITY_WARDEN_HEARTBEAT, 3.0f, 0.7f),
        SoundCue(10, Sound.ENTITY_WARDEN_SONIC_CHARGE, 3.0f, 0.8f),
        SoundCue(16, Sound.ENTITY_WARDEN_HEARTBEAT, 3.0f, 0.8f),
    )

    fun lastStandBlast(e: Entity) {
        play(e, Sound.ENTITY_WARDEN_SONIC_BOOM, 4.0f, 0.6f)
        play(e, Sound.ENTITY_WITHER_SPAWN, 2.0f, 1.4f)
    }

    fun lastStandHeartbeat(e: Entity) = play(e, Sound.ENTITY_WARDEN_HEARTBEAT, 3.0f, 1.0f)

    /** 怨霊の束が届く直前に、狙われた人にだけ聞こえる「今だ」 */
    fun volleyCue(p: Player) {
        p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.HOSTILE, 1.2f, 1.8f)
    }

    /** 攻撃が効かなかった */
    fun warded(e: Entity) = play(e, Sound.BLOCK_SOUL_SAND_BREAK, 1.4f, 0.6f)

    fun lightningCast(e: Entity) {
        play(e, Sound.ITEM_TRIDENT_THUNDER, 2.0f, 0.8f)
        play(e, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.6f, 0.4f)
    }

    /** 雷の予告（ぱちぱちと鳴る） */
    fun lightningWarn(at: Location) = play(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.8f)
}
