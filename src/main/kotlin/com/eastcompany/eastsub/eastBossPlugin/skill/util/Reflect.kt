package com.eastcompany.eastsub.eastBossPlugin.skill.util

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.status.JustGuardFx
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 弾き返し: 飛んでくる弾を、ジャストガードで弾き返す（ドラゴンの火球・アルファの花びらの玉など）。
 *
 * 弾ごとに毎tick [tryReflect] を呼び、弾き返した人が返ってきたら、弾をボスへ向けて飛ばし直す（飛ばし方は弾ごと）。
 * 弾き返せるのは、弾が radius 以内まで来たときに、ジャストガードの受付の間（盾を構えてから just-guard.window-ticks）で、
 * 盾を持って弾かボスの方を向いている相手だけ（弾が 1 ブロック以内なら向きは問わない）。弾き返すとジャストガードとして数える（Boss.onJustGuard も呼ぶ）
 */
object Reflect {

    /** pos にある弾を弾き返した人（いなければ null） */
    fun tryReflect(boss: Boss, pos: Location, radius: Double): Player? {
        val limit = boss.plugin.configManager.main.double(Names.Config.JUST_GUARD_FACING_DEGREES, 90.0)
        for (p in pos.world.getNearbyPlayers(pos, radius)) {
            if (!TargetFinder.isValidTarget(boss, p)) continue
            val inv = p.inventory
            if (inv.itemInMainHand.type != Material.SHIELD && inv.itemInOffHand.type != Material.SHIELD) continue
            if (p.hasCooldown(Material.SHIELD)) continue
            // 弾の方かボスの方を向いている（すぐそば・真上・真下から来るときは向きを問わない）。
            // 至近距離では弾が体の横や頭の上に来るので、ボスを見て構えていれば弾き返せるようにする
            val look = p.location.direction.setY(0)
            val toBall = pos.toVector().subtract(p.location.toVector()).setY(0)
            val toBoss = boss.entity.location.toVector().subtract(p.location.toVector()).setY(0)
            val facing = { to: org.bukkit.util.Vector -> to.lengthSquared() < 1e-6 || Math.toDegrees(look.angle(to).toDouble()) <= limit }
            if (look.lengthSquared() > 1e-6 && toBall.lengthSquared() > CLOSE * CLOSE && !facing(toBall) && !facing(toBoss)) continue
            if (!boss.status.justGuard.tryGuard(p, boss.status.justGuardWindowTicks)) continue
            JustGuardFx.play(p, pos, boss.plugin.messages.component(Names.Message.JUST_GUARD_REFLECT))
            boss.onJustGuard(p)
            boss.onReflect(p)
            return p
        }
        return null
    }

    /** 弾がこれより近い（水平・ブロック）ときは、向きを問わない */
    private const val CLOSE = 1.0
}
