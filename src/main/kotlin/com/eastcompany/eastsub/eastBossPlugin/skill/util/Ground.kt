package com.eastcompany.eastsub.eastBossPlugin.skill.util

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.Vector

/** 地面に沿った移動（技でボスを少しずつ動かすとき用） */
object Ground {

    /** 足元と頭の高さ（2ブロック）が空いているか */
    fun isFree(at: Location): Boolean =
        at.block.isPassable && at.clone().add(0.0, 1.0, 0.0).block.isPassable

    /**
     * 進んだ先の足元を地面に合わせる。1段の段差は登り、maxDrop 段までの下りは降りる。
     * 壁などで進めなければ null
     */
    fun step(to: Location, maxDrop: Int = 3): Location? {
        val dest = to.clone()
        if (!isFree(dest)) {
            dest.add(0.0, 1.0, 0.0)
            dest.y = dest.blockY.toDouble()
            return if (isFree(dest)) dest else null
        }
        repeat(maxDrop) {
            val below = dest.clone().add(0.0, -1.0, 0.0)
            if (!below.block.isPassable) return dest
            dest.add(0.0, -1.0, 0.0)
        }
        return dest
    }

    /**
     * from から水平の dir へ、地面に沿って（[step] で）どこまで進めるか（ブロック）。maxDistance で打ち切る。
     * 壁を背にしているか・どちらが開けているかを調べるのに使う
     */
    fun freeLength(from: Location, dir: Vector, maxDistance: Double, stepLength: Double = 0.5): Double {
        val d = dir.clone().setY(0)
        if (d.lengthSquared() < 1e-6) return 0.0
        d.normalize().multiply(stepLength)
        var at = from.clone()
        var length = 0.0
        while (length < maxDistance) {
            at = step(at.clone().add(d)) ?: break
            length += stepLength
        }
        return length
    }

    /**
     * (x, z) の地面の高さ（上に立てるブロックの上面）。startY から下へ maxDepth ブロック探す。
     * 見つからなければ startY
     */
    fun groundY(world: World, x: Double, startY: Double, z: Double, maxDepth: Int = 8): Double {
        val bx = Math.floor(x).toInt()
        val bz = Math.floor(z).toInt()
        var y = Math.floor(startY).toInt()
        // 埋まっていたら上へ
        var up = 0
        while (!world.getBlockAt(bx, y, bz).isPassable && up < 4) {
            y++
            up++
        }
        for (i in 0 until maxDepth) {
            if (!world.getBlockAt(bx, y - 1, bz).isPassable) return y.toDouble()
            y--
        }
        return startY
    }
}
