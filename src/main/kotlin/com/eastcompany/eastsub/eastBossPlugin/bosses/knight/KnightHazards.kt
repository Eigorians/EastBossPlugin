package com.eastcompany.eastsub.eastBossPlugin.bosses.knight

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs

/**
 * 髑髏騎士が技の外へ放つもの（毎tick ボスが [tick] で進める。ボスが消えたら [clear]）。
 *
 * - 地割れ（[crack]）: 大剣を叩きつけた所から、地面に沿って前へ割れ目が走る。割れ目の先端に触れるとダメージ。
 *   壁や段差（2ブロック以上）で止まる。ジャンプでは避けられない（横へ避ける）
 * - 斬撃波（[crescent]）: 本気の薙ぎ払いが放つ、深紅の三日月。まっすぐ飛び、ブロックで消える。
 *   幅があるので横へ大きく避けるか、ジャストガードで受ける
 * - 怨霊（[wraith]）: 体から弧を描いて飛び出し、ゆっくり向きを変えながら相手を追う魂の弾。ブロックに当たると消える
 *   （物陰に隠れるか、引きつけて横へかわす。ジャストガードもできる）
 * - 怨霊の束（[volley]）: 瀕死の弾幕。弧を描いてひろがり、必ず追いついて同時に届く（ジャストガードしないと押し戻される）
 * - 雷（[lightning]）: 予告の輪が満ちると、そこに雷が落ちる（輪から出れば当たらない）
 */
class KnightHazards(private val boss: Boss) {

    private class Crack(
        var at: Location,
        val dir: Vector,
        val length: Double,
        val width: Double,
        val stepTicks: Int,
        val damage: Double,
    ) {
        var traveled = 0.0
        var age = 0
        val hit = HashSet<UUID>()
    }

    private class Crescent(
        val at: Location,
        val dir: Vector,
        val speed: Double,
        val range: Double,
        val width: Double,
        val damage: Double,
    ) {
        var traveled = 0.0
        val hit = HashSet<UUID>()
    }

    private class Wraith(
        val at: Location,
        var dir: Vector,
        var speed: Double,
        val cruise: Double,
        val turnRadians: Double,
        val target: UUID?,
        val life: Int,
        val damage: Double,
        val radius: Double,
    ) {
        var age = 0
    }

    /** 束の中の1体。bend = 弧のふくらみ（届くときは 0） */
    private class Soul(val bend: Vector, var at: Location)

    private class Volley(
        val from: Vector,
        val target: UUID,
        val souls: MutableList<Soul>,
        val flight: Int,
        val cueLead: Int,
        val damage: Double,
        val push: Double,
    ) {
        var age = 0
    }

    private class Strike(
        val at: Location,
        val warn: Int,
        val radius: Double,
        val damage: Double,
    ) {
        var age = 0
    }

    private val cracks = ArrayList<Crack>()
    private val crescents = ArrayList<Crescent>()
    private val wraiths = ArrayList<Wraith>()
    private val strikes = ArrayList<Strike>()
    private val volleys = ArrayList<Volley>()

    /** 地割れを走らせる（from から dir の向きへ length ブロック。stepTicks ごとに1ブロック進む） */
    fun crack(from: Location, dir: Vector, length: Double, width: Double, stepTicks: Int, damage: Double) {
        val d = dir.clone().setY(0)
        if (d.lengthSquared() < 1e-6 || length <= 0) return
        cracks += Crack(from.clone(), d.normalize(), length, width, stepTicks.coerceAtLeast(1), damage)
    }

    /** 斬撃波を放つ（from の高さから、dir の向きへ） */
    fun crescent(from: Location, dir: Vector, speed: Double, range: Double, width: Double, damage: Double) {
        val d = dir.clone().setY(0)
        if (d.lengthSquared() < 1e-6 || range <= 0) return
        crescents += Crescent(from.clone(), d.normalize(), speed.coerceAtLeast(0.1), range, width, damage)
        KnightSounds.crescentRelease(from)
    }

    /**
     * 怨霊を放つ。launch（向き × 速さ）で飛び出し、だんだん cruise の速さへ落ち着きながら、
     * 1tick に turnDegrees ずつ target の方へ向きを変える。life tick で消える
     */
    fun wraith(from: Location, launch: Vector, target: LivingEntity?, cruise: Double, turnDegrees: Double,
               life: Int, damage: Double, radius: Double) {
        val speed = launch.length()
        if (speed < 1e-4) return
        wraiths += Wraith(from.clone(), launch.clone().normalize(), speed, cruise, Math.toRadians(turnDegrees),
            target?.uniqueId, life, damage, radius)
    }

    /**
     * 怨霊の束を放つ。count 体が扇にふくらんだ弧を描き、flight tick 後に target の所へ同時に届く（当たりは束で1回）。
     * 相手が動いても届く。壁に当たった怨霊だけ消え、全部消えれば当たらない。
     * 届く cueLead tick 前に相手へ合図の音。ジャストガードされなければ、push の速さでボスと逆へ押し戻す
     */
    fun volley(from: Location, target: Player, count: Int, flight: Int, cueLead: Int, damage: Double, push: Double) {
        val toward = target.location.toVector().subtract(from.toVector()).setY(0)
        val length = toward.length()
        val dir = if (length > 1e-4) toward.multiply(1 / length) else Vector(1, 0, 0)
        val side = Vector(-dir.z, 0.0, dir.x)
        val width = (length * 0.22).coerceIn(1.5, 6.0)
        val souls = (0 until count).mapTo(ArrayList<Soul>()) { i ->
            // 左端 → 頭上 → 右端の半円に並べる。真ん中の1体はほぼまっすぐ
            val f = if (count == 1) 0.0 else 2.0 * i / (count - 1) - 1.0
            val up = if (f == 0.0) 0.3 else (1.0 - abs(f)) * width * 0.7 + 0.5
            Soul(side.clone().multiply(f * width).setY(up), from.clone())
        }
        volleys += Volley(from.toVector(), target.uniqueId, souls, flight.coerceAtLeast(1), cueLead, damage, push)
    }

    /** 雷を予約する（warn tick の間、予告の輪を見せてから落ちる） */
    fun lightning(at: Location, warn: Int, radius: Double, damage: Double) {
        strikes += Strike(at.clone(), warn.coerceAtLeast(1), radius, damage)
        KnightSounds.lightningWarn(at)
    }

    fun tick() {
        // ダメージを与えた拍子に（とげの反撃でボスが倒れて clear されるなど）リストが変わることがあるので、写しを回す
        for (c in cracks.toList()) if (!stepCrack(c)) cracks.remove(c)
        for (w in crescents.toList()) if (!stepCrescent(w)) crescents.remove(w)
        for (w in wraiths.toList()) if (!stepWraith(w)) wraiths.remove(w)
        for (v in volleys.toList()) if (!stepVolley(v)) volleys.remove(v)
        for (st in strikes.toList()) if (!stepStrike(st)) strikes.remove(st)
    }

    fun clear() {
        cracks.clear()
        crescents.clear()
        wraiths.clear()
        strikes.clear()
        for (v in volleys.toList()) v.souls.forEach { KnightFx.wraithFade(it.at) }
        volleys.clear()
    }

    // ===== 怨霊 =====

    private fun stepWraith(w: Wraith): Boolean {
        w.age++
        if (w.age > w.life) {
            KnightFx.wraithFade(w.at)
            return false
        }
        val world = w.at.world
        // 相手の胸のあたりへ向きを少しずつ変える（球面上で回す）
        val t = w.target?.let { boss.plugin.server.getEntity(it) } as? LivingEntity
        if (t != null && t.world == world && TargetFinder.isValidTarget(boss, t)) {
            val want = t.location.toVector().add(Vector(0.0, t.height * 0.55, 0.0)).subtract(w.at.toVector())
            if (want.lengthSquared() > 1e-4) {
                want.normalize()
                val cos = w.dir.dot(want).coerceIn(-1.0, 1.0)
                val angle = Math.acos(cos)
                w.dir = if (angle <= w.turnRadians || angle < 1e-4) want else {
                    val s = Math.sin(angle)
                    w.dir.clone().multiply(Math.sin(angle - w.turnRadians) / s).add(want.multiply(Math.sin(w.turnRadians) / s)).normalize()
                }
            }
        }
        w.speed += (w.cruise - w.speed) * 0.07
        val steps = Math.ceil(w.speed / 0.4).toInt().coerceAtLeast(1)
        repeat(steps) {
            w.at.add(w.dir.clone().multiply(w.speed / steps))
            if (!w.at.block.isPassable) {
                KnightFx.wraithBurst(w.at)
                KnightSounds.wraithBurst(w.at)
                return false
            }
            if (hitWraith(w)) return false
        }
        KnightFx.wraith(w.at, w.dir, w.age)
        if (w.age % 16 == 0) KnightSounds.wraithWail(w.at)
        return true
    }

    private fun hitWraith(w: Wraith): Boolean {
        val r = w.radius + 1.5
        for (e in w.at.world.getNearbyEntities(w.at, r, r + 1.0, r)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(boss, e)) continue
            val c = e.location.toVector().add(Vector(0.0, e.height * 0.5, 0.0))
            val dy = Math.abs(w.at.y - c.y)
            val dxz = Math.hypot(w.at.x - c.x, w.at.z - c.z)
            if (dxz > w.radius + e.width / 2 || dy > w.radius + e.height / 2) continue
            damage(e, w.damage, w.dir)
            KnightFx.wraithBurst(w.at)
            KnightSounds.wraithBurst(w.at)
            return true
        }
        return false
    }

    // ===== 怨霊の束 =====

    private fun stepVolley(v: Volley): Boolean {
        v.age++
        val t = boss.plugin.server.getPlayer(v.target)
        if (t == null || !TargetFinder.isValidTarget(boss, t) || t.world != boss.entity.world) {
            v.souls.forEach { KnightFx.wraithFade(it.at) }
            return false
        }
        val f = v.age.toDouble() / v.flight
        val goal = t.location.toVector().add(Vector(0.0, t.height * 0.55, 0.0))
        // 今の相手の位置へ向かう線に、弧のふくらみを足す（f = 1 で必ず相手の胸に重なる）
        val line = v.from.clone().add(goal.subtract(v.from).multiply(f))
        val swell = Math.sin(Math.PI * f)
        val iter = v.souls.iterator()
        while (iter.hasNext()) {
            val soul = iter.next()
            val move = line.clone().add(soul.bend.clone().multiply(swell)).subtract(soul.at.toVector())
            val steps = Math.ceil(move.length() / 0.5).toInt().coerceAtLeast(1)
            val step = move.clone().multiply(1.0 / steps)
            var blocked = false
            for (i in 0 until steps) {
                soul.at.add(step)
                if (!soul.at.block.isPassable) {
                    blocked = true
                    break
                }
            }
            if (blocked) {
                KnightFx.wraithBurst(soul.at)
                iter.remove()
            } else if (move.lengthSquared() > 1e-6) {
                KnightFx.wraith(soul.at, move.normalize(), v.age)
            }
        }
        if (v.souls.isEmpty()) return false
        if (v.flight - v.age == v.cueLead) {
            KnightSounds.volleyCue(t)
            KnightFx.volleyCue(t)
        }
        if (v.age < v.flight) return true

        val at = v.souls[0].at
        KnightFx.wraithBurst(at)
        KnightSounds.wraithBurst(at)
        t.noDamageTicks = 0
        t.damage(v.damage, boss.entity)
        // 普通のガードでも押し戻す。ジャストガードしたときだけ、その場に踏みとどまれる
        if (!t.isDead && !boss.status.justGuard.guardedNow(t)) {
            val away = Facing.horizontalDir(boss.entity, t) ?: Facing.forward(boss.entity)
            t.velocity = away.multiply(v.push).setY(0.3)
        }
        return false
    }

    // ===== 雷 =====

    private fun stepStrike(st: Strike): Boolean {
        st.age++
        if (st.age < st.warn) {
            if (st.age % 2 == 0) KnightFx.lightningWarn(st.at, st.radius, st.age.toDouble() / st.warn)
            return true
        }
        val world = st.at.world
        world.strikeLightningEffect(st.at)
        KnightFx.lightningImpact(st.at, st.radius)
        val r = st.radius
        for (e in world.getNearbyEntities(st.at, r + 1.0, 4.0, r + 1.0)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(boss, e)) continue
            val v = Vector(e.location.x - st.at.x, 0.0, e.location.z - st.at.z)
            val dy = e.location.y - st.at.y
            if (v.length() > r + e.width / 2 || dy < -1.5 || dy > 3.5) continue
            damage(e, st.damage, if (v.lengthSquared() > 1e-4) v else Vector(1, 0, 0))
        }
        return false
    }

    // ===== 地割れ =====

    /** @return まだ続くなら true */
    private fun stepCrack(c: Crack): Boolean {
        c.age++
        if (c.age % c.stepTicks != 0) {
            KnightFx.crackGlow(c.at)
            return true
        }
        if (c.traveled >= c.length) return false
        val next = c.at.clone().add(c.dir)
        // 地面に沿う。高すぎる段差・壁で止まる
        val ground = Ground.groundY(next.world, next.x, next.y + 1.5, next.z, 4)
        if (abs(ground - c.at.y) > 1.6) return false
        next.y = ground
        if (!Ground.isFree(next)) return false
        c.at = next
        c.traveled += 1.0
        KnightFx.crackBurst(next, c.width)
        if (c.traveled.toInt() % 2 == 0) KnightSounds.crack(next)
        hitAround(next, c.width / 2 + 0.3, 2.2, c.damage, c.hit, push = c.dir)
        return true
    }

    // ===== 斬撃波 =====

    private fun stepCrescent(w: Crescent): Boolean {
        if (w.traveled >= w.range) return false
        val steps = Math.ceil(w.speed / 0.5).toInt().coerceAtLeast(1)
        val step = w.speed / steps
        repeat(steps) {
            w.at.add(w.dir.clone().multiply(step))
            w.traveled += step
            // 真ん中が壁に当たったら消える
            if (!w.at.block.isPassable) {
                KnightFx.crescentBreak(w.at)
                return false
            }
            hitCrescent(w)
        }
        KnightFx.crescent(w.at, w.dir, w.width, w.traveled / w.range)
        return true
    }

    private fun hitCrescent(w: Crescent) {
        val side = Vector(-w.dir.z, 0.0, w.dir.x)
        val r = w.width / 2 + 1.0
        for (e in w.at.world.getNearbyEntities(w.at, r, 2.5, r)) {
            if (e !is LivingEntity || e.uniqueId in w.hit || !TargetFinder.isValidTarget(boss, e)) continue
            val v = e.location.toVector().subtract(w.at.toVector())
            val forward = v.dot(w.dir)
            val lateral = v.dot(side)
            val dy = e.location.y - w.at.y
            if (abs(forward) > 0.9 + e.width / 2 || abs(lateral) > w.width / 2 + e.width / 2) continue
            if (dy < -2.0 || dy > 1.2) continue
            w.hit += e.uniqueId
            damage(e, w.damage, w.dir)
        }
    }

    // ===== 共通 =====

    private fun hitAround(at: Location, radius: Double, height: Double, damage: Double, hit: MutableSet<UUID>, push: Vector) {
        for (e in at.world.getNearbyEntities(at, radius + 1.0, height, radius + 1.0)) {
            if (e !is LivingEntity || e.uniqueId in hit || !TargetFinder.isValidTarget(boss, e)) continue
            val dx = e.location.x - at.x
            val dz = e.location.z - at.z
            val dy = e.location.y - at.y
            if (dx * dx + dz * dz > (radius + e.width / 2) * (radius + e.width / 2) || dy < -1.0 || dy > height) continue
            hit += e.uniqueId
            damage(e, damage, push)
        }
    }

    private fun damage(e: LivingEntity, amount: Double, push: Vector) {
        e.noDamageTicks = 0
        e.damage(amount, boss.entity)
        if (e is org.bukkit.entity.Player && boss.status.justGuard.guardedNow(e)) return
        val flat = push.clone().setY(0)
        // 真上・真下から当たったとき（怨霊が上から落ちてきたなど）は、横へは押さない
        e.velocity = (if (flat.lengthSquared() > 1e-4) flat.normalize().multiply(0.5) else Vector()).setY(0.35)
    }
}
