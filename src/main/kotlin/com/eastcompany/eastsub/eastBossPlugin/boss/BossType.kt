package com.eastcompany.eastsub.eastBossPlugin.boss

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.battle.BossBattle
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigFile
import kr.toxicity.model.api.tracker.EntityTracker
import org.bukkit.entity.Husk
import org.bukkit.entity.Mob
import org.bukkit.inventory.ItemStack

/**
 * ボスの種類。1種類につき1つ作り、[BossRegistry] に登録する。
 * 召喚は [BossManager.spawn] が行い、最後に [create] で Boss 本体を作る。
 */
interface BossType {

    /** コマンドや設定ファイル名に使う ID（小文字・アンダースコア推奨） */
    val id: String

    /**
     * ボス選択メニューなどに出すアイコン（必須）。
     * 呼ぶたびに新しい ItemStack を返すこと（呼び出し側が名前などを書き換えるため）。
     * Material だけで良い場合は `ItemStack.of(Material.XXX)` を返す。
     */
    val icon: ItemStack

    /** 設定ファイル（データフォルダからの相対パス）。jar 同梱のものがデフォルト値になる */
    val configPath: String get() = Names.File.boss(id)

    /** ボスの本体（当たり判定・AI）になるエンティティ。見た目は BetterModel のモデルが上書きする */
    val entityClass: Class<out Mob> get() = Husk::class.java

    /** 召喚の準備が済んだ材料から Boss を作る */
    fun create(spawn: BossSpawn): Boss
}

/** Boss を作るときに渡す材料 */
class BossSpawn(
    val type: BossType,
    val manager: BossManager,
    /** このボスの設定ファイル */
    val config: ConfigFile,
    val settings: BossSettings,
    val entity: Mob,
    val tracker: EntityTracker,
    /** 旅商人から召喚したときの戦闘（参加者など）。コマンドで召喚したときは null */
    val battle: BossBattle?,
)
