package com.eastcompany.eastsub.eastBossPlugin.status

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerVelocityEvent
import org.bukkit.event.player.PlayerRespawnEvent

/** スタン中の操作制限と、回復不可中の回復打ち消しと、ジャストガードの受付（盾を構えた瞬間） */
class StatusListener(private val status: StatusManager) : Listener {

    private val stun get() = status.stun

    // ===== スタン =====

    /** 移動・視点を固定(落下だけは許可、上昇=ジャンプは禁止) */
    @EventHandler(ignoreCancelled = true)
    fun onMove(e: PlayerMoveEvent) {
        if (!stun.isStunned(e.player)) return
        val from = e.from
        val to = e.to
        e.setTo(Location(from.world, from.x, minOf(from.y, to.y), from.z, from.yaw, from.pitch))
    }

    /** 近接攻撃を禁止 */
    @EventHandler(ignoreCancelled = true)
    fun onMelee(e: EntityDamageByEntityEvent) {
        val p = e.damager as? Player ?: return
        if (stun.isStunned(p)) e.isCancelled = true
    }

    /** 左右クリック(アイテム使用・空振り・ブロック操作)を禁止 */
    @EventHandler(ignoreCancelled = true)
    fun onInteract(e: PlayerInteractEvent) {
        if (stun.isStunned(e.player)) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(e: PlayerInteractEntityEvent) {
        if (stun.isStunned(e.player)) e.isCancelled = true
    }

    /** 弓・クロスボウ・トライデント・投げ物を禁止 */
    @EventHandler(ignoreCancelled = true)
    fun onLaunch(e: ProjectileLaunchEvent) {
        val p = e.entity.shooter as? Player ?: return
        if (stun.isStunned(p)) e.isCancelled = true
    }

    // ===== 回復不可 =====

    /** 回復不可中のプレイヤーは、あらゆる回復を打ち消す */
    @EventHandler(ignoreCancelled = true)
    fun onRegain(e: EntityRegainHealthEvent) {
        val player = e.entity as? Player ?: return
        if (status.healLock.isLocked(player)) e.isCancelled = true
    }

    // ===== ジャストガード =====

    /**
     * 盾で右クリックした瞬間を覚える（ジャストガードの受付が開く）。
     * 空中への右クリックは最初からキャンセル扱いで届くので、キャンセルされたイベントも受け取る。
     * 盾が壊れて使えない間・スタン中は開かない
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRaiseShield(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        if (e.item?.type != Material.SHIELD) return
        if (e.useItemInHand() == Event.Result.DENY) return
        val player = e.player
        if (player.hasCooldown(Material.SHIELD) || stun.isStunned(player)) return
        // すでに構えている（押しっぱなし）なら、構え直しではない
        if (player.isHandRaised) return
        status.justGuard.raise(player, status.justGuardLockoutTicks)
    }

    /** リスポーンした時刻を覚える（しばらくボスに狙われない） */
    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        status.respawn.markRespawn(e.player)
    }

    /** ジャストガードした直後は、技が付けた吹き飛ばし（velocity の書き換え）を届けない */
    @EventHandler(ignoreCancelled = true)
    fun onVelocity(e: PlayerVelocityEvent) {
        if (status.justGuard.guardedNow(e.player)) e.isCancelled = true
    }

    // ===== 落下ダメージの保護 =====

    /** 打ち上げ・投げ飛ばされた後の落下ダメージを打ち消す（status.fall） */
    @EventHandler(ignoreCancelled = true)
    fun onFall(e: EntityDamageEvent) {
        if (e.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = e.entity as? Player ?: return
        if (status.fall.isProtected(player)) e.isCancelled = true
    }
}
