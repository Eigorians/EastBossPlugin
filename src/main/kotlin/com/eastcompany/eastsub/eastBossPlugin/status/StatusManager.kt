package com.eastcompany.eastsub.eastBossPlugin.status

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import org.bukkit.Server
import org.bukkit.entity.Player

/**
 * ボスがプレイヤーに与える状態異常をまとめたもの。
 * どのボスからも [stun] / [healLock] / [shieldBreak] / [justGuard] / [fall] を通して使う。
 */
class StatusManager(server: Server, private val configManager: ConfigManager) {

    val stun = StunManager(server) { configManager.main.int(Names.Config.STATUS_STUN_SLOW_AMPLIFIER, 3) }

    val healLock = HealLockManager(server)

    val shieldBreak = ShieldBreakManager(server)

    /** リスポーン直後の保護 */
    val respawn = RespawnGuard(server)

    /** リスポーンしてから、ボスに狙われず攻撃も当たらない時間（config.yml の status.respawn-protection-ticks） */
    val respawnProtectionTicks: Int get() = configManager.main.int(Names.Config.STATUS_RESPAWN_PROTECTION_TICKS, 60).coerceAtLeast(0)

    /** リスポーンして間もない（ボスに狙われない・攻撃が当たらない） */
    fun isRespawnProtected(player: Player): Boolean = respawn.isProtected(player, respawnProtectionTicks)

    /** 落下ダメージの保護（打ち上げ・投げ飛ばしの後） */
    val fall = FallGuard(server)

    /** ジャストガードの受付 */
    val justGuard = JustGuardManager(server)

    /** 盾を構えてから、構え直しても受付が開かない時間（config.yml の just-guard.lockout-ticks） */
    val justGuardLockoutTicks: Int get() = configManager.main.int(Names.Config.JUST_GUARD_LOCKOUT_TICKS, 16).coerceAtLeast(0)

    /** ジャストガードの受付の長さ（config.yml の just-guard.window-ticks） */
    val justGuardWindowTicks: Int get() = configManager.main.int(Names.Config.JUST_GUARD_WINDOW_TICKS, 6).coerceAtLeast(0)

    /** 毎tick呼ぶ */
    fun tick() {
        stun.tick()
        healLock.tick()
        shieldBreak.tick()
        justGuard.tick()
        respawn.tick()
        fall.tick()
    }

    /** 1人の状態異常をすべて解く（ボス戦の開始時など） */
    fun release(player: Player) {
        stun.release(player)
        healLock.release(player)
        shieldBreak.release(player)
        justGuard.release(player)
        fall.release(player)
    }

    /** 全プレイヤーの状態異常を解除する */
    fun clear() {
        stun.clear()
        healLock.clear()
        shieldBreak.clear()
        justGuard.clear()
        respawn.clear()
        fall.clear()
    }
}
