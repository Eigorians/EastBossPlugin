package com.eastcompany.eastsub.eastBossPlugin.boss.phase

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill

/**
 * ボスのフェーズ。HP の割合が [healthRatio] 以下になると、このフェーズに移る。
 *
 * - フェーズは HP の高い順（healthRatio の大きい順）に並べ、最初は 1.0 にする
 * - 一度進んだフェーズは、回復しても戻らない
 * - 移行時に実行中の技は止めず、次に選ぶ技から新しいフェーズのものになる
 *
 * @property skills このフェーズで使う技（ボスごとにインスタンスを作ること）
 * @property onEnter このフェーズに入ったときの処理（演出など）
 */
class BossPhase(
    val id: String,
    val healthRatio: Double,
    val skills: List<BossSkill>,
    val onEnter: (Boss) -> Unit = {},
)
