package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner

import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.BackflipSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.GatlingSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.GrenadeSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.SnipeSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.StrafeFireSkill
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill.TwinBurstSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.BoneStrikeSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.common.PerformSkill
import org.bukkit.Sound
import org.bukkit.entity.Entity

/**
 * ガンナーの効果音（無口な賞金稼ぎ）。
 * 声 … ピリジャー（低めのピッチ）
 * 銃声 … 花火の破裂＋鉄の扉を叩く音＋小さな爆発を重ねて「パンッ」という乾いた音にする
 */
object GunnerSounds {

    val HURT = listOf(SoundCue(0, Sound.ENTITY_PILLAGER_HURT, 1.0f, 0.75f))

    val VANISH = listOf(
        SoundCue(0, Sound.ENTITY_PILLAGER_CELEBRATE, 1.5f, 0.7f),
        SoundCue(0, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.8f),
    )

    /**
     * 拳銃の1発（弾が残っているときだけ鳴らすので、技の効果音には入れず [GunnerBoss.shoot] で鳴らす）
     */
    fun playPistolShot(at: Entity, pitch: Float = 1.0f) = pistolShot(0, pitch).forEach { it.play(at) }

    /** 弾切れの空撃ち「カチッ」 */
    fun playDryFire(at: Entity) = DRY_FIRE.forEach { it.play(at) }

    private val DRY_FIRE = listOf(
        SoundCue(0, Sound.BLOCK_DISPENSER_FAIL, 1.2f, 1.9f),
        SoundCue(0, Sound.BLOCK_LEVER_CLICK, 1.0f, 2.0f),
    )

    /** 拳銃の1発 */
    private fun pistolShot(t: Int, pitch: Float = 1.0f) = listOf(
        SoundCue(t, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f, 1.6f * pitch),
        SoundCue(t, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.45f, 1.9f * pitch),
        SoundCue(t, Sound.ENTITY_GENERIC_EXPLODE, 0.3f, 2.0f),
    )

    /** 撃鉄を起こす「カチッ」 */
    private fun cock(t: Int) = listOf(
        SoundCue(t, Sound.BLOCK_LEVER_CLICK, 1.2f, 1.8f),
        SoundCue(t, Sound.UI_BUTTON_CLICK, 0.6f, 2.0f),
    )

    /** 登場: 銃をくるくる回して、帽子のつばに触れる */
    fun spawnPose(s: PerformSkill.Settings): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_PILLAGER_AMBIENT, 1.5f, 0.7f),
        SoundCue(4, Sound.ITEM_CROSSBOW_LOADING_START, 1.0f, 1.6f),
        SoundCue(12, Sound.ITEM_CROSSBOW_LOADING_START, 1.0f, 1.8f),
    ) + cock(20) + cock(22) + listOf(
        SoundCue(33, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.2f, 0.9f),
    )

    /** 二丁拳銃: 構えて撃鉄を起こす → 撃つ（左右で少し音を変える） */
    fun twinBurst(s: TwinBurstSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(SoundCue(0, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 1.3f))
        cues += cock(3)
        return cues
    }

    /** 横走り撃ち（銃声は撃つたびに鳴らす）: 走り出しのコートの音 */
    fun strafeFire(s: StrafeFireSkill.Settings): List<SoundCue> = listOf(
        SoundCue(0, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 1.2f),
    ) + cock((s.shotTicks.firstOrNull() ?: 6) - 4)

    /** 狙撃: 抜く → スコープを下ろす → 狙いを固定する電子音 → 轟音 → ボルトを引く */
    fun snipe(s: SnipeSkill.Settings): List<SoundCue> {
        val tl = s.timeline
        val cues = mutableListOf(
            SoundCue(0, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.8f),
            SoundCue(s.swapTick, Sound.BLOCK_CHAIN_PLACE, 1.2f, 0.8f),
            SoundCue(s.drawTicks - 2, Sound.ITEM_SPYGLASS_USE, 1.5f, 0.8f),
        )
        for (shot in tl.shots) {
            cues += SoundCue(shot.aimStart, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 2.0f)
            cues += SoundCue(shot.lockStart, Sound.BLOCK_NOTE_BLOCK_BIT, 2.0f, 2.0f)
            cues += SoundCue(shot.lockStart, Sound.BLOCK_LEVER_CLICK, 1.5f, 1.2f)
            cues += SoundCue((shot.lockStart + shot.fireTick) / 2, Sound.BLOCK_NOTE_BLOCK_BIT, 2.0f, 2.0f)
            cues += SoundCue(shot.fireTick, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 1.5f)
            cues += SoundCue(shot.fireTick, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 3.0f, 0.7f)
            cues += SoundCue(shot.fireTick, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 0.6f)
            cues += SoundCue(shot.fireTick + 7, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.2f, 1.6f)
            cues += SoundCue(shot.fireTick + 10, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.2f, 1.6f)
            cues += SoundCue(shot.fireTick + 11, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.6f)
        }
        cues += SoundCue(tl.stowStart, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.8f)
        cues += SoundCue(tl.stowStart + s.swapTick, Sound.BLOCK_CHAIN_PLACE, 1.2f, 0.9f)
        return cues
    }

    /** 手榴弾: 弾帯から外す → ピンを抜く → 投げる → 追い打ちの銃声（爆発音は手榴弾の位置で鳴らす） */
    fun grenade(s: GrenadeSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(
            SoundCue(s.grabTick, Sound.BLOCK_CHAIN_HIT, 1.2f, 1.4f),
            SoundCue(s.grabTick + 4, Sound.BLOCK_LEVER_CLICK, 1.5f, 2.0f),
            SoundCue(s.grabTick + 5, Sound.ITEM_FLINTANDSTEEL_USE, 1.2f, 1.6f),
            SoundCue(s.throwTick, Sound.ENTITY_SNOWBALL_THROW, 1.5f, 0.6f),
            SoundCue(s.throwTick, Sound.ENTITY_PILLAGER_AMBIENT, 1.2f, 0.7f),
        )
        s.shootTicks.forEach { cues += cock(it - 4) }
        return cues
    }

    /** 蹴り: 溜め → 鈍い衝撃 */
    fun kick(s: BoneStrikeSkill.Settings): List<SoundCue> {
        val hit = s.hitWindows.firstOrNull()?.first ?: 8
        return listOf(
            SoundCue(0, Sound.ENTITY_PILLAGER_AMBIENT, 1.2f, 0.65f),
            SoundCue(hit - 1, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.6f),
            SoundCue(hit, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.8f, 0.7f),
            SoundCue(hit, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 1.2f),
        )
    }

    /** バク宙: 踏み切りの風 → 空中で二丁撃ち → 着地 */
    fun backflip(s: BackflipSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(
            SoundCue(s.moveStartTick, Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 1.3f),
            SoundCue(s.moveStartTick, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.6f),
            SoundCue(s.moveEndTick, Sound.ENTITY_PLAYER_SMALL_FALL, 1.5f, 0.7f),
        )
        return cues
    }

    /**
     * ガトリング: 取り出して担ぐ → 銃身が回り始める（だんだん高く）→ 連射 → 焼き付いてジュッ → 冷却 → しまう
     */
    fun gatling(s: GatlingSkill.Settings): List<SoundCue> {
        val cues = mutableListOf(
            SoundCue(0, Sound.ENTITY_PILLAGER_CELEBRATE, 1.8f, 0.6f),
            SoundCue(s.swapTick, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.5f, 0.7f),
            SoundCue(s.swapTick, Sound.BLOCK_SMITHING_TABLE_USE, 1.0f, 0.8f),
            SoundCue(s.swapTick + 8, Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.8f),
        )
        // 回り始め
        var pitch = 0.6f
        for (t in (s.fireStart - 10) until s.fireStart step 2) {
            cues += SoundCue(t, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 1.2f, pitch)
            pitch += 0.25f
        }
        // 連射
        for (t in s.fireStart until s.fireEnd step s.shootIntervalTicks) {
            cues += SoundCue(t, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.6f, 1.9f)
            if ((t - s.fireStart) % (s.shootIntervalTicks * 2) == 0) {
                cues += SoundCue(t, Sound.ENTITY_GENERIC_EXPLODE, 0.25f, 2.0f)
            }
        }
        for (t in s.fireStart until s.fireEnd step 8) {
            cues += SoundCue(t, Sound.ENTITY_MINECART_RIDING, 1.2f, 1.8f)
        }
        // 焼き付き → 冷却
        cues += SoundCue(s.fireEnd, Sound.BLOCK_FIRE_EXTINGUISH, 2.0f, 0.8f)
        cues += SoundCue(s.fireEnd, Sound.BLOCK_LAVA_EXTINGUISH, 1.5f, 1.0f)
        cues += SoundCue(s.fireEnd + 6, Sound.ENTITY_PILLAGER_HURT, 1.2f, 0.6f)
        for (t in s.cooldownStart until s.endStart step 20) {
            cues += SoundCue(t, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1.4f)
            cues += SoundCue(t + 10, Sound.ENTITY_HORSE_BREATHE, 1.0f, 0.7f)
        }
        // しまう
        cues += SoundCue(s.endStart, Sound.ENTITY_PILLAGER_AMBIENT, 1.2f, 0.7f)
        cues += SoundCue(s.endStart + s.endSwapTick, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.2f, 0.9f)
        cues += cock(s.endStart + s.endSwapTick + 4)
        return cues
    }

    /** 本気モード: うつむいて弾を込め直す（カチカチ）→ 顔を上げて赤く光る → 銃を回す */
    fun phaseShift(s: PerformSkill.Settings, changeTick: Int): List<SoundCue> {
        val cues = mutableListOf(SoundCue(0, Sound.ENTITY_PILLAGER_AMBIENT, 1.5f, 0.55f))
        for (t in 7..21 step 2) cues += SoundCue(t, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.6f + (t % 4) * 0.05f)
        cues += SoundCue(22, Sound.BLOCK_IRON_DOOR_CLOSE, 1.0f, 1.6f)
        cues += SoundCue(changeTick, Sound.BLOCK_BEACON_POWER_SELECT, 2.0f, 0.6f)
        cues += SoundCue(changeTick, Sound.ENTITY_PILLAGER_CELEBRATE, 2.0f, 0.5f)
        cues += SoundCue(changeTick, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5f, 0.6f)
        cues += SoundCue(37, Sound.ITEM_CROSSBOW_LOADING_START, 1.0f, 1.7f)
        cues += cock(46)
        return cues
    }

    /**
     * リロード: 弾倉を振り出す → 空薬莢がチャラチャラ落ちる → 弾を込める（カチカチ）→ 手首を振って弾倉を戻す → 回す
     */
    fun reload(s: PerformSkill.Settings, ejectTick: Int, insertTick: Int, closeTick: Int): List<SoundCue> {
        val cues = mutableListOf(
            SoundCue(0, Sound.ENTITY_PILLAGER_AMBIENT, 1.0f, 0.8f),
            SoundCue(ejectTick - 2, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.9f),
            SoundCue(ejectTick, Sound.BLOCK_CHAIN_FALL, 1.2f, 1.6f),
        )
        // 空薬莢が地面で跳ねる音（少しずつずらす）
        for ((i, dt) in listOf(4, 5, 7, 8, 10).withIndex()) {
            cues += SoundCue(ejectTick + dt, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.6f + i * 0.08f)
        }
        cues += SoundCue(insertTick - 3, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 1.4f)
        for (t in insertTick until closeTick step 1) cues += SoundCue(t, Sound.BLOCK_LEVER_CLICK, 0.8f, 1.7f + (t % 3) * 0.1f)
        cues += SoundCue(closeTick, Sound.BLOCK_IRON_DOOR_CLOSE, 1.0f, 1.8f)
        cues += SoundCue(closeTick + 1, Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 1.4f)
        cues += SoundCue(closeTick + 6, Sound.ITEM_CROSSBOW_LOADING_START, 0.8f, 1.8f)
        cues += cock(s.endTicks - 4)
        return cues
    }

    /** 挑発: 銃口の煙をふっと吹く → 銃を回す → 帽子のつばに触れる。低く笑う */
    fun taunt(s: PerformSkill.Settings, smokeTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_PILLAGER_CELEBRATE, 1.4f, 0.55f),
        SoundCue(smokeTick, Sound.ENTITY_BREEZE_IDLE_GROUND, 0.6f, 1.6f),
        SoundCue(smokeTick, Sound.BLOCK_FIRE_EXTINGUISH, 0.4f, 2.0f),
        SoundCue(smokeTick + 8, Sound.ITEM_CROSSBOW_LOADING_START, 0.9f, 1.8f),
        SoundCue(s.endTicks - 8, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.9f),
    )

    /**
     * 決闘の始まり: 低くつぶやく → 親指でコインを弾く（キンッ）。鐘とコインの落ちる音は [DuelSkill] が鳴らす
     */
    fun duelReady(coinTick: Int): List<SoundCue> = listOf(
        SoundCue(0, Sound.ENTITY_PILLAGER_AMBIENT, 2.0f, 0.5f),
        SoundCue(0, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.8f),
        SoundCue(coinTick, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2.0f, 2.0f),
        SoundCue(coinTick, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.5f, 2.0f),
    )

    /** 決闘の鐘（i 回目。だんだん低く、最後は重く） */
    fun duelBell(i: Int, last: Boolean): List<SoundCue> = buildList {
        add(SoundCue(0, Sound.BLOCK_BELL_USE, 4.0f, 0.9f - i * 0.1f))
        add(SoundCue(0, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.9f))
        if (last) add(SoundCue(0, Sound.BLOCK_BELL_RESONATE, 3.0f, 0.8f))
    }

    /** 早撃ちの1発（狙撃並みに重い銃声） */
    val DUEL_SHOT = listOf(
        SoundCue(0, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 3.0f, 0.9f),
        SoundCue(0, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.7f),
        SoundCue(0, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 1.2f),
    )

    /** 決闘で撃たれる直前の合図（撃たれる人にだけ聞こえる。撃鉄を起こす音と、澄んだ高い音） */
    val DUEL_CUE = listOf(
        SoundCue(0, Sound.ITEM_CROSSBOW_LOADING_END, 2.0f, 1.6f),
        SoundCue(0, Sound.BLOCK_NOTE_BLOCK_PLING, 2.0f, 2.0f),
        SoundCue(0, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2.0f, 2.0f),
    )
}
