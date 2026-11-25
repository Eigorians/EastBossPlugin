package com.eastcompany.eastsub.eastBossPlugin.boss

import java.util.UUID
import kotlin.math.abs

/**
 * プレイヤーがボスに付けた火（付けた人 → 燃え終わる tick）。燃焼ダメージを外的要因と見分け、付けた人の分に数える。
 *
 * サーバーの killer は最後の攻撃から 5秒で消えるので、それより長く燃える火（火属性II は 8秒）には使えない。
 * 「最後に引火した人」も使えない（短い火は残りを延ばさず、フレイムの矢はダメージが入らなければ火が元に戻る）。
 * そこで、今燃えている火の終わる tick に一番近い火を付けた人にする。
 */
internal class PlayerBurns {

    private val ends = LinkedHashMap<UUID, Int>()

    /** [player] が [now] から [ticks] の間燃える火を付けた */
    fun ignite(player: UUID, now: Int, ticks: Int) {
        ends.values.removeIf { it < now }
        ends[player] = maxOf(ends[player] ?: 0, now + ticks)
    }

    /** プレイヤーの付けた火が残っているか */
    fun isBurning(now: Int): Boolean = ends.values.any { it >= now }

    /** 残り [fireTicks] で燃えている今の火を付けた人。プレイヤーの火でなければ null */
    fun igniter(now: Int, fireTicks: Int): UUID? =
        ends.entries.filter { it.value >= now }.minByOrNull { abs(it.value - (now + fireTicks)) }?.key
}
