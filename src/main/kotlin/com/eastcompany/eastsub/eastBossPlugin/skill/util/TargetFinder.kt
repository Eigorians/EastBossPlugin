package com.eastcompany.eastsub.eastBossPlugin.skill.util

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import org.bukkit.GameMode
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType

/** 技の標的・ダメージの対象を選ぶ */
object TargetFinder {

    /**
     * ダメージを与えてよい相手か（すべての技の当たり判定・ノックバック・状態異常に使う）。
     * モブ、またはサバイバルのプレイヤー（村人・行商人・ボスとその手下は除く）。
     * 旅商人から召喚したボス（battle あり）は、参加者以外には一切当てない。
     * 死んでいる人・別のワールドにいる人・リスポーンして間もない人（config.yml の status.respawn-protection-ticks）も除く
     */
    fun isValidTarget(boss: Boss, e: Entity): Boolean {
        if (e !is LivingEntity || e is ArmorStand || e.isDead) return false
        if (e is AbstractVillager) return false
        if (isInvincible(e)) return false
        if (e === boss.entity || boss.manager.isBossSide(e)) return false
        val battle = boss.battle
        if (battle != null && !battle.isParticipant(e)) return false
        if (e is Player) {
            // リスポーンして間もない人は狙わない・当てない（死ぬ前の標的を、遠いリスポーン地点まで追いかけないように）
            if (e.world != boss.entity.world || boss.status.isRespawnProtected(e)) return false
            return e.gameMode == GameMode.SURVIVAL
        }
        return e is Mob
    }

    /**
     * 標的として狙えるか（技を出す相手を選ぶときに使う）。
     *   ・乗り物に乗っている相手は狙わない
     *   ・ボスより Boss.targetMaxHeight ブロック以上高い所にいる相手は狙わない
     *   ・旅商人から召喚したボス（battle あり）は、参加者だけを狙う（isValidTarget で除かれる）
     *
     * ダメージ判定には isValidTarget を使うので、
     * 狙われていない相手（乗り物の上・高い所）でも、技に当たればダメージは受ける。
     */
    fun isTargetable(boss: Boss, e: Entity): Boolean {
        if (!isValidTarget(boss, e)) return false
        if (e.isInsideVehicle) return false
        if (e.location.y - boss.entity.location.y >= boss.targetMaxHeight) return false
        return true
    }

    /**
     * range 以内で一番近い標的
     * @param verticalRange 上下に探す距離
     * @param accept これを満たす相手からだけ選ぶ
     */
    fun findNearest(
        boss: Boss,
        range: Double,
        verticalRange: Double = range,
        accept: (Entity) -> Boolean = { true },
    ): LivingEntity? {
        var best: LivingEntity? = null
        var bestDist = Double.MAX_VALUE
        for (e in boss.entity.getNearbyEntities(range, verticalRange, range)) {
            if (!isTargetable(boss, e) || !accept(e)) continue
            val d = e.location.distanceSquared(boss.entity.location)
            if (d < bestDist) {
                bestDist = d
                best = e as LivingEntity
            }
        }
        return best
    }

    /**
     * 無敵かどうか。
     *   ・無敵フラグ（/data の Invulnerable など）が付いている
     *   ・耐性V以上（ダメージを完全に防ぐ）が付いている
     */
    private fun isInvincible(e: LivingEntity): Boolean {
        if (e.isInvulnerable) return true
        val resistance = e.getPotionEffect(PotionEffectType.RESISTANCE) ?: return false
        return resistance.amplifier >= 4
    }
}
