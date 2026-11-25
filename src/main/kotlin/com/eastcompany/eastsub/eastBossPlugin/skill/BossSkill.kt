package com.eastcompany.eastsub.eastBossPlugin.skill

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * ボスの技（行動）。ボスごとにインスタンスを作るので、状態を持ってよい。
 *
 * 流れ: canStart が true の技から [SkillSelector] が1つ選ぶ → start → 毎tick tick → 終わったら stop
 * stop は途中で止められたとき（ボスの死亡・削除）にも呼ばれる。
 */
interface BossSkill {

    /** 技の ID（設定ファイルの skills.<id> やデバッグ表示に使う） */
    val id: String

    /** 選ばれやすさ（大きいほど選ばれやすい。0 以下は選ばれない） */
    val weight: Int get() = 1

    /** 技の開始から何 tick 目に何の音を鳴らすか */
    val sounds: List<SoundCue> get() = emptyList()

    /**
     * 効果音（[sounds]）のタイミングに使う経過 tick。null なら技の開始からの経過 tick。
     * 長さの決まらない準備（標的への接近など）の後に本番が始まる技は、本番からの tick を返す（準備中は -1）
     */
    val soundTick: Int? get() = null

    /** 今この技を始められるか（射程内に標的がいるか等） */
    fun canStart(boss: Boss): Boolean

    fun start(boss: Boss)

    /** 毎tick呼ばれる。終わったら true */
    fun tick(boss: Boss): Boolean

    /** 終了時・強制終了時の後始末 */
    fun stop(boss: Boss) {}

    /** この技の最中にボスが受けるダメージの補正（event.damage を書き換える） */
    fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {}
}
