package com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinClone
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import java.time.Duration
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 分身の囲み（瀕死の大技）: 印を結んで煙に消え、標的のまわりに円になって clones 体で現れる。本物は1体だけ。
 *
 *   印を結ぶ（castTicks。smokeTick で煙に消える）
 *   → 現れる（appearTicks）→ 囲む（waitTicks。全員で標的のまわりを同じ向きに小刻みに回る）
 *   → 見破られなければ、全員が順に予告の線に沿って突っ込む（最後が本物）
 *
 * 見破る: 囲んでいる間に本物にダメージを与える（殴っても矢でもよい）と、分身がすべて消えて本物がよろめく
 * （foundTicks の間、受けるダメージが foundDamageMultiplier 倍）。本物の足元にだけ影がある。
 * 偽物を殴ると、その分身は煙になって消える（突っ込んでくる数が減る）。
 */
class CloneSurroundSkill(
    override val id: String,
    private val settings: Settings,
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val castAnimation: String,
        val castTicks: Int,
        val smokeTick: Int,
        val clones: Int,
        val radius: Double,
        val appearAnimation: String,
        val appearTicks: Int,
        val waitTicks: Int,
        val orbitDegrees: Double,
        val orbitIntervalTicks: Int,
        val orbitMoveTicks: Int,
        val assaultIntervalTicks: Int,
        val warningTicks: Int,
        val dashTicks: Int,
        val overshoot: Double,
        val lineWidth: Double,
        val damage: Double,
        val knockback: Double,
        val readyAnimation: String,
        val dashAnimation: String,
        val foundAnimation: String,
        val foundAnimationTicks: Int,
        val foundLoopAnimation: String,
        val foundTicks: Int,
        val foundEndAnimation: String,
        val foundEndTicks: Int,
        val foundDamageMultiplier: Double,
        val title: Component,
        val subtitle: Component,
        val foundMessage: Component,
        val fakeMessage: Component,
        val failMessage: Component,
    ) {
        companion object {
            fun load(section: ConfigSection): Settings {
                fun msg(key: String, def: String) = section.file.component(section.fullKey(key), def = def)
                return Settings(
                    weight = section.int(Names.SkillConfig.WEIGHT, 1),
                    range = section.double(Names.SkillConfig.RANGE, 20.0),
                    castAnimation = section.string(Names.SkillConfig.ANIMATION, "clone_cast"),
                    castTicks = section.int(Names.SkillConfig.CAST_TICKS, 16).coerceAtLeast(1),
                    smokeTick = section.int(Names.SkillConfig.SMOKE_TICK, 12),
                    clones = section.int(Names.SkillConfig.CLONES, 4).coerceIn(2, 8),
                    radius = section.double(Names.SkillConfig.RADIUS, 5.0),
                    appearAnimation = section.string(Names.SkillConfig.APPEAR_ANIMATION, "clone_appear"),
                    appearTicks = section.int(Names.SkillConfig.APPEAR_TICKS, 12),
                    waitTicks = section.int(Names.SkillConfig.WAIT_TICKS, 100),
                    orbitDegrees = section.double(Names.SkillConfig.ORBIT_DEGREES, 25.0),
                    orbitIntervalTicks = section.int(Names.SkillConfig.ORBIT_INTERVAL_TICKS, 14).coerceAtLeast(4),
                    orbitMoveTicks = section.int(Names.SkillConfig.STEP_TICKS, 3).coerceAtLeast(1),
                    assaultIntervalTicks = section.int(Names.SkillConfig.INTERVAL_TICKS, 7).coerceAtLeast(1),
                    warningTicks = section.int(Names.SkillConfig.WARNING_TICKS, 14).coerceAtLeast(1),
                    dashTicks = section.int(Names.SkillConfig.DASH_TICKS, 3).coerceAtLeast(1),
                    overshoot = section.double(Names.SkillConfig.OVERSHOOT, 3.0),
                    lineWidth = section.double(Names.SkillConfig.LINE_WIDTH, 1.0),
                    damage = section.double(Names.SkillConfig.DAMAGE, 8.0),
                    knockback = section.double(Names.SkillConfig.KNOCKBACK, 0.4),
                    readyAnimation = section.string(Names.SkillConfig.READY_ANIMATION, "dash_ready"),
                    dashAnimation = section.string(Names.SkillConfig.DASH_ANIMATION, "dash"),
                    foundAnimation = section.string(Names.SkillConfig.EXPOSED_ANIMATION, "exposed"),
                    foundAnimationTicks = section.int(Names.SkillConfig.EXPOSED_TICKS, 30),
                    foundLoopAnimation = section.string(Names.SkillConfig.EXHAUSTED_ANIMATION, "tired"),
                    foundTicks = section.int(Names.SkillConfig.EXHAUSTED_TICKS, 70),
                    foundEndAnimation = section.string(Names.SkillConfig.EXHAUSTED_END_ANIMATION, "tired_end"),
                    foundEndTicks = section.int(Names.SkillConfig.EXHAUSTED_END_TICKS, 10),
                    foundDamageMultiplier = section.double(Names.SkillConfig.EXHAUSTED_DAMAGE_MULTIPLIER, 1.6),
                    title = msg(Names.SkillConfig.WARN_TITLE, "<dark_purple><bold>影分身"),
                    subtitle = msg(Names.SkillConfig.WARN_SUBTITLE, "<gray>本物を見つけて叩け"),
                    foundMessage = msg(Names.SkillConfig.FOUND_MESSAGE, "<gold>本物を見破った！ 今のうちに叩け"),
                    fakeMessage = msg(Names.SkillConfig.FAKE_MESSAGE, "<gray>……偽物だ"),
                    failMessage = msg(Names.SkillConfig.FAIL_MESSAGE, "<red><bold>来る！</bold> 線の外へ"),
                )
            }
        }
    }

    private enum class Phase { CAST, APPEAR, WAIT, FOUND, ASSAULT }

    /** 円の1か所。clone が null なら本物（ボス本体） */
    private inner class Slot(var angle: Double, val clone: AssassinClone?) {
        var alive = true
        var from: Location? = null
        var to: Location? = null
        var lineFrom: Location? = null
        var lineTo: Location? = null
        val hit = HashSet<UUID>()
        val isReal: Boolean get() = clone == null
    }

    override val weight: Int get() = settings.weight

    private var phase = Phase.CAST
    private var tick = 0
    private var target: LivingEntity? = null
    private var center: Location? = null
    private val slots = mutableListOf<Slot>()
    private var orbitSign = 1.0
    private var orbitTick = 0
    private var found = false
    private var assaultOrder = listOf<Slot>()

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val a = boss as AssassinBoss
        phase = Phase.CAST
        tick = 0
        found = false
        slots.clear()
        target = TargetFinder.findNearest(a, settings.range)
        Facing.faceTarget(a, target)
        a.playOnce(settings.castAnimation)
        AssassinSounds.play(a.entity.location, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.4f, 0.8f)
    }

    override fun tick(boss: Boss): Boolean {
        val a = boss as AssassinBoss
        val done = when (phase) {
            Phase.CAST -> cast(a)
            Phase.APPEAR -> appear(a)
            Phase.WAIT -> wait(a)
            Phase.FOUND -> foundTick(a)
            Phase.ASSAULT -> assault(a)
        }
        tick++
        return done
    }

    override fun stop(boss: Boss) {
        val a = boss as AssassinBoss
        for (s in slots) s.clone?.let { a.removeClone(it, effect = !it.isRemoved && s.alive) }
        slots.clear()
        a.surface()
        a.stopAnimation(settings.readyAnimation)
        a.stopAnimation(settings.foundLoopAnimation)
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        when (phase) {
            // 囲んでいる間に本物が攻撃を受けた: 見破られた
            Phase.APPEAR, Phase.WAIT -> found = true
            Phase.FOUND -> event.damage *= settings.foundDamageMultiplier
            else -> {}
        }
    }

    private fun next(p: Phase) {
        phase = p
        tick = -1   // tick() の最後で 0 になる
    }

    private fun currentTarget(a: AssassinBoss): LivingEntity? {
        val t = target?.takeIf { TargetFinder.isValidTarget(a, it) } ?: TargetFinder.findNearest(a, settings.range * 1.5)
        target = t
        return t
    }

    // ===== 印を結んで、煙に消える =====

    private fun cast(a: AssassinBoss): Boolean {
        val at = a.entity.location
        if (tick < settings.smokeTick && tick % 3 == 0) {
            at.world.spawnParticle(Particle.DUST, at.clone().add(0.0, 1.3, 0.0), 4, 0.3, 0.3, 0.3, 0.0, AssassinFx.AURA)
        }
        if (tick == settings.smokeTick) {
            at.world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0.0, 1.0, 0.0), 60, 0.8, 1.0, 0.8, 0.05)
            at.world.spawnParticle(Particle.POOF, at.clone().add(0.0, 1.0, 0.0), 30, 0.6, 0.8, 0.6, 0.08)
            AssassinSounds.play(at, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.6f, 0.6f)
            AssassinSounds.play(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.6f, 0.7f)
            a.submerge()
        }
        if (tick < settings.castTicks - 1) return false
        a.submerge()
        val t = currentTarget(a)
        center = (t?.location ?: at).clone()
        placeSlots(a)
        showTitle(a)
        next(Phase.APPEAR)
        return false
    }

    /** 標的のまわりに円になって現れる。本物の位置はランダム */
    private fun placeSlots(a: AssassinBoss) {
        val c = center ?: return
        val n = settings.clones
        val base = Random.nextDouble(360.0)
        val realIndex = Random.nextInt(n)
        for (i in 0 until n) {
            val angle = base + 360.0 * i / n
            val pos = ringPoint(c, angle)
            if (i == realIndex) {
                val slot = Slot(angle, null)
                slots += slot
                a.entity.teleport(pos)
                a.surface()
                a.playOnce(settings.appearAnimation)
                AssassinFx.inkBurst(pos, 0.8)
            } else {
                val clone = a.spawnClone(pos, hittable = true) ?: continue
                val slot = Slot(angle, clone)
                clone.onHit = { player -> hitFake(a, slot, player) }
                clone.playOnce(settings.appearAnimation)
                slots += slot
            }
        }
        AssassinSounds.play(c, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.8f, 1.1f)
        AssassinSounds.play(c, Sound.ENTITY_WARDEN_HEARTBEAT, 1.8f, 1.0f)
    }

    /** 円の上の angle 度の地面の点（標的の方を向く）。壁で立てなければ、半径を縮める */
    private fun ringPoint(c: Location, angle: Double): Location {
        val rad = Math.toRadians(angle)
        var r = settings.radius
        while (r > 1.5) {
            val p = c.clone().add(cos(rad) * r, 0.0, sin(rad) * r)
            val ground = Ground.step(p, maxDrop = 4)
            if (ground != null && Ground.freeLength(c, Vector(cos(rad), 0.0, sin(rad)), r) >= r - 0.6) {
                ground.yaw = Facing.yawOf(Vector(-cos(rad), 0.0, -sin(rad)))
                ground.pitch = 0f
                return ground
            }
            r -= 1.0
        }
        val fallback = c.clone().add(cos(rad) * 1.5, 0.0, sin(rad) * 1.5)
        fallback.yaw = Facing.yawOf(Vector(-cos(rad), 0.0, -sin(rad)))
        return fallback
    }

    private fun showTitle(a: AssassinBoss) {
        val title = Title.title(
            settings.title, settings.subtitle,
            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(400)),
        )
        participants(a).forEach { it.showTitle(title) }
    }

    private fun participants(a: AssassinBoss): List<Player> = a.battle?.onlineParticipants(a.plugin.server)
        ?: a.entity.getNearbyEntities(40.0, 24.0, 40.0).filterIsInstance<Player>()

    // ===== 現れる・囲む =====

    private fun appear(a: AssassinBoss): Boolean {
        faceAll(a)
        drawRealShadow(a)
        if (found) return reveal(a)
        if (tick < settings.appearTicks - 1) return false
        orbitTick = 0
        next(Phase.WAIT)
        return false
    }

    private fun wait(a: AssassinBoss): Boolean {
        if (found) return reveal(a)
        val t = currentTarget(a)
        // 囲みは標的についていく（離れたら、次の回り込みで寄せる）
        if (t != null && orbitTick % settings.orbitIntervalTicks == 0) {
            center = t.location.clone()
            if (Random.nextDouble() < 0.35) orbitSign = -orbitSign
            beginOrbitStep(a)
        }
        moveOrbitStep(a)
        faceAll(a)
        drawRealShadow(a)
        // 鼓動がだんだん速くなる
        val left = settings.waitTicks - tick
        val beat = if (left < 30) 6 else if (left < 60) 10 else 16
        if (tick % beat == 0) center?.let { AssassinSounds.play(it, Sound.ENTITY_WARDEN_HEARTBEAT, 1.4f, 1.0f + 0.4f * (1 - left / settings.waitTicks.toFloat())) }
        orbitTick++
        if (tick < settings.waitTicks - 1) return false
        beginAssault(a)
        return false
    }

    /** 全員そろって、円の上を orbitDegrees だけ横へ刻む（ステップのアニメ付き） */
    private fun beginOrbitStep(a: AssassinBoss) {
        val c = center ?: return
        for (s in slots) {
            if (!s.alive) continue
            s.angle += orbitSign * settings.orbitDegrees
            val from = if (s.isReal) a.entity.location else s.clone!!.location
            val to = ringPoint(c, s.angle)
            s.from = from.clone()
            s.to = to
            val face = Facing.horizontalDir(from.toVector(), c.toVector()) ?: continue
            val right = Vector(-face.z, 0.0, face.x)
            val move = Vector(to.x - from.x, 0.0, to.z - from.z)
            val anim = if (move.dot(right) > 0) AssassinBoss.STEP_RIGHT else AssassinBoss.STEP_LEFT
            if (s.isReal) a.playOnce(anim) else s.clone!!.playOnce(anim)
        }
        AssassinSounds.step(a.entity)
    }

    private fun moveOrbitStep(a: AssassinBoss) {
        val step = orbitTick % settings.orbitIntervalTicks
        if (step >= settings.orbitMoveTicks) return
        val f = (step + 1).toDouble() / settings.orbitMoveTicks
        for (s in slots) {
            if (!s.alive) continue
            val from = s.from ?: continue
            val to = s.to ?: continue
            val p = from.clone().add(to.toVector().subtract(from.toVector()).multiply(f))
            if (s.isReal) a.entity.teleport(p) else s.clone!!.moveTo(p)
            AssassinFx.stepDust(from)
        }
    }

    /** 全員、標的の方を向く */
    private fun faceAll(a: AssassinBoss) {
        val t = target ?: return
        for (s in slots) {
            if (!s.alive) continue
            if (s.isReal) Facing.faceTarget(a, t) else s.clone!!.faceTo(t.location)
        }
    }

    /** 本物の足元にだけ影がある（見破る手がかり） */
    private fun drawRealShadow(a: AssassinBoss) {
        if (tick % 2 == 0) AssassinFx.shadow(a.entity.location, 0.45)
    }

    private fun hitFake(a: AssassinBoss, slot: Slot, player: Player) {
        if (!slot.alive || phase == Phase.CAST || phase == Phase.FOUND) return
        slot.alive = false
        slot.clone?.let { a.removeClone(it) }
        player.sendActionBar(settings.fakeMessage)
        player.playSound(player.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.HOSTILE, 1.0f, 0.6f)
    }

    // ===== 見破られた =====

    private fun reveal(a: AssassinBoss): Boolean {
        for (s in slots) if (!s.isReal && s.alive) {
            s.alive = false
            s.clone?.let { a.removeClone(it) }
        }
        a.stopAnimation(settings.readyAnimation)
        a.playOnce(settings.foundAnimation)
        val at = a.entity.location
        at.world.spawnParticle(Particle.END_ROD, at.clone().add(0.0, 1.2, 0.0), 20, 0.3, 0.5, 0.3, 0.1)
        AssassinSounds.exposed(a.entity)
        a.announce(settings.foundMessage)
        next(Phase.FOUND)
        return false
    }

    private fun foundTick(a: AssassinBoss): Boolean {
        val s = settings
        if (tick == s.foundAnimationTicks) a.playLoop(s.foundLoopAnimation, lerpTicks = 3)
        if (tick > s.foundAnimationTicks && tick % 16 == 0) AssassinSounds.pant(a.entity)
        if (tick == s.foundTicks) {
            a.stopAnimation(s.foundLoopAnimation)
            a.playOnce(s.foundEndAnimation)
        }
        return tick >= s.foundTicks + s.foundEndTicks
    }

    // ===== 時間切れ: 全員が順に突っ込む（最後が本物） =====

    private fun beginAssault(a: AssassinBoss) {
        assaultOrder = slots.filter { it.alive && !it.isReal }.shuffled() + slots.filter { it.isReal }
        a.announce(settings.failMessage)
        next(Phase.ASSAULT)
    }

    private fun assault(a: AssassinBoss): Boolean {
        val s = settings
        val t = currentTarget(a)
        var allDone = true
        for ((k, slot) in assaultOrder.withIndex()) {
            val warnStart = k * s.assaultIntervalTicks
            val dashStart = warnStart + s.warningTicks
            val dashEnd = dashStart + s.dashTicks
            if (tick < dashEnd + 1) allDone = false
            if (!slot.alive) continue
            val here = if (slot.isReal) a.entity.location else slot.clone!!.location

            if (tick == warnStart) {
                // 線を決める（標的を貫いて overshoot 先まで）
                val goal = t?.location ?: center ?: here
                val dir = Facing.horizontalDir(here.toVector(), goal.toVector()) ?: Facing.dirOfYaw(here.yaw)
                val dist = Math.hypot(goal.x - here.x, goal.z - here.z) + s.overshoot
                val free = Ground.freeLength(here, dir, dist)
                slot.lineFrom = here.clone()
                slot.lineTo = here.clone().add(dir.clone().multiply(free))
                val face = here.clone().also { it.yaw = Facing.yawOf(dir) }
                if (slot.isReal) {
                    a.entity.teleport(face)
                    a.playLoop(s.readyAnimation, lerpTicks = 2)
                } else {
                    slot.clone!!.moveTo(face)
                    slot.clone.playLoop(s.readyAnimation, lerpTicks = 2)
                }
            }
            val from = slot.lineFrom ?: continue
            val to = slot.lineTo ?: continue
            if (tick in warnStart until dashStart) {
                val hot = tick >= dashStart - 3
                if (tick % 2 == 0 || hot) AssassinFx.line(from, to, if (hot) AssassinFx.WARN_HOT else AssassinFx.WARN)
            }
            if (tick == dashStart) {
                if (slot.isReal) a.playOnce(s.dashAnimation) else slot.clone!!.playOnce(s.dashAnimation)
                AssassinSounds.dash(a.entity)
                AssassinSounds.play(from, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 1.3f)
            }
            if (tick in dashStart until dashEnd) {
                val f = (tick - dashStart + 1).toDouble() / s.dashTicks
                val p = from.clone().add(to.toVector().subtract(from.toVector()).multiply(f))
                p.y = Ground.groundY(p.world, p.x, here.y + 1.0, p.z, 4)
                val prev = here.clone()
                if (slot.isReal) a.entity.teleport(p) else slot.clone!!.moveTo(p)
                AssassinFx.afterimage(prev, 1.8)
                a.hitAlongLine(prev, p, s.lineWidth, s.damage, s.knockback, slot.hit)
            }
            if (tick == dashEnd + 1) {
                if (slot.isReal) {
                    a.stopAnimation(s.readyAnimation)
                } else {
                    slot.alive = false
                    slot.clone?.let { a.removeClone(it) }
                }
            }
        }
        return allDone
    }
}
