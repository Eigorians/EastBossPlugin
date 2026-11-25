package com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.skill

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerBoss
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerFx
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerSounds
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigSection
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import com.eastcompany.eastsub.eastBossPlugin.skill.util.Facing
import com.eastcompany.eastsub.eastBossPlugin.skill.util.TargetFinder
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.time.Duration

/**
 * 決闘（瀕死になってからの大技）。**ジャストガードしなければ即死**。
 *
 *   構える（readyTicks。coinTick で親指でコインを高く弾く）
 *   → 待つ（waitTicks。鐘が bells 回鳴る。コインは最後の鐘と同時に手元へ落ちてくる）
 *   → 早撃ち（drawTicks。射程内の相手全員を、shootTicks に1人ずつ撃ち抜く）
 *
 * 弾は一瞬で届き、物陰も貫く（隠れても無駄）。撃たれる相手ごとに、撃つ cueLeadTicks 前に
 * その人にだけ「撃鉄の音」とアクションバーの合図が届く。その瞬間に盾を構えてジャストガードできれば無傷、
 * それ以外（盾を構えっぱなし・構えていない）は即死。
 * 撃たれる相手は合図の少し前（最初の合図の時点）に決まる。構えている間に殴っても止まらない。
 */
class DuelSkill(
    override val id: String,
    private val settings: Settings,
    override val sounds: List<SoundCue> = emptyList(),
) : BossSkill {

    data class Settings(
        val weight: Int,
        val range: Double,
        val readyAnimation: String,
        val readyTicks: Int,
        val coinTick: Int,
        /** コインが上がる高さ（ブロック） */
        val coinHeight: Double,
        val waitAnimation: String,
        val waitTicks: Int,
        /** 鐘の回数（最後の鐘で抜く） */
        val bells: Int,
        val drawAnimation: String,
        val drawTicks: Int,
        /** 早撃ちの tick（早撃ちの始まりから）。1つにつき1人 */
        val shootTicks: List<Int>,
        /** 撃つ何 tick 前に、撃たれる人へ合図（撃鉄の音）を出すか */
        val cueLeadTicks: Int,
        /** ジャストガードできなかったときのダメージ（即死させる大きさ。防いでも、残っていれば体力を 0 にする） */
        val damage: Double,
        val knockback: Double,
        val warnTitle: Component,
        val warnSubtitle: Component,
        /** 撃たれる相手のアクションバー（待っている間） */
        val seenMessage: Component,
        /** 合図のアクションバー */
        val cueMessage: Component,
    ) {
        val drawStart: Int get() = readyTicks + waitTicks
        val end: Int get() = drawStart + drawTicks

        /** 鐘の鳴る tick（技の開始から）。最後の1回は早撃ちの始まりと同時 */
        val bellTicks: List<Int> get() = (1..bells.coerceAtLeast(1)).map { readyTicks + waitTicks * it / bells.coerceAtLeast(1) }

        /** i 人目を撃つ tick / その合図の tick（技の開始から） */
        fun shotTick(i: Int): Int = drawStart + shootTicks[i]
        fun cueTick(i: Int): Int = shotTick(i) - cueLeadTicks

        /** 撃たれる相手を決める tick（最初の合図の時点） */
        val lockTick: Int get() = if (shootTicks.isEmpty()) drawStart else cueTick(0)

        companion object {
            fun load(section: ConfigSection): Settings {
                fun text(key: String, def: String) = section.file.component(section.fullKey(key), def = def)
                return Settings(
                    weight = section.int(Names.SkillConfig.WEIGHT, 1),
                    range = section.double(Names.SkillConfig.RANGE, 32.0),
                    readyAnimation = section.string(Names.SkillConfig.READY_ANIMATION, "duel_ready"),
                    readyTicks = section.int(Names.SkillConfig.READY_TICKS, 24),
                    coinTick = section.int(Names.SkillConfig.COIN_TICK, 6),
                    coinHeight = section.double(Names.SkillConfig.COIN_HEIGHT, 4.0),
                    waitAnimation = section.string(Names.SkillConfig.WAIT_ANIMATION, "duel_wait"),
                    waitTicks = section.int(Names.SkillConfig.WAIT_TICKS, 60),
                    bells = section.int(Names.SkillConfig.BELLS, 3).coerceAtLeast(1),
                    drawAnimation = section.string(Names.SkillConfig.DRAW_ANIMATION, "duel_draw"),
                    drawTicks = section.int(Names.SkillConfig.DRAW_TICKS, 18),
                    shootTicks = section.stringList(Names.SkillConfig.SHOOT_TICKS, listOf("2", "4", "6", "8"))
                        .mapNotNull { it.trim().toIntOrNull() }.sorted(),
                    cueLeadTicks = section.int(Names.SkillConfig.CUE_LEAD_TICKS, 7).coerceAtLeast(1),
                    damage = section.double(Names.SkillConfig.DAMAGE, 1000.0),
                    knockback = section.double(Names.SkillConfig.KNOCKBACK, 1.0),
                    warnTitle = text(Names.SkillConfig.WARN_TITLE, "<dark_red>決闘"),
                    warnSubtitle = text(Names.SkillConfig.WARN_SUBTITLE, "<gray>撃鉄の音で盾を構えろ"),
                    seenMessage = text(Names.SkillConfig.SEEN_MESSAGE, "<red><bold>狙われている！</bold> 撃鉄の音を聞き逃すな"),
                    cueMessage = text(Names.SkillConfig.CUE_MESSAGE, "<aqua><bold>今！ 盾を構えろ！"),
                )
            }
        }
    }

    override val weight: Int get() = settings.weight

    private var tick = 0
    private var coin: ItemDisplay? = null
    private var coinFrom: Location? = null
    private var coinSpin = 0f
    /** 撃たれる相手（lockTick に決まる。近い順） */
    private var shotTargets: List<LivingEntity> = emptyList()

    override fun canStart(boss: Boss): Boolean = TargetFinder.findNearest(boss, settings.range) != null

    override fun start(boss: Boss) {
        val g = boss as GunnerBoss
        tick = 0
        shotTargets = emptyList()
        g.equip(GunnerBoss.Weapon.PISTOLS)
        g.refillAmmo()
        g.entity.setAI(false)
        Facing.faceTarget(g, TargetFinder.findNearest(g, settings.range))
        g.playOnce(settings.readyAnimation)

        val title = Title.title(
            settings.warnTitle, settings.warnSubtitle,
            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2500), Duration.ofMillis(500)),
        )
        candidates(g).filterIsInstance<Player>().forEach { it.showTitle(title) }
    }

    override fun tick(boss: Boss): Boolean {
        val g = boss as GunnerBoss
        val s = settings

        if (tick < s.drawStart) {
            val nearest = TargetFinder.findNearest(g, s.range)
            if (nearest != null) Facing.faceTarget(g, nearest)
            if (tick == s.coinTick) flipCoin(g)
            if (tick == s.readyTicks) g.playLoop(s.waitAnimation, lerpTicks = 3)
            tickCoin()
            if (tick >= s.coinTick) mark(g)
        }

        if (tick == s.lockTick) lockTargets(g)
        if (tick == s.drawStart) draw(g)

        for (i in s.shootTicks.indices) {
            if (tick == s.cueTick(i)) cue(shotTargets.getOrNull(i))
            if (tick == s.shotTick(i)) fireAt(g, i)
        }

        tick++
        return tick >= s.end
    }

    // ===== コイン =====

    private fun flipCoin(g: GunnerBoss) {
        val from = (g.bonePoint("hand_r") ?: g.entity.location.add(0.0, g.entity.height * 0.6, 0.0))
            .add(Facing.forward(g.entity).multiply(0.3))
        coinFrom = from
        coin = from.world.spawn(from, ItemDisplay::class.java) { d ->
            d.isPersistent = false
            d.setItemStack(ItemStack.of(Material.GOLD_NUGGET))
            d.teleportDuration = 1
            d.interpolationDuration = 1
            d.brightness = Display.Brightness(15, 15)
            d.transformation = coinTransform(0f)
        }
        from.world.spawnParticle(Particle.WAX_ON, from, 4, 0.05, 0.05, 0.05, 0.5)
    }

    /** コインは放物線を描き、最後の鐘（早撃ち）と同時に手元へ落ちてくる */
    private fun tickCoin() {
        val c = coin ?: return
        val from = coinFrom ?: return
        val s = settings
        val total = (s.drawStart - s.coinTick).coerceAtLeast(1)
        val f = ((tick - s.coinTick).toDouble() / total).coerceIn(0.0, 1.0)
        val p = from.clone().add(0.0, s.coinHeight * 4 * f * (1 - f), 0.0)
        c.teleport(p)
        coinSpin += 0.9f
        c.interpolationDelay = 0
        c.transformation = coinTransform(coinSpin)
        if (tick % 5 == 0) p.world.spawnParticle(Particle.WAX_ON, p, 1, 0.02, 0.02, 0.02, 0.0)
    }

    private fun coinTransform(spin: Float): Transformation =
        Transformation(Vector3f(), Quaternionf().rotateX(spin), Vector3f(COIN_SIZE, COIN_SIZE, COIN_SIZE), Quaternionf())

    private fun removeCoin() {
        coin?.remove()
        coin = null
    }

    // ===== 狙われている相手 =====

    /** 決闘の相手（射程内の、ダメージを与えてよい参加者） */
    private fun candidates(g: GunnerBoss): List<LivingEntity> {
        val r = settings.range
        return g.entity.getNearbyEntities(r, r, r).filter { TargetFinder.isTargetable(g, it) }.map { it as LivingEntity }
    }

    private fun eyeOf(g: GunnerBoss): Location = g.entity.location.add(0.0, g.entity.height * 0.88, 0.0)

    /** 射程内の全員に赤い照準と視線を付け、アクションバーで知らせる（物陰に隠れても狙われる） */
    private fun mark(g: GunnerBoss) {
        val eye = eyeOf(g)
        for (t in candidates(g)) {
            val head = t.eyeLocation.add(0.0, 0.55, 0.0)
            t.world.spawnParticle(Particle.DUST, head, 2, 0.06, 0.06, 0.06, 0.0, MARK)
            if (tick % 2 == 0) GunnerFx.ring(t.location.add(0.0, 0.05, 0.0), 0.7, MARK)
            if (tick % 4 == 0) GunnerFx.line(eye, g.aimPoint(t), GAZE, 0.7)
            // 合図の直前からはアクションバーを合図に譲る
            if (t is Player && tick % 5 == 0 && tick < settings.lockTick) t.sendActionBar(settings.seenMessage)
        }
    }

    /** 撃たれる相手を決める（近い順。shootTicks の数まで） */
    private fun lockTargets(g: GunnerBoss) {
        shotTargets = candidates(g).sortedBy { it.location.distanceSquared(g.entity.location) }
            .take(settings.shootTicks.size)
    }

    /** 撃つ直前の合図: その人にだけ、撃鉄を起こす音とアクションバー */
    private fun cue(t: LivingEntity?) {
        if (t !is Player || !t.isValid) return
        GunnerSounds.DUEL_CUE.forEach { t.playSound(t.location, it.sound, it.volume, it.pitch) }
        t.sendActionBar(settings.cueMessage)
        t.world.spawnParticle(Particle.DUST, t.eyeLocation.add(0.0, 0.55, 0.0), 6, 0.1, 0.1, 0.1, 0.0, CUE)
    }

    // ===== 早撃ち =====

    private fun draw(g: GunnerBoss) {
        g.stopAnimation(settings.waitAnimation)
        g.playOnce(settings.drawAnimation)
        // コインがきらめいて消える
        coin?.let { c ->
            c.world.spawnParticle(Particle.FLASH, c.location, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(255, 230, 160))
            c.world.spawnParticle(Particle.ELECTRIC_SPARK, c.location, 10, 0.1, 0.1, 0.1, 0.2)
        }
        removeCoin()
        if (shotTargets.isEmpty()) lockTargets(g)
    }

    private fun fireAt(g: GunnerBoss, index: Int) {
        val m = if (index % 2 == 0) GunnerBoss.Muzzle.RIGHT else GunnerBoss.Muzzle.LEFT
        val from = g.muzzle(m)
        val t = shotTargets.getOrNull(index)?.takeIf { it.isValid && !it.isDead }
        if (t == null) {
            // 相手がいなければ、最初の1発だけ空へ撃つ（見せ場）
            if (index == 0) {
                val up = Facing.forward(g.entity).setY(0.6).normalize()
                GunnerFx.muzzleFlash(from, up, big = true)
                GunnerSounds.DUEL_SHOT.forEach { it.play(g.entity) }
            }
            return
        }
        val aim = g.aimPoint(t)
        val v = aim.toVector().subtract(from.toVector())
        if (v.lengthSquared() < 1e-6) return
        val dir = v.clone().normalize()

        // 物陰も貫いて一直線に届く
        GunnerFx.muzzleFlash(from, dir, big = true)
        GunnerFx.sniperBeam(from, aim)
        GunnerSounds.DUEL_SHOT.forEach { it.play(g.entity) }

        if (!TargetFinder.isValidTarget(g, t)) return
        t.noDamageTicks = 0
        t.damage(settings.damage, g.entity)
        // ジャストガードできた: 無傷（ダメージ・吹き飛ばしは BossListener と StatusListener が止める）
        if (t is Player && g.status.justGuard.guardedNow(t)) return
        // それ以外は即死（盾で防いでいても、防具で耐えていても）
        if (!t.isDead && t.health > 0) t.health = 0.0
        val flat = dir.clone().setY(0)
        if (flat.lengthSquared() > 1e-4) t.velocity = flat.normalize().multiply(settings.knockback).setY(0.4)
        t.world.spawnParticle(Particle.DAMAGE_INDICATOR, aim, 6, 0.2, 0.3, 0.2, 0.1)
    }

    override fun stop(boss: Boss) {
        removeCoin()
        boss.stopAnimation(settings.waitAnimation)
        boss.entity.setAI(true)
        shotTargets = emptyList()
    }

    private companion object {
        const val COIN_SIZE = 0.45f
        val MARK = Particle.DustOptions(Color.fromRGB(255, 30, 30), 1.0f)
        val GAZE = Particle.DustOptions(Color.fromRGB(200, 20, 20), 0.45f)
        val CUE = Particle.DustOptions(Color.fromRGB(120, 230, 255), 1.4f)
    }
}
