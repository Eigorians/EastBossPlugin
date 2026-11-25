package com.eastcompany.eastsub.eastBossPlugin.bosses.dragon

import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Mob
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * ドラゴンの飛行。エンティティの AI と重力は切ってあり、毎tick テレポートで少しずつ動かす。
 *
 * - [steer]: 目的地へ向かって加速・減速しながら進む（近づくと止まる）
 * - 壁や天井にぶつかる所へは進まない（そのまま → 横だけ → 縦だけ → 真上、の順に試す）。
 *   すでに埋まっているときだけは、抜け出せるようにそのまま進む
 * - 向き（[yaw]）は1tickに turn 度までしか変えない（急に振り向かない）
 */
class DragonFlight(private val entity: Mob) {

    /** 今の速度（ブロック/tick） */
    var velocity = Vector()
        private set

    /** 向いている向き（度）。動かしたときにエンティティへ反映する */
    var yaw: Float = entity.location.yaw

    val position: Vector get() = entity.location.toVector()

    /** 水平の速さ（ブロック/tick） */
    val horizontalSpeed: Double get() = Math.hypot(velocity.x, velocity.z)

    // ===== 地面 =====

    /**
     * (x, z) の地面の高さ（上に立てるブロックの上面。水や溶岩の水面も地面とみなす）。
     * fromY から下へ探し、見つからなければ fromY - [MAX_DEPTH]
     */
    fun groundY(world: World, x: Double, z: Double, fromY: Double): Double {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        var y = floor(fromY).toInt()
        repeat(MAX_DEPTH) {
            if (y - 1 < world.minHeight) return world.minHeight.toDouble()
            val block = world.getBlockAt(bx, y - 1, bz)
            if (!block.isPassable || block.isLiquid) return y.toDouble()
            y--
        }
        return fromY - MAX_DEPTH
    }

    /** 今いる所の、地面からの高さ（当たり判定の底から） */
    fun altitude(): Double {
        val l = entity.location
        return l.y - groundY(l.world, l.x, l.z, l.y + 0.5)
    }

    /**
     * (x, z) の地面から height だけ上の点。地面は refY の少し上から探す（木や丘を越えるとき）
     */
    fun above(x: Double, z: Double, height: Double, refY: Double = entity.location.y): Vector {
        val g = groundY(entity.world, x, z, refY + LOOK_UP)
        return Vector(x, g + height, z)
    }

    // ===== 移動 =====

    /**
     * dest へ向かって1tick分飛ぶ。arriveRadius より近いと減速し、着いたら止まる。
     * @param maxSpeed 最高速度（ブロック/tick）
     * @param accel 1tick に変えられる速度（小さいほど重たい動き）
     */
    fun steer(dest: Vector, maxSpeed: Double, accel: Double = DEFAULT_ACCEL, arriveRadius: Double = 4.0) {
        val to = dest.clone().subtract(position)
        val dist = to.length()
        val desired = if (dist < 1e-3) Vector() else to.multiply(maxSpeed * min(1.0, dist / arriveRadius.coerceAtLeast(0.1)) / dist)
        val dv = desired.subtract(velocity)
        val len = dv.length()
        if (len > accel) dv.multiply(accel / len)
        velocity.add(dv)
        move()
    }

    /** 速度を決めて1tick分飛ぶ（急降下など、まっすぐ進むとき）。ぶつかったら false */
    fun moveWith(v: Vector): Boolean {
        velocity = v.clone()
        return move()
    }

    /** 減速しながらその場にとどまる */
    fun brake(accel: Double = DEFAULT_ACCEL) {
        val len = velocity.length()
        if (len <= accel) velocity = Vector() else velocity.multiply((len - accel) / len)
        move()
    }

    /** 止まる（速度を 0 にする。位置はそのまま） */
    fun halt() {
        velocity = Vector()
        applyRotation()
    }

    /** その場へ瞬時に移す（登場のときなど） */
    fun warp(to: Location) {
        velocity = Vector()
        teleport(to.clone())
    }

    /**
     * 速度の分だけ動く。ぶつかるなら、横だけ・縦だけ・真上へ、の順に試す。
     * @return 速度のまま進めたら true
     */
    private fun move(): Boolean {
        if (velocity.lengthSquared() < 1e-8) {
            applyRotation()
            return true
        }
        val from = entity.location
        // すでにブロックに埋まっている（天井の下で召喚されたなど）ときは、抜け出せるようにそのまま動く
        if (entity.collidesAt(from)) {
            teleport(from.clone().add(velocity))
            return true
        }
        val tries = listOf(
            velocity,
            Vector(velocity.x, 0.0, velocity.z),
            Vector(0.0, velocity.y, 0.0),
            Vector(0.0, ESCAPE_UP, 0.0),
        )
        for ((i, v) in tries.withIndex()) {
            if (v.lengthSquared() < 1e-8) continue
            val to = from.clone().add(v)
            if (entity.collidesAt(to)) continue
            if (i > 0) velocity = v.clone()
            teleport(to)
            return i == 0
        }
        velocity = Vector()
        applyRotation()
        return false
    }

    // ===== 向き =====

    /** dir の方へ、1tick に maxTurn 度まで向きを変える */
    fun turnToward(dir: Vector, maxTurn: Float) {
        if (dir.x * dir.x + dir.z * dir.z < 1e-6) return
        val target = Facing.yawOf(dir.clone().setY(0))
        yaw += wrap(target - yaw).coerceIn(-maxTurn, maxTurn)
    }

    /** point の方へ向きを変える */
    fun turnToward(point: Location, maxTurn: Float) {
        turnToward(point.toVector().subtract(position), maxTurn)
    }

    /** 進んでいる向きへ向きを変える（ほとんど止まっていれば何もしない） */
    fun turnToVelocity(maxTurn: Float) {
        if (horizontalSpeed > 0.05) turnToward(velocity, maxTurn)
    }

    /** 今の向き（水平の単位ベクトル） */
    fun forward(): Vector = Facing.dirOfYaw(yaw)

    /** 向きをエンティティ（とモデル）へ反映する。動いたときは teleport で一緒に反映される */
    fun applyRotation() {
        if (abs(wrap(entity.location.yaw - yaw)) > 0.05f) Facing.faceYaw(entity, yaw)
    }

    private fun teleport(to: Location) {
        to.yaw = yaw
        to.pitch = 0f
        entity.teleport(to)
        entity.setBodyYaw(yaw)
        entity.fallDistance = 0f
    }

    companion object {
        /** 地面を探す深さ（ブロック） */
        const val MAX_DEPTH = 48
        /** 地面を探し始める高さ（今の高さより上。丘や木を越えるため） */
        const val LOOK_UP = 10.0
        const val DEFAULT_ACCEL = 0.06
        /** どこにも動けないときに真上へ逃げる量 */
        const val ESCAPE_UP = 0.25

        /** 角度を -180〜180 に */
        fun wrap(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
