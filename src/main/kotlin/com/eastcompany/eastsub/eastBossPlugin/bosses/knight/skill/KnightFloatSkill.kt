package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Ground
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.util.Vector
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 浮遊: 宙に浮き上がり、標的の方へ滑るように寄ってから、降りながら大剣を振るう。
 *
 * 技を始めるたびに chance の割合で宙から打つ（[air]）。外れたとき・頭の上が狭くて浮けないときは、地上のまま（[ground]）。
 *
 *   0 〜 riseTicks          … 足が地面を離れ、斜め後ろへ下がりながら height まで浮き上がる（浮き上がりのアニメ riseAnimation）
 *   〜 hoverTicks 以上      … 宙に留まって標的の方へ寄る（標的から distance まで。最長 maxTicks で打ち始める）。
 *                              寄っている間は宙を滑るアニメ（glideAnimation）、寄り終えて待つ間は浮遊のアニメ（animation）
 *   宙からの技（air）       … 技の始めから descendTicks かけて降りる（高さ = 浮いた高さ × (1 − f²)。
 *                              溜めの間は宙にいて、打つ直前に一気に降り立つ）
 *
 * air のアニメ（*_air）は、浮遊の姿勢から始まり、降り切る tick に足が着くように作ってある（モデルの生成スクリプトの FLOAT_H）。
 * 効果音・地割れなどの tick は、air の技の始めから数える（浮いている間は数えない）。
 * 途中で止められたら（体勢崩し・死亡）、その下の地面に降ろす。
 */
class KnightFloatSkill(
    override val id: String,
    private val settings: Settings,
    private val ground: BossSkill,
    private val air: BossSkill,
) : BossSkill {

    data class Settings(
        /** 宙から打つ割合（0〜1） */
        val chance: Double,
        /** この距離以内の標的に寄る */
        val range: Double,
        /** 浮く高さ（ブロック。足の裏から地面まで） */
        val height: Double,
        val riseTicks: Int,
        /** 浮き上がってから、最低これだけ宙に留まる */
        val hoverTicks: Int,
        /** 浮き始めてから打ち始めるまでの最長 */
        val maxTicks: Int,
        /** 宙で寄る速さ（1tick に進むブロック） */
        val glideSpeed: Double,
        /** 標的との水平距離がこれ以下になったら打ち始める */
        val distance: Double,
        /** 宙からの技の始めから、降り切るまでの tick */
        val descendTicks: Int,
        /** 宙で止まっている間に繰り返すアニメ */
        val animation: String,
        /** 浮き上がる間に1回流すアニメ（空なら animation を繰り返す） */
        val riseAnimation: String,
        /** 宙で標的へ寄っている間に繰り返すアニメ（空なら animation） */
        val glideAnimation: String,
        /** 浮き上がりながら斜め後ろへ下がる距離（ブロック。0 で真上） */
        val backDistance: Double,
        /** 下がる向き（真後ろから左右どちらかへこの角度だけ斜め） */
        val backDegrees: Double,
    ) {
        companion object {
            /**
             * @param range 寄る標的を探す距離（技の射程）
             * @param firstHitTick 宙からの技の最初の判定の tick（float-descend-ticks が無いとき、その 1tick 前に降り切る）
             */
            fun load(section: ConfigSection, range: Double, firstHitTick: Int): Settings {
                val rise = section.int(Names.SkillConfig.FLOAT_RISE_TICKS, 12).coerceAtLeast(1)
                val hover = section.int(Names.SkillConfig.FLOAT_HOVER_TICKS, 8).coerceAtLeast(0)
                return Settings(
                    chance = section.double(Names.SkillConfig.FLOAT_CHANCE, 0.0).coerceIn(0.0, 1.0),
                    range = range,
                    height = section.double(Names.SkillConfig.FLOAT_HEIGHT, 2.2),
                    riseTicks = rise,
                    hoverTicks = hover,
                    maxTicks = section.int(Names.SkillConfig.FLOAT_MAX_TICKS, 36).coerceAtLeast(rise + hover),
                    glideSpeed = section.double(Names.SkillConfig.FLOAT_GLIDE_SPEED, 0.35),
                    distance = section.double(Names.SkillConfig.FLOAT_DISTANCE, 3.4),
                    descendTicks = section.int(Names.SkillConfig.FLOAT_DESCEND_TICKS, (firstHitTick - 1).coerceAtLeast(1))
                        .coerceAtLeast(1),
                    animation = section.string(Names.SkillConfig.FLOAT_ANIMATION).ifEmpty { "float" },
                    riseAnimation = section.string(Names.SkillConfig.FLOAT_RISE_ANIMATION, "float_rise"),
                    glideAnimation = section.string(Names.SkillConfig.FLOAT_GLIDE_ANIMATION, "float_glide"),
                    backDistance = section.double(Names.SkillConfig.FLOAT_BACK_DISTANCE, 3.0).coerceAtLeast(0.0),
                    backDegrees = section.double(Names.SkillConfig.FLOAT_BACK_DEGREES, 45.0),
                )
            }
        }
    }

    override val weight: Int get() = ground.weight

    /** 今出している技（地上 / 宙から） */
    private var active: BossSkill = ground

    /** 浮いて寄っている最中か（まだ技を始めていない） */
    private var floating = false

    /** 地面から離れているか（降り切るまで） */
    private var airborne = false

    /** 技（active）を始めたか */
    private var started = false

    private var tick = 0
    private var innerTick = 0

    /** 浮いている高さの上限（頭の上の空きで決まる） */
    private var maxHeight = 0.0

    /** 降り始めたときの高さ */
    private var descendFrom = 0.0

    private var target: LivingEntity? = null

    /** 浮き上がりながら下がる向き（水平・長さ 1） */
    private var backDir: Vector? = null

    /** 宙で繰り返しているアニメ（浮遊 / 宙を滑る。まだ無ければ null） */
    private var loopAnimation: String? = null

    override val sounds: List<SoundCue> get() = if (started) active.sounds else emptyList()

    /** 技を始めるまで（浮いている間）は -1（技の効果音は、技を始めてから数える） */
    override val soundTick: Int? get() = if (started) (active.soundTick ?: innerTick) else -1

    override fun canStart(boss: Boss): Boolean = ground.canStart(boss)

    override fun start(boss: Boss) {
        tick = 0
        innerTick = 0
        started = false
        target = TargetFinder.findNearest(boss, settings.range)

        val entity = boss.entity
        maxHeight = headroom(entity.location, settings.height, entity.height)
        val lift = settings.chance > 0 && Random.nextDouble() < settings.chance && maxHeight >= MIN_HEIGHT
        if (!lift) {
            active = ground
            floating = false
            airborne = false
            beginInner(boss)
            return
        }

        active = air
        floating = true
        airborne = true
        entity.setAI(false)
        entity.setGravity(false)
        Facing.faceTarget(boss, target)
        backDir = chooseBackDir(boss)
        loopAnimation = null
        if (settings.riseAnimation.isNotEmpty()) boss.playOnce(settings.riseAnimation) else setLoop(boss, settings.animation)
        KnightFx.liftOff(entity.location)
        KnightSounds.floatRise(entity)
    }

    override fun tick(boss: Boss): Boolean {
        if (floating) {
            hover(boss)
            tick++
            return false
        }
        if (!started) {
            // 浮遊から移った tick は始めるだけ（技の 0 tick 目の効果音を、次の tick で鳴らせるように）
            beginInner(boss)
            return false
        }
        val done = active.tick(boss)
        innerTick++
        if (airborne) descend(boss)
        return done
    }

    override fun stop(boss: Boss) {
        if (started) active.stop(boss)
        if (airborne && !started) stopFloatAnimations(boss)
        val entity = boss.entity
        if (airborne) {
            // 宙にいるうちに止められたら、その下の地面に降ろす
            val loc = entity.location
            loc.y = groundBelow(loc)
            entity.teleport(loc)
        }
        entity.setGravity(true)
        boss.resumeEntityAi()
        floating = false
        airborne = false
        started = false
        target = null
    }

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) {
        if (started) active.modifyIncomingDamage(boss, event)
    }

    private fun beginInner(boss: Boss) {
        started = true
        innerTick = 0
        active.start(boss)
        if (airborne) {
            // 技のアニメ（*_air）を流してから浮遊のアニメを止める（間に待機の姿勢が挟まらないように）
            stopFloatAnimations(boss)
            // 技の始めでエンティティの AI が切られる。宙から打つ技は、降り切るまで重力も切ったまま
            boss.entity.setGravity(false)
        }
    }

    // ===== 浮遊のアニメ =====

    /** 宙で繰り返すアニメを切り替える（同じなら何もしない。浮き上がりのアニメは繰り返しのアニメで上書きされる） */
    private fun setLoop(boss: Boss, animation: String) {
        if (animation == loopAnimation) return
        loopAnimation?.let { boss.stopAnimation(it) }
        boss.playLoop(animation, lerpTicks = 6)
        loopAnimation = animation
    }

    /** 浮き上がり・浮遊・宙を滑るアニメを止める */
    private fun stopFloatAnimations(boss: Boss) {
        boss.stopAnimation(settings.riseAnimation)
        loopAnimation?.let { boss.stopAnimation(it) }
        loopAnimation = null
    }

    // ===== 浮いて寄る =====

    private fun hover(boss: Boss) {
        val entity = boss.entity
        val t = target?.takeIf { TargetFinder.isValidTarget(boss, it) }
            ?: TargetFinder.findNearest(boss, settings.range)?.also { target = it }
        if (t != null) Facing.faceTarget(boss, t)

        val from = entity.location
        val next = from.clone()

        // 浮き上がる間は斜め後ろへ下がる（初めは速く、だんだんゆっくり）→ 寄る（水平）。標的から distance までは近づかない
        var close = t == null
        val back = backDir
        if (tick < settings.riseTicks && back != null) {
            val f0 = tick.toDouble() / settings.riseTicks
            val f1 = (tick + 1).toDouble() / settings.riseTicks
            val ease = { f: Double -> 1 - (1 - f) * (1 - f) }
            next.add(back.clone().multiply(settings.backDistance * (ease(f1) - ease(f0))))
            close = false
        } else if (t != null) {
            val dist = hypot(t.location.x - from.x, t.location.z - from.z)
            close = dist <= settings.distance
            val dir = Facing.horizontalDir(entity, t)
            if (!close && dir != null) {
                val step = minOf(settings.glideSpeed, dist - settings.distance)
                next.add(dir.multiply(step))
            }
        }

        // 高さ: 浮き上がり（だんだんゆっくり）→ 宙でゆっくり上下する
        val h = if (tick < settings.riseTicks) {
            val f = (tick + 1).toDouble() / settings.riseTicks
            maxHeight * (1 - (1 - f) * (1 - f))
        } else {
            maxHeight + BOB * sin((tick - settings.riseTicks) * 0.3)
        }
        if (!place(entity, next, h)) place(entity, from.clone(), h)

        // 浮き上がり終えたら、寄っている間は宙を滑る・寄り終えたら浮遊のアニメ
        if (tick + 1 >= settings.riseTicks) {
            setLoop(boss, if (close) settings.animation else settings.glideAnimation.ifEmpty { settings.animation })
        }

        val at = entity.location
        if (tick % 2 == 0) KnightFx.floatTrail(entity, groundBelow(at))
        if (tick > 0 && tick % 14 == 0) KnightSounds.floatHum(entity)

        // 寄り終えたら（または待ちきれなくなったら）打ち始める
        val hovered = tick + 1 >= settings.riseTicks + settings.hoverTicks
        if (hovered && (close || tick + 1 >= settings.maxTicks)) {
            floating = false
            descendFrom = (at.y - groundBelow(at)).coerceAtLeast(0.0)
            KnightSounds.floatDive(entity)
        }
    }

    /**
     * 浮き上がりながら下がる向き: 標的から離れる向きを、左右どちらかへ backDegrees だけ斜めにする
     * （開けている方。同じくらいなら左右はランダム）。下がれないなら null（真上に浮く）
     */
    private fun chooseBackDir(boss: Boss): Vector? {
        if (settings.backDistance <= 0) return null
        val entity = boss.entity
        val away = Facing.forward(entity).multiply(-1).setY(0)
        if (away.lengthSquared() < 1e-6) return null
        away.normalize()
        val from = entity.location
        val sides = listOf(1.0, -1.0).shuffled().map { sign ->
            val dir = away.clone().rotateAroundY(Math.toRadians(settings.backDegrees * sign))
            dir to Ground.freeLength(from, dir, settings.backDistance + 1.0)
        }
        val best = sides.maxBy { it.second }
        return if (best.second >= 1.0) best.first else null
    }

    // ===== 降りる =====

    private fun descend(boss: Boss) {
        val entity = boss.entity
        val f = (innerTick.toDouble() / settings.descendTicks).coerceAtMost(1.0)
        val h = descendFrom * (1 - f * f)
        // 技の踏み込み（地面に沿った移動）で下がった分も、ここで高さを合わせ直す
        place(entity, entity.location, h)
        if (f >= 1.0) {
            airborne = false
            entity.setGravity(true)
            KnightFx.touchDown(entity.location)
            KnightSounds.touchDown(entity)
        }
    }

    // ===== 位置 =====

    /** at の水平位置の、地面から h の高さへ動かす（向きはそのまま）。体が入らなければ低くしてみて、だめなら動かない */
    private fun place(entity: LivingEntity, at: Location, h: Double): Boolean {
        val base = groundBelow(at)
        var height = h
        while (height >= 0) {
            val to = at.clone()
            to.y = base + height
            val now = entity.location
            to.yaw = now.yaw
            to.pitch = 0f
            if (bodyFree(to, entity.height)) {
                entity.teleport(to)
                return true
            }
            height -= 0.25
        }
        return false
    }

    /** at の真下の地面の高さ（足元が宙なら、下へ探す） */
    private fun groundBelow(at: Location): Double =
        Ground.groundY(at.world, at.x, at.y + 0.01, at.z, ceil(settings.height).toInt() + 6)

    /** 足元から bodyHeight の高さまで、体が入るか */
    private fun bodyFree(at: Location, bodyHeight: Double): Boolean {
        var y = 0.0
        while (y < bodyHeight) {
            if (!at.clone().add(0.0, y, 0.0).block.isPassable) return false
            y += 1.0
        }
        return at.clone().add(0.0, bodyHeight - 0.05, 0.0).block.isPassable
    }

    /** 今いる所から、どこまで浮けるか（max まで。頭の上の空きで決まる） */
    private fun headroom(at: Location, max: Double, bodyHeight: Double): Double {
        var h = max
        while (h > 0) {
            if (bodyFree(at.clone().add(0.0, h, 0.0), bodyHeight)) return h
            h -= 0.25
        }
        return 0.0
    }

    companion object {
        /** これより低くしか浮けないなら、地上のまま打つ */
        private const val MIN_HEIGHT = 1.0

        /** 宙に留まっている間の上下の揺れ（ブロック） */
        private const val BOB = 0.15
    }
}
