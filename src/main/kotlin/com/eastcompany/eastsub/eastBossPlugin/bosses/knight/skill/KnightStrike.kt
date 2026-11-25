package com.eastcompany.eastsub.eastBossPlugin.bosses.knight.skill

import com.eastcompany.eastsub.eastBossPlugin.boss.Boss
import com.eastcompany.eastsub.eastBossPlugin.boss.sound.SoundCue
import com.eastcompany.eastsub.eastBossPlugin.skill.BossSkill
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * 大剣の一振り（[inner]。ふつうは BoneStrikeSkill）に、決まった tick の追加の効果を足す
 * （振り下ろしの地割れ・本気の斬撃波など）。
 *
 * tick は振り始めてからの tick（inner.soundTick。踏み込みのダッシュ中は数えない）。
 * 同じ tick の効果は1回だけ。
 */
class KnightStrike(
    private val inner: BossSkill,
    private val events: Map<Int, (Boss) -> Unit>,
) : BossSkill {

    override val id: String get() = inner.id
    override val weight: Int get() = inner.weight
    override val sounds: List<SoundCue> get() = inner.sounds
    override val soundTick: Int? get() = inner.soundTick ?: ownTick

    private var ownTick = 0
    private val fired = HashSet<Int>()

    override fun canStart(boss: Boss): Boolean = inner.canStart(boss)

    override fun start(boss: Boss) {
        ownTick = 0
        fired.clear()
        inner.start(boss)
    }

    override fun tick(boss: Boss): Boolean {
        val t = soundTick ?: ownTick
        events[t]?.let { if (fired.add(t)) it(boss) }
        ownTick++
        return inner.tick(boss)
    }

    override fun stop(boss: Boss) = inner.stop(boss)

    override fun modifyIncomingDamage(boss: Boss, event: EntityDamageByEntityEvent) =
        inner.modifyIncomingDamage(boss, event)
}
