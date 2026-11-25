package com.eastcompany.eastsub.eastBossPlugin.message

import com.eastcompany.eastsub.eastBossPlugin.Names
import com.eastcompany.eastsub.eastBossPlugin.config.ConfigManager
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * config.yml の messages セクションからメッセージを組み立てて送る。
 * 先頭には prefix を付ける。
 *
 * placeholders は `"type" to "boss"` のように渡すと `<type>` が置き換わる。
 * 値が Component ならそのまま（色やクリック動作付きで）入れ、
 * それ以外は文字列にして MiniMessage として解釈せずに入れる。
 */
class Messages(private val configManager: ConfigManager) {

    /** メッセージを送る（prefix 付き） */
    fun send(target: Audience, key: String, vararg placeholders: Pair<String, Any>) {
        val prefix = configManager.main.component(Names.Config.PREFIX)
        target.sendMessage(prefix.append(component(key, *placeholders)))
    }

    /** 複数人にまとめて送る */
    fun send(targets: Iterable<Audience>, key: String, vararg placeholders: Pair<String, Any>) {
        targets.forEach { send(it, key, *placeholders) }
    }

    /** メッセージを Component として組み立てる（prefix なし。ボタンや表示用） */
    fun component(key: String, vararg placeholders: Pair<String, Any>): Component {
        val resolver = TagResolver.resolver(placeholders.map { (name, value) ->
            if (value is Component) Placeholder.component(name, value) else Placeholder.unparsed(name, value.toString())
        })
        return configManager.main.component(Names.Config.message(key), resolver)
    }
}
