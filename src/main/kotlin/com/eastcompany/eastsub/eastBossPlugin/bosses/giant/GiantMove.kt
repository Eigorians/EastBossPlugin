package com.eastcompany.eastsub.eastBossPlugin.bosses.giant

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import org.bukkit.Material

/**
 * 巨人の「動き」1つ分の設定（ボスの yml の moves.<動きID>）。技（[skill.GiantComboSkill]）はこれを並べて連携にする。
 *
 * 1つの動きの流れ（tick は動きの始まりから）:
 *   0 〜 aimLockTick        … 標的の方へ turnSpeed 度/tick でゆっくり振り向く（巨体なので速くは向けない）
 *   aimLockTick             … 狙いを固める（ここから向きも範囲も動かない）
 *   warnStartTick 〜 impactTick … 地面に範囲の予告。範囲の中を色が満たしていき、満ちた瞬間に当たる
 *   impactTick              … 当たる（LINE は手前から奥へ順に地面が割れる）
 *   〜 ticks                … 動きの終わり（次の動きへ、または連携の後の隙へ）
 *
 * 範囲の形（[Shape]）:
 *   CIRCLE … 正面 forward ブロック先を中心にした半径 radius の円（forward 0 なら自分のまわり）
 *   CONE   … 自分を中心にした半径 radius・開き coneDegrees 度の扇（薙ぎ払い）
 *   LINE   … 正面へ長さ length・幅 width の帯。waveSpeed ブロック/tick で手前から奥へ割れていく（0 なら一度に）
 *   THROW  … releaseTick に岩を投げる。着く所（標的の動きを leadRatio だけ見越す）に半径 radius の円。flightTicks で着弾
 *   LEAP   … takeoffTick に跳び、impactTick に標的のいた所（maxDistance まで）へ着地。着地点に半径 radius の円
 *
 * どの形も、上へ hitHeight ブロックまで届く柱（ブロックを積んで登った相手にも当たる）。
 */
data class GiantMove(
    val id: String,
    val animation: String,
    val ticks: Int,
    val shape: Shape,
    val impactTick: Int,
    val warnStartTick: Int,
    val aimLockTick: Int,
    val turnSpeed: Double,
    val radius: Double,
    val forward: Double,
    val coneDegrees: Double,
    val length: Double,
    val width: Double,
    val waveSpeed: Double,
    /** 足元から上へ届く高さ（THROW は着弾点から） */
    val hitHeight: Double,
    val damage: Double,
    val knockback: Double,
    val launch: Double,
    /** 当たった瞬間、この半径のプレイヤーの足元が揺れる（0 で揺らさない） */
    val shakeRadius: Double,
    // ---- THROW ----
    val grabTick: Int,
    val releaseTick: Int,
    val flightTicks: Int,
    val arcHeight: Double,
    val leadRatio: Double,
    val block: Material,
    // ---- LEAP ----
    val takeoffTick: Int,
    val leapHeight: Double,
    val maxDistance: Double,
    /** アニメを流す速さ（本気でワンテンポ速くしたとき 1 より大きい） */
    val animationSpeed: Float = 1f,
) {
    enum class Shape { CIRCLE, CONE, LINE, THROW, LEAP }

    /** 実際に当たる tick（THROW は投げてから飛ぶ時間の後） */
    val hitTick: Int get() = if (shape == Shape.THROW) releaseTick + flightTicks else impactTick

    /** 狙いを固める tick（THROW は投げる瞬間、LEAP は跳ぶ瞬間） */
    val lockTick: Int
        get() = when (shape) {
            Shape.THROW -> releaseTick
            Shape.LEAP -> takeoffTick
            else -> aimLockTick
        }

    /**
     * 本気用の写し: 範囲を size 倍（体が大きくなったぶん）、時間を tempo 倍（0.8 なら8割＝速い）にする。
     * 振り向く速さとアニメの速さは 1/tempo 倍
     */
    fun scaled(size: Double, tempo: Double): GiantMove {
        fun t(v: Int) = (v * tempo).let { Math.round(it).toInt() }
        return copy(
            ticks = t(ticks).coerceAtLeast(1),
            impactTick = t(impactTick),
            warnStartTick = t(warnStartTick),
            aimLockTick = t(aimLockTick),
            turnSpeed = turnSpeed / tempo,
            radius = radius * size,
            forward = forward * size,
            length = length * size,
            width = width * size,
            hitHeight = hitHeight * size,
            shakeRadius = shakeRadius * size,
            grabTick = t(grabTick),
            releaseTick = t(releaseTick),
            flightTicks = t(flightTicks).coerceAtLeast(1),
            takeoffTick = t(takeoffTick),
            leapHeight = leapHeight * size,
            maxDistance = maxDistance * size,
            animationSpeed = (animationSpeed / tempo).toFloat(),
        )
    }

    companion object {
        fun load(id: String, section: ConfigSection): GiantMove {
            val impact = section.int(Names.SkillConfig.IMPACT_TICK, 20)
            return GiantMove(
                id = id,
                animation = section.string(Names.SkillConfig.ANIMATION, id),
                ticks = section.int(Names.SkillConfig.END_TICKS, impact + 10).coerceAtLeast(1),
                shape = section.enum(Names.SkillConfig.SHAPE, Shape.CIRCLE),
                impactTick = impact,
                warnStartTick = section.int(Names.SkillConfig.WARN_START_TICK, 0),
                aimLockTick = section.int(Names.SkillConfig.AIM_LOCK_TICK, (impact - 8).coerceAtLeast(0)),
                turnSpeed = section.double(Names.SkillConfig.TURN_SPEED, 3.0),
                radius = section.double(Names.SkillConfig.RADIUS, 4.0),
                forward = section.double(Names.SkillConfig.FORWARD, 0.0),
                coneDegrees = section.double(Names.SkillConfig.CONE_DEGREES, 150.0),
                length = section.double(Names.SkillConfig.LENGTH, 16.0),
                width = section.double(Names.SkillConfig.WIDTH, 3.0),
                waveSpeed = section.double(Names.SkillConfig.WAVE_SPEED, 0.0),
                hitHeight = section.double(Names.SkillConfig.HIT_HEIGHT, 12.0),
                damage = section.double(Names.SkillConfig.DAMAGE, 10.0),
                knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.8),
                launch = section.double(Names.SkillConfig.LAUNCH, 0.5),
                shakeRadius = section.double(Names.SkillConfig.SHAKE_RADIUS, 0.0),
                grabTick = section.int(Names.SkillConfig.GRAB_TICK, 9),
                releaseTick = section.int(Names.SkillConfig.RELEASE_TICKS, 32),
                flightTicks = section.int(Names.SkillConfig.FLIGHT_TICKS, 18).coerceAtLeast(1),
                arcHeight = section.double(Names.SkillConfig.ARC_HEIGHT, 8.0),
                leadRatio = section.double(Names.SkillConfig.LEAD_RATIO, 0.5),
                block = Material.matchMaterial(section.string(Names.SkillConfig.BLOCK, "MOSSY_COBBLESTONE"))
                    ?.takeIf { it.isBlock } ?: Material.MOSSY_COBBLESTONE,
                takeoffTick = section.int(Names.SkillConfig.TAKEOFF_TICK, 20),
                leapHeight = section.double(Names.SkillConfig.JUMP_HEIGHT, 9.0),
                maxDistance = section.double(Names.SkillConfig.MAX_DISTANCE, 18.0),
            )
        }
    }
}
