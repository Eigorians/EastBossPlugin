package com.eastcompany.eastsub.eastBossPlugin.boss

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigFile
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component

/**
 * すべてのボスに共通する設定（bosses/<ボスID>.yml のトップレベル）。
 * 召喚するたびに読み込むので、/eb reload の後に召喚したボスから反映される。
 */
data class BossSettings(
    /** 表示名（ボスバー・頭上の名前） */
    val displayName: Component,
    /** BetterModel のモデル名 */
    val model: String,
    /** モデルの見た目の倍率（技の当たり判定のボーン位置もこれに合わせて大きくなる） */
    val modelScale: Double,
    /** エンティティ本体（殴れる当たり判定）の倍率。Attribute.SCALE に入れる */
    val entityScale: Double,
    val maxHealth: Double,
    /** 旅商人からのボス戦で、参加者が1人増えるごとに増やす HP の割合（0.2 なら 1人 1倍・2人 1.2倍・3人 1.4倍） */
    val healthPerExtraPlayer: Double,
    /**
     * ボスが与えるダメージ全体の倍率（技の damage に掛かる。防具で減る前の値）。
     * 技の数値はそのままに、想定する装備の強さに合わせて難しさをまとめて調整する
     */
    val damageMultiplier: Double,
    /** 召喚から消えるまでの tick。0 以下で無制限 */
    val lifetimeTicks: Int,
    /** 技が終わってから次の技を出すまで、何もしない時間（tick）。決められた技（queueSkill）は待たない */
    val skillGapTicks: Int,

    /** 技の標的を探す距離 */
    val searchRange: Double,
    /** ボスよりこれ以上高い所にいる相手は狙わない（ブロック） */
    val targetMaxHeight: Double,

    val barColor: BossBar.Color,
    val barOverlay: BossBar.Overlay,
    /** ボスバーが見える距離 */
    val barRange: Double,

    /** 死亡アニメーション名（空なら再生しない） */
    val deathAnimation: String,
    /** 死亡してから消えるまでの tick */
    val deathTicks: Int,

    /** 飛び道具をすり抜ける */
    val immuneToProjectile: Boolean,
    /** スプラッシュポーションの効果を受けない */
    val immuneToPotion: Boolean,
    /** 縦方向のノックバックを受けない（横は受ける） */
    val immuneToVerticalKnockback: Boolean,
    /** 飛び道具で受けるダメージの倍率。null なら config.yml の boss.projectile-damage-multiplier（全ボス共通の値） */
    val projectileDamageMultiplier: Double?,
    /** 盾で防いだダメージがこの量たまると盾が壊れる（damage-multiplier を掛けた後の値。0 以下で壊れない） */
    val shieldBreakDamage: Double,
    /** 盾が壊れた後、使えない時間（tick） */
    val shieldDisableTicks: Int,
    /** 技ごとに、盾で防いだときにたまる量の倍率（遠距離の技は盾で受けやすくするなど） */
    val shieldSkillMultipliers: Map<String, Double>,
) {
    /** この技（null なら技の外の攻撃）を盾で防いだときにたまる量の倍率 */
    fun shieldMultiplier(skillId: String?): Double = skillId?.let { shieldSkillMultipliers[it] } ?: 1.0

    /**
     * 参加人数に合わせた最大 HP。
     * 1人なら max-health のまま、1人増えるごとに max-health × health-per-player ずつ増える
     */
    fun scaledMaxHealth(players: Int): Double =
        maxHealth * (1.0 + healthPerExtraPlayer * (players - 1).coerceAtLeast(0))

    companion object {
        fun load(file: ConfigFile): BossSettings = BossSettings(
            displayName = file.component(Names.BossConfig.NAME, def = "<red>Boss"),
            model = file.string(Names.BossConfig.MODEL),
            modelScale = file.double(Names.BossConfig.MODEL_SCALE, 1.0).coerceAtLeast(0.01),
            entityScale = file.double(Names.BossConfig.ENTITY_SCALE, 1.0).coerceIn(0.0625, 16.0),
            maxHealth = file.double(Names.BossConfig.MAX_HEALTH, 100.0).coerceAtLeast(1.0),
            healthPerExtraPlayer = file.double(Names.BossConfig.HEALTH_PER_PLAYER, 0.2).coerceAtLeast(0.0),
            damageMultiplier = file.double(Names.BossConfig.DAMAGE_MULTIPLIER, 1.0).coerceAtLeast(0.0),
            lifetimeTicks = file.int(Names.BossConfig.LIFETIME_TICKS, 0),
            skillGapTicks = file.int(Names.BossConfig.SKILL_GAP_TICKS, 0).coerceAtLeast(0),
            searchRange = file.double(Names.BossConfig.TARGET_SEARCH_RANGE, 12.0),
            targetMaxHeight = file.double(Names.BossConfig.TARGET_MAX_HEIGHT, 5.0),
            barColor = file.enum(Names.BossConfig.BOSSBAR_COLOR, BossBar.Color.RED),
            barOverlay = file.enum(Names.BossConfig.BOSSBAR_OVERLAY, BossBar.Overlay.NOTCHED_10),
            barRange = file.double(Names.BossConfig.BOSSBAR_RANGE, 48.0),
            deathAnimation = file.string(Names.BossConfig.DEATH_ANIMATION),
            deathTicks = file.int(Names.BossConfig.DEATH_TICKS, 0).coerceAtLeast(0),
            immuneToProjectile = file.boolean(Names.BossConfig.IMMUNE_PROJECTILE, false),
            immuneToPotion = file.boolean(Names.BossConfig.IMMUNE_POTION, false),
            immuneToVerticalKnockback = file.boolean(Names.BossConfig.IMMUNE_VERTICAL_KNOCKBACK, false),
            projectileDamageMultiplier = if (file.contains(Names.BossConfig.PROJECTILE_DAMAGE_MULTIPLIER)) {
                file.double(Names.BossConfig.PROJECTILE_DAMAGE_MULTIPLIER, 0.5).coerceAtLeast(0.0)
            } else null,
            shieldBreakDamage = file.double(Names.BossConfig.SHIELD_BREAK_DAMAGE, 80.0),
            shieldDisableTicks = file.int(Names.BossConfig.SHIELD_DISABLE_TICKS, 100).coerceAtLeast(1),
            shieldSkillMultipliers = file.keys(Names.BossConfig.SHIELD_SKILL_MULTIPLIERS).associateWith {
                file.double("${Names.BossConfig.SHIELD_SKILL_MULTIPLIERS}.$it", 1.0).coerceAtLeast(0.0)
            },
        )
    }
}
