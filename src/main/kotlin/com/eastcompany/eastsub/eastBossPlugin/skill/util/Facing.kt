package com.eastcompany.eastsub.eastBossPlugin.skill.util

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** ボスの向き・水平方向の計算 */
object Facing {

    /** from → to の水平方向の単位ベクトル。ほぼ同じ位置なら null */
    fun horizontalDir(from: Vector, to: Vector): Vector? {
        val v = to.clone().subtract(from).setY(0.0)
        return if (v.lengthSquared() < 1.0E-4) null else v.normalize()
    }

    fun horizontalDir(from: Entity, to: Entity): Vector? =
        horizontalDir(from.location.toVector(), to.location.toVector())

    /** エンティティの向いている水平方向（真上・真下を向いているときは +Z） */
    fun forward(entity: Entity): Vector {
        val v = entity.location.direction.setY(0.0)
        return if (v.lengthSquared() < 1.0E-4) Vector(0, 0, 1) else v.normalize()
    }

    /** 指定した水平方向を向かせる（上下は水平に戻す） */
    fun face(entity: Entity, dir: Vector) {
        val loc = entity.location
        loc.direction = dir
        loc.pitch = 0f
        entity.teleport(loc)
    }

    /**
     * 指定した yaw（度）を向かせる。体の向きも合わせるので、モデルもすぐその向きになる。
     * 毎tick回すような用途向け（テレポートしない）
     */
    fun faceYaw(entity: Entity, yaw: Float) {
        entity.setRotation(yaw, 0f)
        if (entity is LivingEntity) entity.setBodyYaw(yaw)
    }

    /** 水平方向のベクトルの yaw（度）。[dirOfYaw] の逆 */
    fun yawOf(dir: Vector): Float = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat()

    /** yaw（度）の水平方向の単位ベクトル */
    fun dirOfYaw(yaw: Float): Vector {
        val r = Math.toRadians(yaw.toDouble())
        return Vector(-sin(r), 0.0, cos(r))
    }

    /**
     * 標的の方を向かせる。標的がもう狙えない（死亡・離脱など）場合は何もしない。
     * @return 向きを変えたら、その方向。変えなかったら null
     */
    fun faceTarget(boss: Boss, target: Entity?): Vector? {
        val t = target ?: return null
        if (!TargetFinder.isValidTarget(boss, t)) return null
        val dir = horizontalDir(boss.entity, t) ?: return null
        face(boss.entity, dir)
        return dir
    }
}
