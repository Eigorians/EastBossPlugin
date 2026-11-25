package com.eastcompany.eastsub.eastBossPlugin.bosses

import com.eastcompany.eastsub.eastBossPlugin.boss.BossRegistry
import com.eastcompany.eastsub.eastBossPlugin.bosses.assassin.AssassinType
import com.eastcompany.eastsub.eastBossPlugin.bosses.dragon.DragonType
import com.eastcompany.eastsub.eastBossPlugin.bosses.giant.GiantType
import com.eastcompany.eastsub.eastBossPlugin.bosses.gunner.GunnerType
import com.eastcompany.eastsub.eastBossPlugin.bosses.knight.KnightType
import com.eastcompany.eastsub.eastBossPlugin.bosses.tutorialalpha.TutorialAlphaType
import com.eastcompany.eastsub.eastBossPlugin.bosses.veigar.VeigarType

/**
 * 実装済みのボスの一覧。
 * ボスを追加するときは bosses/<名前>/ に BossType と Boss を作り、ここに1行足す。
 * あわせて src/main/resources/bosses/<ボスID>.yml を用意すること。
 */
object Bosses {

    fun registerAll(registry: BossRegistry) {
        registry.register(TutorialAlphaType)
        registry.register(VeigarType)
        registry.register(GunnerType)
        registry.register(DragonType)
        registry.register(AssassinType)
        registry.register(GiantType)
        registry.register(KnightType)
    }
}
