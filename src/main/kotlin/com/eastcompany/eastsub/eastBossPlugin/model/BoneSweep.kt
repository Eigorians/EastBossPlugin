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
 * ボーン直下のキューブを、そのままの形の箱（OBB）として掃引判定する汎用クラス。
 * 剣・胴体・腕など、どのボーンにも使える。
 *
 * tick間はクライアントの見た目と同じく
 *   位置 … 線形補間
 *   回転 … 球面線形補間（slerp）
 * で補間する。
 *
 * 1つのボーンにつき1インスタンス。複数ボーンで「1人1回」にしたい場合は
 * detect() に同じ hitEntities を渡す。
 */
class BoneSweep(
    /** 補間サンプルの最大移動量（ブロック） */
    private val step: Double = 0.1,
    /** 補間サンプルの最大回転量 */
    private val angleStep: Double = Math.toRadians(5.0),
    /** true の間、当たり判定をパーティクルで表示する */
    private val debug: () -> Boolean = { false }
) {

    /**
     * @param position    ボーンのピボット（ワールド座標）
     * @param rotation    ボーンのワールド回転
     * @param localCenter キューブ中心（ピボット基準・回転前、スケール適用済み）
     * @param half        キューブの半サイズ（スケール適用済み）
     */
    class Frame(
        val position: Vector3f,
        val rotation: Quaternionf,
        val localCenter: Vector3f,
        val half: Vector3f
    ) {
        fun center(): Vector3f = rotation.transform(Vector3f(localCenter)).add(position)
    }

    private class Sample(
        val center: Vector3f,
        val half: Vector3f,
        val axes: Array<Vector3f>
    )

    private var previous: Frame? = null

    /** 姿勢をリセット（判定区間ごとに呼ぶ） */
    fun reset() {
        previous = null
    }

    /** 判定せずに姿勢だけ記録する（判定区間の1tick前に呼ぶ） */
    fun prime(frame: Frame) {
        previous = frame
    }

    /**
     * 前tick → 今tick の間にボーンの箱が通った範囲に、新しく触れたEntityを返す。
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

        val samples = buildSamples(before, frame)

        if (debug()) {
            drawDebug(world, samples)
        }

        // Broad phase
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE

        for (s in samples) {
            val r = s.half.length().toDouble()
            minX = min(minX, s.center.x - r)
            minY = min(minY, s.center.y - r)
            minZ = min(minZ, s.center.z - r)
            maxX = max(maxX, s.center.x + r)
            maxY = max(maxY, s.center.y + r)
            maxZ = max(maxZ, s.center.z + r)
        }

        val area = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)

        val result = ArrayList<LivingEntity>()

        for (entity in world.getNearbyEntities(area)) {

            val living = entity as? LivingEntity ?: continue

            if (!living.isValid || living.isDead) continue
            if (living.uniqueId in hitEntities) continue
            if (!filter(living)) continue

            val box = living.boundingBox

            if (samples.any { intersects(it, box) }) {
                hitEntities.add(living.uniqueId)
                result.add(living)
            }
        }

        return result
    }

    private fun buildSamples(before: Frame, current: Frame): List<Sample> {

        val angle = 2.0 * acos(
            abs(before.rotation.dot(current.rotation)).coerceIn(0f, 1f).toDouble()
        )

        // 箱の最も遠い角がピボットから届く距離
        val reach = max(
            before.localCenter.length() + before.half.length(),
            current.localCenter.length() + current.half.length()
        ).toDouble()

        val travel = before.position.distance(current.position) + angle * reach

        val steps = max(
            1,
            max(ceil(travel / step).toInt(), ceil(angle / angleStep).toInt())
        ).coerceAtMost(128)

        val samples = ArrayList<Sample>(steps + 1)

        for (i in 0..steps) {

            val t = i.toFloat() / steps

            val position = Vector3f(before.position).lerp(current.position, t)
            val rotation = Quaternionf(before.rotation).slerp(current.rotation, t).normalize()
            val localCenter = Vector3f(before.localCenter).lerp(current.localCenter, t)
            val half = Vector3f(before.half).lerp(current.half, t)

            samples.add(
                Sample(
                    center = rotation.transform(localCenter).add(position),
                    half = half,
                    axes = arrayOf(
                        rotation.transform(Vector3f(1f, 0f, 0f)),
                        rotation.transform(Vector3f(0f, 1f, 0f)),
                        rotation.transform(Vector3f(0f, 0f, 1f))
                    )
                )
            )
        }

        return samples
    }

    /** OBB vs AABB のSAT判定（15軸） */
    private fun intersects(sample: Sample, box: BoundingBox): Boolean {

        val half = sample.half
        val axes = sample.axes

        val boxCenter = Vector3f(
            ((box.minX + box.maxX) * 0.5).toFloat(),
            ((box.minY + box.maxY) * 0.5).toFloat(),
            ((box.minZ + box.maxZ) * 0.5).toFloat()
        )

        val boxHalf = Vector3f(
            ((box.maxX - box.minX) * 0.5).toFloat(),
            ((box.maxY - box.minY) * 0.5).toFloat(),
            ((box.maxZ - box.minZ) * 0.5).toFloat()
        )

        val delta = Vector3f(boxCenter).sub(sample.center)

        fun separated(axis: Vector3f): Boolean {

            if (axis.lengthSquared() < 1.0E-8f) return false

            val distance = abs(delta.dot(axis))

            val radiusOBB =
                half.x * abs(axis.dot(axes[0])) +
                        half.y * abs(axis.dot(axes[1])) +
                        half.z * abs(axis.dot(axes[2]))

            val radiusAABB =
                boxHalf.x * abs(axis.x) +
                        boxHalf.y * abs(axis.y) +
                        boxHalf.z * abs(axis.z)

            return distance > radiusOBB + radiusAABB
        }

        for (axis in axes) if (separated(axis)) return false
        for (axis in WORLD_AXES) if (separated(axis)) return false

        for (a in axes) {
            for (b in WORLD_AXES) {
                if (separated(Vector3f(a).cross(b))) return false
            }
        }

        return true
    }

    /**
     * デバッグ表示
     *   赤 … 今tickの箱（辺）
     *   橙 … 前tick→今tickの補間サンプルの箱の中心
     */
    private fun drawDebug(world: World, samples: List<Sample>) {

        for (s in samples) {
            world.spawnParticle(
                Particle.DUST,
                s.center.x.toDouble(), s.center.y.toDouble(), s.center.z.toDouble(),
                1, 0.0, 0.0, 0.0, 0.0,
                SWEPT_DUST
            )
        }

        val last = samples.last()

        // 12本の辺
        val signs = listOf(-1f, 1f)
        val corners = ArrayList<Vector3f>()

        for (sx in signs) for (sy in signs) for (sz in signs) {
            corners.add(
                Vector3f(last.axes[0]).mul(last.half.x * sx)
                    .add(Vector3f(last.axes[1]).mul(last.half.y * sy))
                    .add(Vector3f(last.axes[2]).mul(last.half.z * sz))
                    .add(last.center)
            )
        }

        for (i in corners.indices) {
            for (j in i + 1 until corners.size) {

                // 1軸だけ符号が違う組 = 辺
                if (Integer.bitCount(i xor j) != 1) continue

                val a = corners[i]
                val b = corners[j]
                val count = max(1, ceil(a.distance(b) / 0.15).toInt())

                for (k in 0..count) {
                    val p = Vector3f(a).lerp(b, k.toFloat() / count)
                    world.spawnParticle(
                        Particle.DUST,
                        p.x.toDouble(), p.y.toDouble(), p.z.toDouble(),
                        1, 0.0, 0.0, 0.0, 0.0,
                        BOX_DUST
                    )
                }
            }
        }
    }

    companion object {

        private val WORLD_AXES = arrayOf(
            Vector3f(1f, 0f, 0f),
            Vector3f(0f, 1f, 0f),
            Vector3f(0f, 0f, 1f)
        )

        private val BOX_DUST = Particle.DustOptions(Color.RED, 0.6f)
        private val SWEPT_DUST = Particle.DustOptions(Color.ORANGE, 0.5f)

        /**
         * ボーンの現在の姿勢と、直下キューブの形から Frame を作る。
         * ボーン直下にキューブが無い場合は null。
         */
        fun frameOf(bone: RenderedBone, origin: Location): Frame? {

            val box = bone.getGroup().getHitBox() ?: return null

            val pose = BoneKinematics.worldPose(bone, origin)

            val scale = Vector3f(
                abs(pose.scale.x),
                abs(pose.scale.y),
                abs(pose.scale.z)
            )

            return Frame(
                position = pose.position,
                rotation = pose.rotation,
                localCenter = Vector3f(box.centerPoint()).mul(pose.scale),
                half = Vector3f(
                    (box.x() * 0.5).toFloat(),
                    (box.y() * 0.5).toFloat(),
                    (box.z() * 0.5).toFloat()
                ).mul(scale)
            )
        }
    }
}
