package com.eastcompany.eastsub.eastBossPlugin.bosses.giant.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.giant.GiantBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.giant.GiantFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.giant.GiantMove
import com.eastcompany.eastsub.eastBossPlugin.bosses.giant.GiantSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 巨人の連携技: 動き（[GiantMove]）を順に出す。最後の動きの後は、長い隙（疲れ）がある。
 *
 *   動き1 → chainGapTicks → 動き2 → … → 隙（recoverTicks。受けるダメージが recoverDamageMultiplier 倍）→ 立ち直る
 *
 * どの動きも溜めが長く、地面に範囲の予告が出る（予告が満ちた瞬間に当たる）。
 * 連携の途中は止まらないので、1つかわしても次が来る。隙は連携が終わってから。
 *
 * 連携の長さをランダムにするとき（chain-pool がある）は、毎回 chain-min 〜 chain-max 個の動きをつなぐ。
 * moves を頭から使い、足りない分は chain-pool からランダムに足す（同じ動きは3回続けない）。
 *
 * ブロックを積んで登った相手にも届く: どの動きも上へ hit-height までの柱で当たり、予告はその相手の高さにも出る。
 * 岩投げで始まる技は、高い所の相手を優先して、その高さへ直接投げる。
 *
 * @param opening 連携の動き（ボスの yml の moves から読んだもの。ランダムのときは頭の部分）
 * @param pool ランダムに足す動きの候補（空ならランダムにしない）
 */
class GiantComboSkill(
    override val id: String,
    private val settings: Settings,
    private val opening: List<GiantMove>,
    private val pool: List<GiantMove> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val minRange: Double,
        val chainGapTicks: Int,
        val recoverAnimation: String,
        val recoverTicks: Int,
        val recoverEndAnimation: String,
        val recoverEndTicks: Int,
        val recoverDamageMultiplier: Double,
        /** ランダムの連携の長さ（動きの数） */
        val chainMin: Int,
        val chainMax: Int,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings = Settings(
                weight = section.int(Names.SkillConfig.WEIGHT, 1),
                range = section.double(Names.SkillConfig.RANGE, 10.0),
                minRange = section.double(Names.SkillConfig.MIN_RANGE, 0.0),
                chainGapTicks = section.int(Names.SkillConfig.CHAIN_GAP_TICKS, 2).coerceAtLeast(0),
                recoverAnimation = section.string(Names.SkillConfig.RECOVER_ANIMATION, "tired"),
                recoverTicks = section.int(Names.SkillConfig.RECOVER_TICKS, 50),
                recoverEndAnimation = section.string(Names.SkillConfig.RECOVER_END_ANIMATION, "tired_end"),
                recoverEndTicks = section.int(Names.SkillConfig.RECOVER_END_TICKS, 16),
                recoverDamageMultiplier = section.double(Names.SkillConfig.RECOVER_DAMAGE_MULTIPLIER, 1.3),
                chainMin = section.int(Names.SkillConfig.CHAIN_MIN, 2).coerceAtLeast(1),
                chainMax = section.int(Names.SkillConfig.CHAIN_MAX, 4).coerceAtLeast(1),
            )
        }
    }

    private enum class Phase { MOVE, GAP, RECOVER, RECOVER_END }

    /** 投げた岩 */
    private class Boulder(val display: BlockDisplay, val from: Location, val to: Location, val move: GiantMove) {
        var age = 0
    }

    override val weight: Int get() = settings.weight

    private var phase = Phase.MOVE
    private var tick = 0
    private var index = 0
    private var target: LivingEntity? = null

    // 今の動きの狙い（aimLockTick で固まる）
    private var yaw = 0f
    private var locked = false
    private var origin: Location? = null
    private var aimPoint: Location? = null
    private var leapFrom: Location? = null
    private val hit = HashSet<UUID>()
    /** 大地割りで、もう割れた区間の数 */
    private var crackedSegments = 0
    private val boulders = mutableListOf<Boulder>()

    /** 今回の連携の動き（始めるたびに決める） */
    private var moves: List<GiantMove> = opening

    private val move: GiantMove get() = moves[index]

    override fun canStart(boss: Boss): Boolean = opening.isNotEmpty() && findTarget(boss) != null

    /** 今回つなぐ動きを決める（ランダムでなければ moves のまま） */
    private fun rollMoves(): List<GiantMove> {
        if (pool.isEmpty()) return opening
        val lo = minOf(settings.chainMin, settings.chainMax)
        val hi = maxOf(settings.chainMin, settings.chainMax)
        val n = Random.nextInt(lo, hi + 1)
        val out = opening.take(n).toMutableList()
        while (out.size < n) {
            val last2 = out.takeLast(2)
            val candidates = pool.filter { c -> !(last2.size == 2 && last2.all { it.id == c.id }) }.ifEmpty { pool }
            out += candidates.random()
        }
        return out
    }

    /**
     * 始める相手。岩投げは高い所の相手（近くても・いくら高くても）を優先する。
     * ほかの技は、最初の動きが届く高さの相手から選ぶ（届かない相手に空振りし続けない）
     */
    private fun findTarget(boss: Boss): LivingEntity? {
        val g = boss as GiantBoss
        val first = opening.first()
        val here = g.entity.location
        val t = if (first.shape == GiantMove.Shape.THROW) {
            val high = TargetFinder.findNearest(g, settings.range, verticalRange = g.targetMaxHeight) { g.isHigh(it) }
            if (high != null) return high
            TargetFinder.findNearest(g, settings.range)
        } else {
            TargetFinder.findNearest(g, settings.range) { it.location.y - here.y <= first.hitHeight }
        } ?: return null
        return if (t.location.distanceSquared(here) < settings.minRange * settings.minRange) null else t
    }

    override fun start(boss: Boss) {
        val g = boss as GiantBoss
        index = 0
        moves = rollMoves()
        target = findTarget(g)
        boulders.clear()
        beginMove(g)
        // beginMove は動きの途中から呼ぶ前提で tick を -1 にするので、最初だけ 0 に戻す
        tick = 0
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GiantBoss
        tickBoulders(g)
        val done = when (phase) {
            Phase.MOVE -> tickMove(g)
            Phase.GAP -> {
                if (tick >= settings.chainGapTicks - 1) {
                    index++
                    beginMove(g)
                }
                false
            }
            Phase.RECOVER -> tickRecover(g)
            Phase.RECOVER_END -> tick >= settings.recoverEndTicks - 1 && boulders.isEmpty()
        }
        tick++
        return done
    }

    override fun stop(boss: Boss) {
        val g = boss as GiantBoss
        boulders.forEach { it.display.remove() }
        boulders.clear()
        g.floating = false
        g.setPartVisible(GiantBoss.BOULDER_BONE, false)
        g.stopAnimation(settings.recoverAnimation)
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (phase == Phase.RECOVER || phase == Phase.RECOVER_END) event.damage *= settings.recoverDamageMultiplier
    }

    // ===== 動き =====

    private fun beginMove(g: GiantBoss) {
        phase = Phase.MOVE
        tick = -1   // tick() の最後で 0 になる
        locked = false
        origin = null
        aimPoint = null
        leapFrom = null
        hit.clear()
        crackedSegments = 0
        yaw = g.entity.location.yaw
        g.playOnce(move.animation, move.animationSpeed)
        GiantSounds.windup(g.entity)
    }

    private fun currentTarget(g: GiantBoss): LivingEntity? {
        val t = target?.takeIf { TargetFinder.isTargetable(g, it) } ?: TargetFinder.findNearest(g, settings.range * 2, verticalRange = g.targetMaxHeight)
        target = t
        return t
    }

    private fun tickMove(g: GiantBoss): Boolean {
        val m = move
        val t = currentTarget(g)

        // 狙いを固めるまでは、ゆっくり振り向く
        if (!locked) {
            if (t != null) turnToward(g, t.location, m.turnSpeed)
            if (tick >= m.lockTick) lock(g, t)
        }

        // 予告
        if (tick >= m.warnStartTick && tick < m.hitTick && tick % 2 == 0) drawWarning(g, t)

        // 岩投げ: つかむ → 投げる
        if (m.shape == GiantMove.Shape.THROW) {
            if (tick == m.grabTick) {
                g.setPartVisible(GiantBoss.BOULDER_BONE, true)
                GiantSounds.grab(g.entity)
            }
            if (tick == m.releaseTick) throwBoulder(g, m)
        }
        // 大跳躍: 跳んでいる間は位置を動かす
        if (m.shape == GiantMove.Shape.LEAP && tick in m.takeoffTick until m.impactTick) leapStep(g, m)

        if (tick == m.hitTick - 4 && m.shape != GiantMove.Shape.THROW) GiantSounds.whoosh(g.entity)
        if (tick == m.hitTick && m.shape != GiantMove.Shape.THROW) impact(g, m)
        if (m.shape == GiantMove.Shape.LINE && tick >= m.hitTick) crackStep(g, m)

        if (tick < m.ticks - 1) return false
        // 次の動きへ。最後なら隙
        if (index + 1 < moves.size) {
            phase = Phase.GAP
            tick = -1
            return false
        }
        phase = Phase.RECOVER
        tick = -1
        g.playLoop(settings.recoverAnimation, lerpTicks = 4)
        return false
    }

    private fun turnToward(g: GiantBoss, at: Location, maxTurn: Double) {
        val dir = Facing.horizontalDir(g.entity.location.toVector(), at.toVector()) ?: return
        val want = Facing.yawOf(dir)
        var diff = (want - yaw) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        yaw += diff.coerceIn(-maxTurn.toFloat(), maxTurn.toFloat())
        Facing.faceYaw(g.entity, yaw)
    }

    private fun facing(): Vector = Facing.dirOfYaw(yaw)

    /** 狙いを固める（範囲の位置・投げる先・跳ぶ先） */
    private fun lock(g: GiantBoss, t: LivingEntity?) {
        locked = true
        val m = move
        origin = g.entity.location.clone()
        when (m.shape) {
            GiantMove.Shape.THROW -> aimPoint = t?.let { predicted(g, it, m.flightTicks * m.leadRatio) } ?: frontPoint(g, 10.0)
            GiantMove.Shape.LEAP -> {
                leapFrom = g.entity.location.clone()
                val raw = t?.location ?: frontPoint(g, m.maxDistance * 0.6)
                val v = Vector(raw.x - origin!!.x, 0.0, raw.z - origin!!.z)
                if (v.length() > m.maxDistance) v.normalize().multiply(m.maxDistance)
                val free = Ground.freeLength(origin!!, v, v.length())
                val p = origin!!.clone().add(if (v.lengthSquared() > 1e-6) v.clone().normalize().multiply(free) else Vector())
                p.y = Ground.groundY(p.world, p.x, p.y + 6, p.z, 16)
                aimPoint = p
                GiantSounds.leap(g.entity)
                g.floating = true
                GiantFx.groundBurst(origin!!, 3.0)
            }
            else -> {}
        }
    }

    /** 標的の今の位置から、動き（向き）を ticks 分見越した地面の点。高い所の標的なら、その高さの点 */
    private fun predicted(g: GiantBoss, t: LivingEntity, ticks: Double): Location {
        val p = t.location.add(g.velocityOf(t).multiply(ticks))
        // 高い所では地面を探さない（見越した先や足元が足場から外れていると、下の地面に落ちてしまう）
        if (!g.isHigh(t)) p.y = Ground.groundY(p.world, p.x, t.location.y + 2, p.z, 8)
        return p
    }

    private fun frontPoint(g: GiantBoss, distance: Double): Location {
        val p = g.entity.location.add(facing().multiply(distance))
        p.y = Ground.groundY(p.world, p.x, p.y + 2, p.z, 8)
        return p
    }

    /** 今の動きの範囲の中心（円）・起点（扇・帯） */
    private fun center(g: GiantBoss): Location {
        val m = move
        return when (m.shape) {
            GiantMove.Shape.THROW, GiantMove.Shape.LEAP -> aimPoint ?: frontPoint(g, 6.0)
            GiantMove.Shape.CIRCLE -> {
                val from = origin ?: g.entity.location
                val p = from.clone().add(facing().multiply(m.forward))
                p.y = Ground.groundY(p.world, p.x, from.y + 2, p.z, 6)
                p
            }
            else -> origin ?: g.entity.location
        }
    }

    private fun drawWarning(g: GiantBoss, t: LivingEntity?) {
        val m = move
        val progress = ((tick - m.warnStartTick).toDouble() / (m.hitTick - m.warnStartTick).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        if (m.shape == GiantMove.Shape.THROW) {
            // 投げるまでは標的の足元を追い、投げた後は着く所に固まる
            val c = aimPoint ?: t?.let { predicted(g, it, m.flightTicks * m.leadRatio) } ?: return
            GiantFx.warnCircle(c, m.radius, progress)
            return
        }
        val c = center(g)
        drawShape(m, c, progress)
        // 積んで登った人には地面の予告が見えないので、届く高さならその人の足元の高さにも同じ形を出す
        for (layer in highLayers(g, m, c)) drawShape(m, c.clone().apply { y = layer }, progress)
    }

    private fun drawShape(m: GiantMove, c: Location, progress: Double) {
        when (m.shape) {
            GiantMove.Shape.CIRCLE, GiantMove.Shape.LEAP -> GiantFx.warnCircle(c, m.radius, progress)
            GiantMove.Shape.CONE -> GiantFx.warnCone(c, facing(), m.radius, m.coneDegrees, progress)
            GiantMove.Shape.LINE -> GiantFx.warnLine(c, facing(), m.length, m.width, progress)
            GiantMove.Shape.THROW -> {}
        }
    }

    /** 予告を重ねる高さ（範囲の近くで、届く高さにいるプレイヤーの足元。粒子が増えすぎないよう数段まで） */
    private fun highLayers(g: GiantBoss, m: GiantMove, c: Location): List<Double> {
        val reach = if (m.shape == GiantMove.Shape.LINE) m.length else m.radius
        val heights = hitHeights(g, m, c)
        val out = mutableListOf<Double>()
        for (p in c.world.players) {
            val at = p.location
            if (at.y - c.y < g.airHeight || at.y !in heights) continue
            if (horizontalDistance(at, c) > reach + WARN_MARGIN) continue
            if (!TargetFinder.isValidTarget(g, p) || out.any { abs(it - at.y) < LAYER_GAP }) continue
            out += at.y
            if (out.size >= MAX_LAYERS) break
        }
        return out
    }

    /** 当たる高さ（Y）。動きの起点と範囲の中心の、低い方の少し下から、高い方の hit-height 上まで */
    private fun hitHeights(g: GiantBoss, m: GiantMove, c: Location): ClosedFloatingPointRange<Double> {
        val base = (leapFrom ?: origin ?: g.entity.location).y
        return (minOf(c.y, base) - HIT_DEPTH)..(maxOf(c.y, base) + m.hitHeight)
    }

    // ===== 当たる =====

    private fun impact(g: GiantBoss, m: GiantMove) {
        val c = center(g)
        GiantSounds.impact(c)
        when (m.shape) {
            GiantMove.Shape.CIRCLE, GiantMove.Shape.LEAP -> {
                if (m.shape == GiantMove.Shape.LEAP) land(g)
                GiantFx.groundBurst(c, m.radius)
                damageIn(g, m, c) { e -> horizontalDistance(e.location, c) <= m.radius + e.width / 2 }
            }
            GiantMove.Shape.CONE -> {
                val f = facing()
                val cosLimit = Math.cos(Math.toRadians(m.coneDegrees / 2))
                c.world.spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(f.clone().multiply(m.radius * 0.6)).add(0.0, 1.5, 0.0), 8, m.radius * 0.3, 0.3, m.radius * 0.3, 0.0)
                damageIn(g, m, c) { e ->
                    val v = Vector(e.location.x - c.x, 0.0, e.location.z - c.z)
                    val d = v.length()
                    d <= m.radius + e.width / 2 && (d < 2.0 || v.multiply(1 / d).dot(f) >= cosLimit)
                }
            }
            GiantMove.Shape.LINE -> GiantFx.groundBurst(c.clone().add(facing().multiply(2.0)), 3.0)
            GiantMove.Shape.THROW -> {}
        }
        GiantFx.shake(g, c, m.shakeRadius)
    }

    /** 大地割り: 手前から奥へ、区間ごとに地面が割れて突き上げる */
    private fun crackStep(g: GiantBoss, m: GiantMove) {
        val from = origin ?: return
        val f = facing()
        val segLen = 1.5
        val total = (m.length / segLen).toInt()
        val reach = if (m.waveSpeed <= 0) m.length else (tick - m.hitTick + 1) * m.waveSpeed
        val upTo = (reach / segLen).toInt().coerceAtMost(total)
        while (crackedSegments < upTo) {
            val d = (crackedSegments + 0.5) * segLen
            val at = from.clone().add(f.clone().multiply(d))
            at.y = Ground.groundY(at.world, at.x, from.y + 3, at.z, 8)
            GiantFx.groundBurst(at, m.width / 2)
            at.world.spawnParticle(Particle.LAVA, at, 2, 0.4, 0.1, 0.4, 0.0)
            if (crackedSegments % 2 == 0) GiantSounds.crack(at)
            val half = m.width / 2
            damageIn(g, m, at) { e ->
                val w = Vector(e.location.x - from.x, 0.0, e.location.z - from.z)
                val along = w.dot(f)
                val side = w.clone().subtract(f.clone().multiply(along)).length()
                along in (d - segLen / 2 - 0.5)..(d + segLen / 2 + 0.5) && side <= half + e.width / 2
            }
            crackedSegments++
        }
    }

    /**
     * cond を満たし、届く高さ（hitHeights）にいる相手にダメージ（1つの動きにつき1人1回）。c から外向きに吹き飛ばして浮かせる
     */
    private fun damageIn(g: GiantBoss, m: GiantMove, c: Location, cond: (LivingEntity) -> Boolean) {
        val r = maxOf(m.radius, m.length) + m.forward + 4.0
        val heights = hitHeights(g, m, c)
        val vertical = maxOf(c.y - heights.start, heights.endInclusive - c.y) + 1.0
        for (e in c.world.getNearbyEntities(c, r, vertical, r)) {
            if (e !is LivingEntity || !TargetFinder.isValidTarget(g, e) || e.uniqueId in hit) continue
            if (e.location.y !in heights) continue
            if (!cond(e)) continue
            hit += e.uniqueId
            if (e is Player && e.location.y - c.y >= g.airHeight) GiantFx.airBurst(c.y, e.location)
            e.noDamageTicks = 0
            e.damage(m.damage, g.entity)
            val away = Vector(e.location.x - c.x, 0.0, e.location.z - c.z)
            val push = if (away.lengthSquared() > 1e-4) away.normalize().multiply(m.knockback) else Vector()
            e.velocity = push.setY(m.launch)
        }
    }

    private fun horizontalDistance(a: Location, b: Location): Double = Math.hypot(a.x - b.x, a.z - b.z)

    // ===== 大跳躍 =====

    private fun leapStep(g: GiantBoss, m: GiantMove) {
        val from = leapFrom ?: return
        val to = aimPoint ?: return
        val f = ((tick - m.takeoffTick + 1).toDouble() / (m.impactTick - m.takeoffTick)).coerceIn(0.0, 1.0)
        val p = from.clone().add(to.toVector().subtract(from.toVector()).multiply(f))
        p.y = from.y + (to.y - from.y) * f + m.leapHeight * sin(PI * f)
        p.yaw = yaw
        p.pitch = 0f
        g.entity.teleport(p)
    }

    private fun land(g: GiantBoss) {
        val to = aimPoint ?: return
        val p = to.clone()
        p.yaw = yaw
        p.pitch = 0f
        g.entity.teleport(p)
        g.floating = false
    }

    // ===== 岩投げ =====

    private fun throwBoulder(g: GiantBoss, m: GiantMove) {
        val to = aimPoint ?: return
        g.setPartVisible(GiantBoss.BOULDER_BONE, false)
        val from = g.bonePoint(GiantBoss.BOULDER_BONE) ?: g.entity.location.add(0.0, g.entity.height, 0.0)
        val size = 2.6f
        val display = from.world.spawn(from, BlockDisplay::class.java) { d ->
            d.isPersistent = false
            d.block = m.block.createBlockData()
            d.teleportDuration = 1
            d.transformation = Transformation(
                Vector3f(-size / 2, -size / 2, -size / 2), Quaternionf(), Vector3f(size, size, size), Quaternionf(),
            )
        }
        boulders += Boulder(display, from, to.clone(), m)
        GiantSounds.throwRock(g.entity)
    }

    private fun tickBoulders(g: GiantBoss) {
        for (b in boulders.toList()) {
            b.age++
            val m = b.move
            val f = (b.age.toDouble() / m.flightTicks).coerceAtMost(1.0)
            val p = b.from.clone().add(b.to.toVector().subtract(b.from.toVector()).multiply(f))
            p.add(0.0, m.arcHeight * sin(PI * f), 0.0)
            p.yaw = (b.age * 20f) % 360f
            p.pitch = (b.age * 13f) % 180f - 90f
            b.display.teleport(p)
            p.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, p, 1, 0.3, 0.3, 0.3, 0.01)
            if (b.age % 2 == 0) GiantFx.warnCircle(b.to, m.radius, f)
            if (f < 1.0) continue
            boulders.remove(b)
            b.display.remove()
            GiantSounds.impact(b.to)
            GiantFx.groundBurst(b.to, m.radius)
            GiantFx.shake(g, b.to, m.shakeRadius)
            val r = m.radius
            for (e in b.to.world.getNearbyEntities(b.to, r + 1, maxOf(m.hitHeight, BOULDER_DEPTH) + 1, r + 1)) {
                if (e !is LivingEntity || !TargetFinder.isValidTarget(g, e)) continue
                if (e.location.y - b.to.y !in -BOULDER_DEPTH..m.hitHeight) continue
                if (horizontalDistance(e.location, b.to) > r + e.width / 2) continue
                e.noDamageTicks = 0
                e.damage(m.damage, g.entity)
                val away = Vector(e.location.x - b.to.x, 0.0, e.location.z - b.to.z)
                e.velocity = (if (away.lengthSquared() > 1e-4) away.normalize().multiply(m.knockback) else Vector()).setY(m.launch)
            }
        }
    }

    // ===== 連携の後の隙 =====

    private fun tickRecover(g: GiantBoss): Boolean {
        if (tick % 20 == 0) GiantSounds.pant(g.entity)
        if (tick % 6 == 0) {
            val head = g.entity.location.add(0.0, g.entity.height * 0.75, 0.0).add(Facing.forward(g.entity).multiply(1.2))
            head.world.spawnParticle(Particle.CLOUD, head, 2, 0.2, 0.1, 0.2, 0.02)
        }
        if (tick < settings.recoverTicks - 1) return false
        g.stopAnimation(settings.recoverAnimation)
        g.playOnce(settings.recoverEndAnimation)
        phase = Phase.RECOVER_END
        tick = -1
        return false
    }

    private companion object {
        /** 起点より下へ当たる深さ */
        const val HIT_DEPTH = 2.5
        /** 岩の着弾点より下へ当たる深さ */
        const val BOULDER_DEPTH = 5.5

        // 高い所の予告
        const val WARN_MARGIN = 4.0
        const val LAYER_GAP = 2.0
        const val MAX_LAYERS = 3
    }
}
