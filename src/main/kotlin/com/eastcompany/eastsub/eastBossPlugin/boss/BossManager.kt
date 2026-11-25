package com.eastcompany.eastsub.eastBossPlugin.boss

import com.eastcompany.eastsub.eastBossPlugin.EastBossPlugin
import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.battle.BossBattle
import com.eastcompany.eastsub.eastBossPlugin.boss.minion.MinionGroup
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.tracker.ModelScaler
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Zombie
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * 召喚中のボスを管理する。召喚・毎tickの処理・削除はすべてここを通す。
 * 操作はすべてメインスレッドで行うこと。
 */
class BossManager(val plugin: EastBossPlugin) {

    private val bosses = linkedMapOf<UUID, Boss>()

    /** 召喚の結果 */
    sealed interface SpawnResult {
        data class Success(val boss: Boss) : SpawnResult
        /** 設定ファイルのモデル名が BetterModel に無い */
        data class ModelNotFound(val model: String) : SpawnResult
    }

    fun get(id: UUID): Boss? = bosses[id]

    fun isBoss(id: UUID): Boolean = bosses.containsKey(id)

    /** エンティティが召喚中のボスの手下なら、その持ち主のボス */
    fun ownerOfMinion(entity: Entity): Boss? {
        val owner = MinionGroup.ownerIdOf(entity)?.let { bosses[it] } ?: return null
        return if (owner.minions.contains(entity)) owner else null
    }

    /** ボス本体、またはその手下なら true（ボスの技が当たらない相手） */
    fun isBossSide(entity: Entity): Boolean = isBoss(entity.uniqueId) || ownerOfMinion(entity) != null

    /** 召喚中のボス一覧 */
    fun all(): Collection<Boss> = bosses.values

    /**
     * 指定した種類のボスを召喚する。
     * @param battle 旅商人から始めた戦闘（参加者だけを狙う）。コマンドからの召喚では null
     */
    fun spawn(type: BossType, location: Location, battle: BossBattle? = null): SpawnResult {
        val config = plugin.configManager.file(type.configPath)
        val settings = BossSettings.load(config)
        val renderer = plugin.betterModel.model(settings.model)
            ?: return SpawnResult.ModelNotFound(settings.model)

        // Class<out Mob> のままだと型引数を推論できないので Mob として扱う
        @Suppress("UNCHECKED_CAST")
        val entityClass = type.entityClass as Class<Mob>

        // 旅商人からのボス戦は、オンラインの参加人数に合わせて HP を増やす
        val players = battle?.onlineParticipants(plugin.server)?.size ?: 1
        val maxHealth = settings.scaledMaxHealth(players)

        // randomizeData = false: 素のモブの出現時のランダム（ハスクの装備・子供・ニワトリ騎手など）を付けない
        val mob = location.world.spawn(location, entityClass, false) { mob ->
            // 装備は持たせない（念のため消し、拾わせない）
            mob.equipment.clear()
            mob.canPickupItems = false
            // チャンクのアンロードやサーバー停止で保存されないようにする
            // （モデルの無い素のエンティティが残らないように）
            mob.isPersistent = false
            mob.removeWhenFarAway = false
            mob.isSilent = true
            // 当たり判定の大きさ（見た目の大きさは下の tracker.scaler で別に決める）
            if (settings.entityScale != 1.0) mob.getAttribute(Attribute.SCALE)?.baseValue = settings.entityScale
            val attribute = mob.getAttribute(Attribute.MAX_HEALTH)
            attribute?.baseValue = maxHealth
            // サーバー側の上限（spigot.yml の attribute.maxHealth.max）で切り詰められることがあるので、実際の値に合わせる
            mob.health = attribute?.value ?: maxHealth
            mob.customName(settings.displayName)
            mob.persistentDataContainer.set(Names.Key.BOSS, PersistentDataType.STRING, type.id)
        }

        // ハスクの素の殴り攻撃（と空腹）は無し。追って歩くだけにする（攻撃は全部ボスの技）
        if (mob is Zombie) ChaseGoal.replaceMelee(mob)

        val tracker = renderer.getOrCreate(BukkitAdapter.adapt(mob))
        // 見た目の大きさ。BetterModel は標準ではエンティティの SCALE に合わせるので、モデルの倍率で上書きする
        tracker.scaler(ModelScaler.value(settings.modelScale.toFloat()))
        val boss = type.create(BossSpawn(type, this, config, settings, mob, tracker, battle))
        bosses[boss.id] = boss
        boss.onSpawn()
        plugin.debug("${type.id} を召喚: $location（HP ${"%.0f".format(boss.maxHealth())} / 参加 $players 人）")
        return SpawnResult.Success(boss)
    }

    /**
     * ハスクは長く水に沈むとゾンビに変わり、ボスの管理から外れて殴るようになるので、変化が始まったらすぐ打ち切る。
     * EntityTransformEvent のキャンセルでは遅い（その前に装備・乗り物・チームが変化先へ移され、元のボスから消える）
     */
    private fun stopConversion(entity: Entity) {
        if (entity is Zombie && entity.isConverting) entity.conversionTime = -1
    }

    /** 毎tick呼ぶ */
    fun tickAll() {
        for (boss in bosses.values.toList()) {
            stopConversion(boss.entity)
            when {
                boss.isDying -> boss.tickDying()
                !boss.entity.isValid -> remove(boss)
                else -> boss.tick()
            }
        }
    }

    fun remove(boss: Boss) {
        if (bosses.remove(boss.id) != null) boss.dispose()
    }

    /**
     * すべてのボスを消す（onDisable・BetterModel のリロード時など）。
     * @return 消した数
     */
    fun removeAll(): Int {
        val list = bosses.values.toList()
        list.forEach { remove(it) }
        return list.size
    }
}
