package com.eastcompany.eastsub.eastBossPlugin.model

import kr.toxicity.model.api.bone.RenderedBone
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.util.BoundingBox
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 刃を「線分 ＋ 半径」のカプセルとして扱う掃引判定。
 *
 * 毎tick、剣ボーンの姿勢（ピボット位置・回転）と、ボーン基準の刃の両端を受け取る。
 * tick間はクライアントの見た目と同じく
 *   位置 … 線形補間
 *   回転 … 球面線形補間（slerp）
 * で補間するので、振りの中心（body など）の指定は不要。
 *
 * ヒット履歴は [BoneSweep] と同じく detect() に渡す（複数ボーンで「1人1回」にできる）。
 */
class SwordSweep(
    /** 刃の太さ（半径・ブロック） */
    private val radius: Double,
    /** 補間サンプルの最大間隔（ブロック） */
    private val step: Double = 0.1,
    /** true の間、当たり判定をパーティクルで表示する（毎回評価するので実行中の切替も反映） */
    private val debug: () -> Boolean = { false }
) {

    /**
     * @param position 剣ボーンのピボット（ワールド座標）
     * @param rotation 剣ボーンのワールド回転
     * @param localA   刃の一端（ピボット基準・回転前、スケール適用済み）
     * @param localB   刃のもう一端
     */
    class Frame(
        val position: Vector3f,
        val rotation: Quaternionf,
        val localA: Vector3f,
        val localB: Vector3f
    ) {
        fun pointA(): Vector3f = rotation.transform(Vector3f(localA)).add(position)
        fun pointB(): Vector3f = rotation.transform(Vector3f(localB)).add(position)
    }

    private var previous: Frame? = null

    init {
        require(radius > 0.0)
        require(step > 0.0)
    }

    /** 姿勢をリセット（判定区間ごとに呼ぶ） */
    fun reset() {
        previous = null
    }

    /** 判定せずに姿勢だけ記録する（判定区間の1tick前に呼ぶ） */
    fun prime(frame: Frame) {
        previous = frame
    }

    /**
     * 前tick → 今tick の間に刃が通った範囲に、新しく触れたEntityを返す。
     * 返したEntityは hitEntities に追加される。
     */
    fun detect(
        world: World,
        frame: Frame,
        hitEntities: MutableSet<UUID>,
        filter: (LivingEntity) -> Boolean = { true }
    ): List<LivingEntity> {

        val before = previous ?: frame
        previous = frame

        val points = samplePoints(before, frame)

        if (debug()) {
            drawDebug(world, frame, points)
        }

        // Broad phase
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE

        for (p in points) {
            minX = min(minX, p.x.toDouble())
            minY = min(minY, p.y.toDouble())
            minZ = min(minZ, p.z.toDouble())
            maxX = max(maxX, p.x.toDouble())
            maxY = max(maxY, p.y.toDouble())
            maxZ = max(maxZ, p.z.toDouble())
        }

        val area = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ).expand(radius)

        val result = ArrayList<LivingEntity>()
        val r2 = radius * radius

        for (entity in world.getNearbyEntities(area)) {

            val living = entity as? LivingEntity ?: continue

            if (!living.isValid || living.isDead) continue
            if (living.uniqueId in hitEntities) continue
            if (!filter(living)) continue

            val box = living.boundingBox

            // Narrow phase：各サンプル点（球）とEntityの箱
            if (points.any { distanceSquared(it, box) <= r2 }) {
                hitEntities.add(living.uniqueId)
                result.add(living)
            }
        }

        return result
    }

    /** 前tick〜今tickの刃を、時間方向・刃の長さ方向の両方で点に分解する */
    private fun samplePoints(before: Frame, current: Frame): List<Vector3f> {

        // 刃の両端が動いた距離の目安（直線距離 ＋ 回転角 × 刃の届く距離）
        val angle = 2.0 * acos(
            abs(before.rotation.dot(current.rotation)).coerceIn(0f, 1f).toDouble()
        )

        val reach = max(
            max(before.localA.length(), before.localB.length()),
            max(current.localA.length(), current.localB.length())
        ).toDouble()

        val travel = max(
            before.pointA().distance(current.pointA()),
            before.pointB().distance(current.pointB())
        ) + angle * reach

        val timeSteps = max(1, ceil(travel / step).toInt()).coerceAtMost(128)

        val points = ArrayList<Vector3f>()

        for (i in 0..timeSteps) {

            val t = i.toFloat() / timeSteps

            val position = Vector3f(before.position).lerp(current.position, t)
            val rotation = Quaternionf(before.rotation).slerp(current.rotation, t)
            val localA = Vector3f(before.localA).lerp(current.localA, t)
            val localB = Vector3f(before.localB).lerp(current.localB, t)

            val a = rotation.transform(localA).add(position)
            val b = rotation.transform(localB).add(position)

            // 刃の長さ方向：球の間隔が半径以下になるように並べる
            val bladeSteps = max(1, ceil(a.distance(b) / radius).toInt())

            for (j in 0..bladeSteps) {
                points.add(Vector3f(a).lerp(b, j.toFloat() / bladeSteps))
            }
        }

        return points
    }

    /**
     * デバッグ表示
     *   赤   … 今tickの刃（線分）
     *   橙   … 前tick→今tickの間に刃が通った範囲（判定に使ったサンプル点）
     */
    private fun drawDebug(world: World, frame: Frame, points: List<Vector3f>) {

        // 掃引範囲：点が多いと重いので最大200個に間引く
        val stride = max(1, points.size / 200)

        for (i in points.indices step stride) {
            val p = points[i]
            world.spawnParticle(
                Particle.DUST,
                p.x.toDouble(), p.y.toDouble(), p.z.toDouble(),
                1, 0.0, 0.0, 0.0, 0.0,
                SWEPT_DUST
            )
        }

        // 今tickの刃
        val a = frame.pointA()
        val b = frame.pointB()
        val count = max(2, ceil(a.distance(b) / 0.1).toInt())

        for (i in 0..count) {
            val p = Vector3f(a).lerp(b, i.toFloat() / count)
            world.spawnParticle(
                Particle.DUST,
                p.x.toDouble(), p.y.toDouble(), p.z.toDouble(),
                1, 0.0, 0.0, 0.0, 0.0,
                BLADE_DUST
            )
        }
    }

    private fun distanceSquared(p: Vector3f, box: BoundingBox): Double {
        val dx = max(max(box.minX - p.x, 0.0), p.x - box.maxX)
        val dy = max(max(box.minY - p.y, 0.0), p.y - box.maxY)
        val dz = max(max(box.minZ - p.z, 0.0), p.z - box.maxZ)
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        private val BLADE_DUST = Particle.DustOptions(Color.RED, 0.8f)
        private val SWEPT_DUST = Particle.DustOptions(Color.ORANGE, 0.5f)

        /**
         * 剣ボーンの現在の姿勢と、直下キューブの最も長い辺（刃）から Frame を作る。
         * ボーン直下にキューブが無い場合は null。
         */
        fun frameOf(bone: RenderedBone, origin: Location): Frame? {
            val (a, b) = BoneKinematics.bladeLocal(bone) ?: return null
            val pose = BoneKinematics.worldPose(bone, origin)
            return Frame(
                position = pose.position,
                rotation = pose.rotation,
                localA = Vector3f(a).mul(pose.scale),
                localB = Vector3f(b).mul(pose.scale)
            )
        }
    }
}
