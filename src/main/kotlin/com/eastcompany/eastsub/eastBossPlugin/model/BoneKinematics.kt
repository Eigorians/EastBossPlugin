package com.eastcompany.eastsub.eastBossPlugin.model

import kr.toxicity.model.api.bone.BoneMovement
import kr.toxicity.model.api.bone.RenderedBone
import kr.toxicity.model.api.util.lock.DuplexLock
import org.bukkit.Bukkit
import org.bukkit.Location
import org.joml.Quaternionf
import org.joml.Vector3f
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Supplier
import kotlin.math.abs

/**
 * BetterModel 3.3.0〜3.6.0 のボーン姿勢を「クライアントに見えている通り」に求めるユーティリティ。
 *
 * BetterModel はキーフレームごとに、親込みで合成済みの姿勢（位置・回転・スケール）を
 * Display エンティティへ送り、キーフレーム間はクライアントが
 *   位置・スケール … 線形補間
 *   回転          … 球面線形補間（slerp）
 * で補間する。
 *
 * 公開APIの worldPosition()/worldRotation() はこれと違う補間（ボーン単体のオイラー角を線形補間）
 * をするため、複数のボーンを複数軸で同時に回すとキーフレーム間でずれる。
 *
 * そこで内部の before / current（合成済み姿勢）と進み具合をリフレクションで読み、
 * クライアントと同じ補間をする。読めない場合は公開APIによる近似に切り替える。
 */
object BoneKinematics {

    /** 補間済みの姿勢（モデル座標、親込み） */
    class Transform(
        val position: Vector3f,
        val rotation: Quaternionf,
        val scale: Vector3f
    )

    // ---- BetterModel 内部へのアクセス（3.3.0〜3.6.0 で同じ構造であることを確認済み）----

    private object Internal {

        val globalState: Field?
        val before: Field?
        val current: Field?
        val lock: Field?
        val progress: Method?

        init {
            var gs: Field? = null
            var b: Field? = null
            var c: Field? = null
            var l: Field? = null
            var p: Method? = null

            try {
                gs = RenderedBone::class.java.getDeclaredField("globalState").apply { isAccessible = true }

                val handler = gs.type

                b = handler.getDeclaredField("before").apply { isAccessible = true }
                c = handler.getDeclaredField("current").apply { isAccessible = true }
                l = handler.getDeclaredField("lock").apply { isAccessible = true }
                p = handler.getDeclaredMethod("progress").apply { isAccessible = true }

            } catch (e: Exception) {
                Bukkit.getLogger().warning(
                    "[BoneKinematics] BetterModel内部にアクセスできません（バージョン違い？）。" +
                            "近似計算に切り替えます: $e"
                )
                gs = null
            }

            globalState = gs
            before = b
            current = c
            lock = l
            progress = p
        }

        val available: Boolean get() = globalState != null
    }

    /**
     * クライアントと同じ補間で、ボーンの合成済み姿勢を求める。
     */
    fun transform(bone: RenderedBone): Transform {

        if (Internal.available) {
            try {
                return exactTransform(bone)
            } catch (_: Exception) {
                // 下の近似へ
            }
        }

        return approximateTransform(bone)
    }

    private fun exactTransform(bone: RenderedBone): Transform {

        val handler = Internal.globalState!!.get(bone)

        val lock = Internal.lock!!.get(handler) as DuplexLock

        // before / current は別スレッドで書き換わるので、ロックしてコピー
        val (b, c) = lock.accessToReadLock(Supplier {
            val before = Internal.before!!.get(handler) as BoneMovement
            val current = Internal.current!!.get(handler) as BoneMovement
            BoneMovement().set(before) to BoneMovement().set(current)
        })

        // 0 = before, 1 = current
        val t = (Internal.progress!!.invoke(handler) as Float).coerceIn(0f, 1f)

        return Transform(
            position = Vector3f(b.position()).lerp(c.position(), t),
            rotation = Quaternionf(b.rotation()).slerp(c.rotation(), t).normalize(),
            scale = Vector3f(b.scale()).lerp(c.scale(), t)
        )
    }

    /** 公開APIだけで求める近似（キーフレーム間で回転が少しずれる） */
    private fun approximateTransform(bone: RenderedBone): Transform {

        val movement = BoneMovement().also { bone.worldPosition(it) }

        val chain = generateSequence(bone) { it.getParent() }
            .toList()
            .asReversed()

        val rotation = Quaternionf()

        for (b in chain) {
            val e = b.worldRotation()
            rotation.mul(
                Quaternionf().rotateZYX(
                    Math.toRadians(e.z.toDouble()).toFloat(),
                    Math.toRadians(e.y.toDouble()).toFloat(),
                    Math.toRadians(e.x.toDouble()).toFloat()
                )
            )
        }

        return Transform(
            position = Vector3f(movement.position()),
            rotation = rotation.normalize(),
            scale = Vector3f(movement.scale())
        )
    }

    // ---- 座標変換 ----

    /** モデル座標 → ワールド座標（BetterModelと同じ：ルート加算→スケール→X回転→Y回転→Entity位置） */
    fun toWorld(bone: RenderedBone, model: Vector3f, origin: Location): Vector3f {

        val r = bone.rotation()

        return Vector3f(model)
            .add(bone.getRoot().getGroup().getPosition())
            .mul(abs(bone.hitBoxScale()))
            .rotateX(-r.radianX())
            .rotateY(-r.radianY())
            .add(origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat())
    }

    /** 剣ボーンのワールドでの姿勢（ピボット位置・回転・スケール） */
    class WorldPose(
        val position: Vector3f,
        val rotation: Quaternionf,
        val scale: Vector3f
    )

    /**
     * ボーンのワールドでの姿勢。
     * ワールド座標の点 = position + rotation × (ローカル座標 × scale)
     */
    fun worldPose(bone: RenderedBone, origin: Location): WorldPose {

        val tr = transform(bone)
        val r = bone.rotation()

        // BetterModelは rotateX(-x) → rotateY(-y) の順 → qY * qX
        val model = Quaternionf()
            .rotationY(-r.radianY())
            .mul(Quaternionf().rotationX(-r.radianX()))

        return WorldPose(
            position = toWorld(bone, tr.position, origin),
            rotation = model.mul(tr.rotation).normalize(),
            scale = Vector3f(tr.scale).mul(abs(bone.hitBoxScale()))
        )
    }

    /**
     * ボーン直下のキューブの「最も長い辺」を刃とみなし、
     * 両端をボーン基準のローカル座標で返す。直下にキューブが無い場合は null。
     */
    fun bladeLocal(bone: RenderedBone): Pair<Vector3f, Vector3f>? {

        val box = bone.getGroup().getHitBox() ?: return null

        val center = box.centerPoint()

        val half = Vector3f(
            (box.x() * 0.5).toFloat(),
            (box.y() * 0.5).toFloat(),
            (box.z() * 0.5).toFloat()
        )

        val axis = when {
            half.x >= half.y && half.x >= half.z -> Vector3f(half.x, 0f, 0f)
            half.y >= half.z -> Vector3f(0f, half.y, 0f)
            else -> Vector3f(0f, 0f, half.z)
        }

        return Vector3f(center).sub(axis) to Vector3f(center).add(axis)
    }

    /** 刃の太さの目安（最長辺以外の2辺のうち大きい方の半分） */
    fun bladeRadiusOf(bone: RenderedBone): Double? {

        val box = bone.getGroup().getHitBox() ?: return null

        val sorted = doubleArrayOf(box.x(), box.y(), box.z()).sorted()

        return sorted[1] * 0.5 * abs(bone.hitBoxScale())
    }
}