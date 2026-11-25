package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.tracker.DummyTracker
import kr.toxicity.model.api.tracker.ModelScaler
import kr.toxicity.model.api.tracker.TrackerUpdateAction
import kr.toxicity.model.api.util.function.BonePredicate
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.persistence.PersistentDataType

/**
 * 暗殺者の分身。本体と同じモデルを、エンティティなしで置いたもの（BetterModel の DummyTracker）。
 *
 * - 見た目は本体と同じ（目の色・刃の光も合わせる）。アニメも本体と同じ名前で流せる
 * - hittable なら、見えない当たり判定（Interaction）を重ねる。殴られると [onHit] が呼ばれる（攻撃は BossListener が止める）
 * - 本体のダメージの扱い（参加者だけに当たる・damage-multiplier）を揃えるため、分身の攻撃は本体からのダメージにする
 *
 * 作るのは [AssassinBoss.spawnClone]、消すのは [AssassinBoss.removeClone]（ボスが消えるときもまとめて消える）。
 */
class AssassinClone private constructor(
    val boss: AssassinBoss,
    private val tracker: DummyTracker,
    private val hitbox: Interaction?,
    start: Location,
) {

    /** 今いる所（足元。向きも持つ） */
    var location: Location = start.clone()
        private set

    /** 殴られたとき（hittable の分身だけ） */
    var onHit: ((org.bukkit.entity.Player) -> Unit)? = null

    val isRemoved: Boolean get() = tracker.isClosed

    /** この当たり判定がこの分身のものか */
    fun owns(entity: Entity): Boolean = hitbox != null && hitbox.uniqueId == entity.uniqueId

    /** 動かす（向きは loc の yaw） */
    fun moveTo(loc: Location) {
        val l = loc.clone()
        l.pitch = 0f
        location = l
        tracker.location(BukkitAdapter.adapt(l))
        hitbox?.teleport(l)
    }

    /** 今の所のまま、at の方を向く */
    fun faceTo(at: Location) {
        val dir = Facing.horizontalDir(location.toVector(), at.toVector()) ?: return
        val l = location.clone()
        l.yaw = Facing.yawOf(dir)
        moveTo(l)
    }

    /** 向いている水平方向 */
    fun forward() = Facing.dirOfYaw(location.yaw)

    fun playOnce(animation: String) {
        if (animation.isNotEmpty()) tracker.animate(animation, AnimationModifier.DEFAULT_WITH_PLAY_ONCE)
    }

    fun playLoop(animation: String, lerpTicks: Int = 1) {
        if (animation.isEmpty()) return
        tracker.animate(animation, AnimationModifier.builder().type(AnimationIterator.Type.LOOP).start(lerpTicks.coerceAtLeast(1)).build())
    }

    fun stopAnimation(animation: String) {
        if (animation.isNotEmpty()) tracker.stopAnimation(animation)
    }

    /** 本体と同じ見た目に合わせる（目の色・刃の光・苦無は隠す） */
    fun matchLook(eyeColor: Int, raging: Boolean) {
        tracker.update(TrackerUpdateAction.tint(eyeColor), BonePredicate.name(AssassinBoss.EYES).withoutChildren())
        setPartVisible("kunai_fan", false)
        setPartVisible("edge_r", raging)
        setPartVisible("edge_l", raging)
    }

    fun setPartVisible(boneName: String, visible: Boolean) {
        tracker.update(TrackerUpdateAction.togglePart(visible), BonePredicate.name(boneName).withChildren())
    }

    /** 毎tick呼ぶ。近くに来たプレイヤーに見せる */
    fun tick() {
        if (isRemoved) return
        for (p in location.world.getNearbyPlayers(location, VIEW_RANGE)) {
            val pp = BukkitAdapter.adapt(p)
            if (!tracker.isSpawned(pp)) tracker.spawn(pp)
        }
    }

    /** 消す（演出は呼ぶ側で） */
    internal fun close() {
        if (!tracker.isClosed) tracker.close()
        hitbox?.remove()
    }

    companion object {
        private const val VIEW_RANGE = 64.0

        /**
         * 分身を作る。モデルが見つからなければ null。
         * @param hittable 殴れる当たり判定を付けるか
         */
        internal fun create(boss: AssassinBoss, at: Location, hittable: Boolean): AssassinClone? {
            val renderer = boss.plugin.betterModel.model(boss.settings.model) ?: return null
            val start = at.clone().also { it.pitch = 0f }
            val tracker = renderer.create(BukkitAdapter.adapt(start))
            tracker.scaler(ModelScaler.value(boss.settings.modelScale.toFloat()))
            val hitbox = if (hittable) {
                start.world.spawn(start, Interaction::class.java) { box ->
                    box.isPersistent = false
                    // 本体の当たり判定と同じくらいの大きさ
                    box.interactionWidth = (0.7 * boss.settings.entityScale).toFloat()
                    box.interactionHeight = (2.0 * boss.settings.entityScale).toFloat()
                    box.isResponsive = true
                    box.persistentDataContainer.set(Names.Key.DECOY, PersistentDataType.STRING, boss.id.toString())
                }
            } else null
            return AssassinClone(boss, tracker, hitbox, start).also { it.tick() }
        }
    }
}
