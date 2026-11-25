package com.eastcompany.eastsub.eastBossPlugin.boss.minion

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.tracker.EntityTracker
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * ボスが連れている手下・コンパニオンのモブたち（ボス1体につき1つ）。
 *
 * - [spawn] で出したモブは、ボスが倒された・消えたときに自動で削除される
 * - 手下はボスの技に当たらず、旅商人から召喚したボスの手下は参加者だけを狙う
 * - 死んだ手下・チャンクのアンロードで消えた手下は、自動で一覧から外れる
 *
 * 使用例（Boss のサブクラスで）:
 * ```
 * minions.spawn(entity.location, Zombie::class.java, model = "tutorial_minion") { zombie ->
 *     zombie.customName(Component.text("お供"))
 * }
 * ```
 */
class MinionGroup(private val boss: Boss) {

    /** 手下1体分（モデル付きなら tracker も持つ） */
    class Minion(val entity: Mob, val tracker: EntityTracker?) {
        val isAlive: Boolean get() = entity.isValid && !entity.isDead

        fun remove() {
            tracker?.close()
            entity.remove()
        }
    }

    private val minions = linkedMapOf<UUID, Minion>()

    /** 生きている手下の一覧 */
    fun all(): List<Minion> = minions.values.filter { it.isAlive }

    /** 生きている手下の数 */
    val size: Int get() = minions.values.count { it.isAlive }

    fun contains(entity: Entity): Boolean = minions.containsKey(entity.uniqueId)

    /**
     * 手下を出す。
     * @param model BetterModel のモデル名（null なら素のモブのまま）。見つからなければ警告を出して素のモブにする
     * @param configure 出現前に呼ばれる（HP・名前・装備などの設定）
     */
    fun <T : Mob> spawn(
        location: Location,
        entityClass: Class<T>,
        model: String? = null,
        configure: (T) -> Unit = {},
    ): T {
        // ボスと同じく、出現時のランダム（装備・子供など）は付けない。装備は configure で持たせる
        val mob = location.world.spawn(location, entityClass, false) { m ->
            m.equipment.clear()
            m.canPickupItems = false
            // ボスと同じく、保存されないようにする（素のモブが残らないように）
            m.isPersistent = false
            m.removeWhenFarAway = false
            m.persistentDataContainer.set(Names.Key.MINION, PersistentDataType.STRING, boss.id.toString())
            configure(m)
        }

        val tracker = model?.let { name ->
            val renderer = boss.plugin.betterModel.model(name)
            if (renderer == null) {
                boss.plugin.logger.warning("[${boss.type.id}] 手下のモデル $name が見つかりません。モデル無しで出します")
            }
            renderer?.getOrCreate(BukkitAdapter.adapt(mob))
        }

        minions[mob.uniqueId] = Minion(mob, tracker)
        boss.plugin.debug("${boss.type.id}: 手下 ${entityClass.simpleName} を召喚（${minions.size}体）")
        return mob
    }

    /** 1体だけ削除する */
    fun remove(entity: Entity) {
        minions.remove(entity.uniqueId)?.remove()
    }

    /** 全員削除する */
    fun removeAll() {
        minions.values.forEach { it.remove() }
        minions.clear()
    }

    /** 毎tick呼ぶ。死んだ・消えた手下を一覧から外す（モデルも片付ける） */
    internal fun tick() {
        val iter = minions.values.iterator()
        while (iter.hasNext()) {
            val minion = iter.next()
            if (minion.isAlive) continue
            minion.tracker?.close()
            iter.remove()
        }
    }

    companion object {
        /** エンティティが手下なら、持ち主のボスの UUID を返す */
        fun ownerIdOf(entity: Entity): UUID? =
            entity.persistentDataContainer.get(Names.Key.MINION, PersistentDataType.STRING)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}
