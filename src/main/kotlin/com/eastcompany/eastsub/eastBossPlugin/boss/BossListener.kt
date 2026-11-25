package com.eastcompany.eastsub.eastBossPlugin.boss

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.status.JustGuardFx
import io.papermc.paper.event.entity.EntityKnockbackEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.entity.Player
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import kotlin.math.ceil

/**
 * ボスに関わるイベントを受け取り、該当する Boss に渡す。
 * 個別のボスの事情はここに書かず、Boss の onXxx や BossSettings で表現する。
 */
class BossListener(private val manager: BossManager) : Listener {

    private val config get() = manager.plugin.configManager.main

    // ===== 耐性 =====

    /** 飛び道具耐性：矢・トライデント・火の玉などは当たらずにすり抜ける */
    @EventHandler(ignoreCancelled = true)
    fun onProjectileHit(e: ProjectileHitEvent) {
        val hit = e.hitEntity ?: return
        val boss = manager.get(hit.uniqueId) ?: return
        if (boss.settings.immuneToProjectile || boss.isIntangible) e.isCancelled = true
    }

    /** 当たり判定が無い間（影に潜っているなど）は、どんなダメージも受けない */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onIntangibleDamage(e: EntityDamageEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: return
        if (boss.isIntangible) e.isCancelled = true
    }

    /** スプラッシュポーション耐性 */
    @EventHandler(ignoreCancelled = true)
    fun onPotionSplash(e: PotionSplashEvent) {
        for (entity in e.affectedEntities.toList()) {
            val boss = manager.get(entity.uniqueId) ?: continue
            if (boss.settings.immuneToPotion) e.setIntensity(entity, 0.0)
        }
    }

    /** 縦方向のノックバックを消す（横は残る） */
    @EventHandler(ignoreCancelled = true)
    fun onKnockback(e: EntityKnockbackEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: return
        if (boss.settings.immuneToVerticalKnockback) e.knockback = e.knockback.setY(0.0)
    }

    // ===== 標的 =====

    /**
     * ボスと手下は、仲間（ボス・手下）を狙わない。
     * 旅商人から召喚したボスとその手下は、エンティティ本来のAIでも参加者以外を狙わない
     * （技の標的は TargetFinder 側で絞っている）
     */
    @EventHandler(ignoreCancelled = true)
    fun onTarget(e: EntityTargetEvent) {
        val target = e.target ?: return
        val boss = manager.get(e.entity.uniqueId) ?: manager.ownerOfMinion(e.entity) ?: return
        if (manager.isBossSide(target)) {
            e.isCancelled = true
            return
        }
        // リスポーンして間もない人は狙わない
        if (target is Player && boss.status.isRespawnProtected(target)) {
            e.isCancelled = true
            return
        }
        val battle = boss.battle ?: return
        if (!battle.isParticipant(target)) e.isCancelled = true
    }

    // ===== ダメージ =====

    /**
     * 旅商人から召喚したボスとその手下は、参加者以外からのダメージを受けない。
     * 飛び道具は撃った人、着火した TNT は火を付けた人を攻撃者とみなす。
     * 手下は攻撃者のいないダメージ（落下・溶岩など）をそのまま受ける（ボス本体は onBossExternalDamage で受けない）
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamagedByOutsider(e: EntityDamageByEntityEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: manager.ownerOfMinion(e.entity) ?: return
        val battle = boss.battle ?: return
        val attacker = attackerOf(e.damager)
        if (!battle.isParticipant(attacker)) e.isCancelled = true
    }

    /**
     * 旅商人から召喚したボスとその手下（とその飛び道具）は、参加者以外にダメージを与えない。
     * 技の当たり判定（TargetFinder.isValidTarget）でも除いているが、手下の通常攻撃なども含めてここで止める
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamageOutsider(e: EntityDamageByEntityEvent) {
        val attacker = attackerOf(e.damager)
        val boss = manager.get(attacker.uniqueId) ?: manager.ownerOfMinion(attacker) ?: return
        // リスポーンして間もない人には、ボスと手下の攻撃が当たらない（エンティティ本来の AI の攻撃も）
        val victim = e.entity
        if (victim is Player && boss.status.isRespawnProtected(victim)) {
            e.isCancelled = true
            return
        }
        val battle = boss.battle ?: return
        if (!battle.isParticipant(e.entity)) e.isCancelled = true
    }

    /** 実際に攻撃した相手（飛び道具なら撃った人、TNT なら火を付けた人） */
    private fun attackerOf(damager: Entity): Entity = when (damager) {
        is Projectile -> damager.shooter as? Entity ?: damager
        is TNTPrimed -> damager.source ?: damager
        else -> damager
    }

    /**
     * ボスはプレイヤーによるダメージしか受けない（溶岩・マグマ・火・落下・窒息・落雷・ほかのモブなどは効かない）。
     * 無敵時間が付く前に止まるので、溶岩に浸かっていてもプレイヤーの攻撃はそのまま通る。
     * LOWEST で止めるので、ボスごとの反応（interceptDamage・onHurt）にも届かない
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBossExternalDamage(e: EntityDamageEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: return
        if (!isFromPlayer(boss, e)) e.isCancelled = true
    }

    private fun isFromPlayer(boss: Boss, e: EntityDamageEvent): Boolean {
        if (e.cause in UNSTOPPABLE) return true
        if (e.damageSource.causingEntity is Player) return true
        if (e is EntityDamageByEntityEvent && attackerOf(e.damager) is Player) return true
        return when (e.cause) {
            DamageCause.FIRE_TICK -> boss.playerBurns.isBurning(Bukkit.getCurrentTick())
            // ハスクには効かず、ガストにもスプラッシュは無効・毒を持つモブの攻撃は上で止まるので、残るのはプレイヤーの毒矢
            DamageCause.POISON -> true
            else -> false
        }
    }

    /**
     * プレイヤー（ボス戦では参加者）が付けた火だけ燃え移る。
     * 燃えている間に溶岩に入ると引火イベント無しで燃焼が延びるので、プレイヤーの火を覚えて（PlayerBurns）onBossExternalDamage で見分ける
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBossCombust(e: EntityCombustEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: return
        val igniter = (e as? EntityCombustByEntityEvent)?.let { attackerOf(it.combuster) } as? Player
        val battle = boss.battle
        if (igniter == null || (battle != null && !battle.isParticipant(igniter))) {
            e.isCancelled = true
            return
        }
        boss.playerBurns.ignite(igniter.uniqueId, Bukkit.getCurrentTick(), ceil(e.duration * TICKS_PER_SECOND).toInt())
    }

    /** 今の燃焼ダメージの元の火を付けたプレイヤー */
    private fun igniterOf(boss: Boss): UUID? = boss.playerBurns.igniter(Bukkit.getCurrentTick(), boss.entity.fireTicks)

    /**
     * ボスが受けるダメージ：飛び道具耐性・飛び道具の倍率（config.yml の boss.projectile-damage-multiplier・全ボス共通。
     * ボス yml の immunity.projectile-damage-multiplier があればそちら）
     * → ボス・技ごとの補正
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBossDamaged(e: EntityDamageByEntityEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: return
        if (boss.isDying) return

        if (e.damager is Projectile) {
            if (boss.settings.immuneToProjectile) {
                e.isCancelled = true
                return
            }
            e.damage *= boss.settings.projectileDamageMultiplier
                ?: config.double(Names.Config.BOSS_PROJECTILE_DAMAGE_MULTIPLIER, 0.5)
        }
        boss.modifyIncomingDamage(e)
    }

    /**
     * 1回の攻撃でボスが受けるダメージの上限（config.yml の boss.max-damage-per-hit・全ボス共通）。
     * 技ごとの倍率などをすべて掛けた後、最後に切り詰める
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBossDamageCap(e: EntityDamageEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: return
        val cap = config.double(Names.Config.BOSS_MAX_DAMAGE_PER_HIT, 30.0)
        if (cap > 0 && e.damage > cap) {
            boss.plugin.debug("${boss.type.id}: ダメージ ${"%.1f".format(e.damage)} を上限 $cap に切り詰め")
            e.damage = cap
        }
        // 上限を掛けた後の値で決めるので、別のハンドラに分けずにここで呼ぶ
        if (boss.isDying || e.cause in UNSTOPPABLE) return
        val before = boss.entity.health
        if (!boss.interceptDamage(e)) return
        e.isCancelled = true
        // 止めても HP が減っていれば（1 で耐えた一撃）、与ダメージに数える
        countDamage(boss, e, before - boss.entity.health)
    }

    /** ボスがダメージを受けた後（キャンセルされた攻撃では呼ばない） */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBossHurt(e: EntityDamageEvent) {
        val boss = manager.get(e.entity.uniqueId) ?: return
        if (boss.isDying) return
        countDamage(boss, e, minOf(e.finalDamage, boss.entity.health))
        boss.onHurt()
    }

    /**
     * 旅商人からのボス戦で、参加者ごとの与ダメージを数える（実際に減った HP。とどめの一撃は残りの HP まで）。
     * 飛び道具は撃った人、燃焼は火を付けた人、毒などのほかの継続ダメージは最後に攻撃した人のぶん
     */
    private fun countDamage(boss: Boss, e: EntityDamageEvent, amount: Double) {
        if (amount <= 0) return
        val battle = boss.battle ?: return
        val player: UUID = when {
            e is EntityDamageByEntityEvent -> (attackerOf(e.damager) as? Player)?.uniqueId
            e.cause == DamageCause.FIRE_TICK -> igniterOf(boss)
            e.cause in DAMAGE_OVER_TIME -> boss.entity.killer?.uniqueId
            else -> null
        } ?: return
        battle.addDamage(player, amount)
    }

    /** ボスが与えるダメージに、ボスごとの倍率（damage-multiplier）を掛ける。ボス本体の素の殴りは無いので、技に効く */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBossAttack(e: EntityDamageByEntityEvent) {
        val boss = manager.get(e.damager.uniqueId) ?: return
        e.damage *= boss.settings.damageMultiplier
    }

    /**
     * ジャストガード: 盾を構えた直後（受付の間）にボスの攻撃を受けると、ダメージを消して効果音を鳴らす。
     * 盾のガード破壊（ShieldBreakManager）がたまらない。回復不可・ノックバックも無し（技が付ける吹き飛ばし・スタンも止める）。
     * どの攻撃でも受けられる（弾・魔法も）。盾を持っていて、ボスの方を向いているときだけ
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onJustGuard(e: EntityDamageByEntityEvent) {
        val player = e.entity as? Player ?: return
        val boss = manager.get(e.damager.uniqueId) ?: return
        if (boss.isDying || boss.guardPiercing) return
        val inv = player.inventory
        if (inv.itemInMainHand.type != Material.SHIELD && inv.itemInOffHand.type != Material.SHIELD) return
        if (player.hasCooldown(Material.SHIELD)) return
        // 盾の正面（ボスの方を向いている）
        val look = player.location.direction.setY(0)
        val toBoss = boss.entity.location.toVector().subtract(player.location.toVector()).setY(0)
        if (look.lengthSquared() > 1e-6 && toBoss.lengthSquared() > 1e-6) {
            val limit = config.double(Names.Config.JUST_GUARD_FACING_DEGREES, 90.0)
            if (Math.toDegrees(look.angle(toBoss).toDouble()) > limit) return
        }
        if (boss.status.justGuard.tryGuard(player, boss.status.justGuardWindowTicks)) {
            e.isCancelled = true
            JustGuardFx.play(player, boss.entity.location.add(0.0, boss.entity.height * 0.5, 0.0), manager.plugin.messages.component(Names.Message.JUST_GUARD))
            boss.onJustGuard(player)
            return
        }
        // ジャストガードではないが、盾を構えていてボスの方を向いている: バニラのガードが効かなかった分を補う
        if (player.isHandRaised && player.activeItem.type == Material.SHIELD && !vanillaBlocked(e)) {
            guardLikeVanilla(boss, player, e)
        }
    }

    /** バニラの盾が防いだか（ダメージの BLOCKING の補正が付いているか） */
    @Suppress("DEPRECATION")
    private fun vanillaBlocked(e: EntityDamageByEntityEvent): Boolean =
        e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING) && e.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0

    /**
     * バニラの盾が防がなかった攻撃を、ふつうのガードとして受ける（ダメージ無し・盾のガード破壊はたまる）。
     * バニラの盾は (1) 構えてから 5tick たつまで効かない（構え直した直後は、連打防止でジャストガードの受付も開かない）
     * (2) 攻撃したエンティティの位置で向きを判定するので、ボスが重なるほど近い・真上から落ちてくると、ボスの方を向いていても効かない。
     * どちらも「ボスを見て盾を構えているのに防げない」になるので、ここで防ぐ
     */
    private fun guardLikeVanilla(boss: Boss, player: Player, e: EntityDamageByEntityEvent) {
        val blocked = e.damage * boss.settings.shieldMultiplier(boss.currentSkill?.id)
        e.isCancelled = true
        player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.0f, 0.9f + Math.random().toFloat() * 0.2f)
        addShieldBlocked(boss, player, blocked)
    }

    /** ボスがプレイヤーにダメージを与えた後 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDamagedByBoss(e: EntityDamageByEntityEvent) {
        val player = e.entity as? Player ?: return
        val boss = manager.get(e.damager.uniqueId) ?: return
        // どのボスのどの攻撃でも、当たったら回復不可にする（config.yml の status.heal-lock-ticks・全ボス共通）
        val healLock = config.int(Names.Config.STATUS_HEAL_LOCK_TICKS, 100)
        if (healLock > 0) boss.status.healLock.lock(player, healLock)
        checkShieldBreak(boss, player, e)
        boss.onDamagePlayer(player, e)
    }

    /**
     * 盾で防いだ攻撃なら、防いだダメージをためる。ボスの yml の shield.break-damage に届くと盾が壊れ、
     * shield.disable-ticks の間使えなくなる（全ボス共通。盾で全部防ぎ続けられないように）
     */
    @Suppress("DEPRECATION")
    private fun checkShieldBreak(boss: Boss, player: Player, e: EntityDamageByEntityEvent) {
        if (!player.isBlocking) return
        if (!e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) return
        val s = boss.settings
        // 技ごとの倍率（遠距離の技は盾で受けやすいなど）。技が終わった後に届いた弾は技の外として 1 倍
        val blocked = -e.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) * s.shieldMultiplier(boss.currentSkill?.id)
        addShieldBlocked(boss, player, blocked)
        boss.onLateGuard(player)
    }

    /** 盾で防いだダメージをためる。上限に届いたら盾が壊れたことを知らせる */
    private fun addShieldBlocked(boss: Boss, player: Player, blocked: Double) {
        if (blocked <= 0) return
        val s = boss.settings
        if (boss.status.shieldBreak.addBlocked(player, blocked, s.shieldBreakDamage, s.shieldDisableTicks)) {
            manager.plugin.messages.send(player, Names.Message.SHIELD_BROKEN, "seconds" to (s.shieldDisableTicks / 20))
        }
    }

    // ===== 分身（おとり） =====

    /**
     * ボスの分身の当たり判定（Interaction など）を殴ったら、攻撃は止めて持ち主のボスに知らせる。
     * 旅商人からのボス戦では、参加者の攻撃だけを知らせる
     */
    @EventHandler(ignoreCancelled = true)
    fun onDecoyAttacked(e: PrePlayerAttackEntityEvent) {
        val raw = e.attacked.persistentDataContainer.get(Names.Key.DECOY, PersistentDataType.STRING) ?: return
        e.isCancelled = true
        val owner = runCatching { UUID.fromString(raw) }.getOrNull()?.let { manager.get(it) } ?: return
        if (owner.isDying) return
        val battle = owner.battle
        if (battle != null && !battle.isParticipant(e.player)) return
        owner.onDecoyAttacked(e.player, e.attacked)
    }

    // ===== 死亡 =====

    /** ボスが死んだら死亡演出へ（手下も消える）。ボス・手下ともにドロップと経験値は出さない */
    @EventHandler
    fun onBossDeath(e: EntityDeathEvent) {
        val boss = manager.get(e.entity.uniqueId)
        if (boss == null && manager.ownerOfMinion(e.entity) == null) return
        e.drops.clear()
        e.droppedExp = 0
        if (boss == null) return
        boss.slainBy = slayerOf(boss, e)
        if (boss.slainBy == null) boss.plugin.debug("${boss.type.id}: プレイヤーの攻撃以外で倒れた（討伐にしない）")
        boss.die()
    }

    /**
     * ボスにとどめを刺したプレイヤー（旅商人からのボス戦では参加者だけ）。プレイヤーに倒されたのでなければ null。
     *
     * - 最後のダメージがプレイヤー（の飛び道具・火を付けた TNT）の攻撃なら、その人
     * - 燃焼で倒れたなら、火を付けた人（その人が脱落していれば最後に攻撃した人。残った人の勝ちが消えないように）
     * - 毒などのほかの継続ダメージで倒れたなら、最後に攻撃した人（killer）
     * - /kill・奈落・そのほかの原因で倒れたなら null（討伐にしない）
     */
    private fun slayerOf(boss: Boss, e: EntityDeathEvent): UUID? {
        val last = e.entity.lastDamageCause ?: return null
        fun canSlay(id: UUID): Boolean = boss.battle?.isParticipant(id) ?: true
        val player: UUID? = when {
            last is EntityDamageByEntityEvent -> (attackerOf(last.damager) as? Player)?.uniqueId
            last.cause == DamageCause.FIRE_TICK -> igniterOf(boss)?.takeIf(::canSlay) ?: e.entity.killer?.uniqueId
            last.cause in DAMAGE_OVER_TIME -> e.entity.killer?.uniqueId
            else -> null
        }
        return player?.takeIf(::canSlay)
    }

    /**
     * ボスがプレイヤーを倒したとき。
     * 旅商人から召喚したボスはここでは何もしない（参加者の死亡回数で消える。BattleManager が担当）
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerKilled(e: PlayerDeathEvent) {
        val cause = e.player.lastDamageCause as? EntityDamageByEntityEvent ?: return
        val boss = manager.get(cause.damager.uniqueId) ?: return
        if (boss.isDying || boss.battle != null) return
        boss.onKillPlayer(e.player)
    }

    private companion object {
        const val TICKS_PER_SECOND = 20

        /** 外的要因の無効化・Boss.interceptDamage で止められない（/kill と奈落では必ず消せるように） */
        val UNSTOPPABLE = setOf(DamageCause.KILL, DamageCause.VOID)

        /** 継続ダメージ（最後に攻撃したプレイヤーの分とみなす。燃焼＝FIRE_TICK は火を付けた人を優先） */
        val DAMAGE_OVER_TIME = setOf(
            DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.POISON, DamageCause.WITHER,
            DamageCause.MAGIC, DamageCause.THORNS, DamageCause.FREEZE, DamageCause.CAMPFIRE,
        )
    }
}
