package com.eastcompany.eastsub.eastBossPlugin.location

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigFile
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.util.Vector
import kotlin.math.roundToInt

/**
 * 旅商人やボス召喚地点などの座標を locations.yml に保存・読み込みする。
 */
class LocationManager(private val server: Server, private val configManager: ConfigManager) {

    private val file: ConfigFile get() = configManager.file(Names.File.LOCATIONS)

    /** 読み込み結果 */
    sealed interface Result {
        data class Found(val location: Location) : Result
        data object NotSet : Result
        data class WorldMissing(val worldName: String) : Result
    }

    /**
     * 基準位置を自動でそろえ、オフセットを足した座標を保存する。
     * - 位置: ブロックの中心（X/Z を +0.5、Y は底面）
     * - 向き: yaw を config の location.yaw-snap 度刻みに丸め、location.reset-pitch が true なら pitch を 0 にする
     * @return 実際に保存した座標
     */
    fun set(type: PointType, base: Location, offset: Vector = Vector()): Location {
        val config = configManager.main
        val location = centerOf(base).add(offset)
        location.yaw = snapYaw(base.yaw, config.double(Names.Config.LOCATION_YAW_SNAP, 45.0))
        if (config.boolean(Names.Config.LOCATION_RESET_PITCH, true)) location.pitch = 0f
        val path = type.id
        file.set("$path.world", location.world.name)
        file.set("$path.x", location.x)
        file.set("$path.y", location.y)
        file.set("$path.z", location.z)
        file.set("$path.yaw", location.yaw.toDouble())
        file.set("$path.pitch", location.pitch.toDouble())
        file.save()
        return location
    }

    /** 保存されている座標を取得する */
    fun get(type: PointType): Result {
        val path = type.id
        if (!file.contains("$path.world")) return Result.NotSet

        val worldName = file.string("$path.world")
        val world = server.getWorld(worldName) ?: return Result.WorldMissing(worldName)
        return Result.Found(
            Location(
                world,
                file.double("$path.x"),
                file.double("$path.y"),
                file.double("$path.z"),
                file.double("$path.yaw").toFloat(),
                file.double("$path.pitch").toFloat(),
            )
        )
    }

    companion object {
        /**
         * yaw を step 度刻みの一番近い向きに丸める（結果は ±180 に正規化）。
         * step が 0 以下なら丸めずにそのまま返す。
         */
        fun snapYaw(yaw: Float, step: Double): Float {
            if (step <= 0.0) return Location.normalizeYaw(yaw)
            return Location.normalizeYaw(((yaw / step).roundToInt() * step).toFloat())
        }

        /** ブロックの中心（X/Z を +0.5、Y は底面）にそろえた座標を返す */
        fun centerOf(base: Location): Location =
            Location(base.world, base.blockX + 0.5, base.blockY.toDouble(), base.blockZ + 0.5, base.yaw, base.pitch)
    }
}
