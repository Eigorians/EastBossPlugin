package com.eastcompany.eastsub.eastBossPlugin.record

/** クリアタイム（tick）を「分:秒.小数2桁」の文字列にする（例: 1:23.45） */
object TimeFormat {

    fun format(ticks: Int): String {
        val millis = ticks * 50L
        val minutes = millis / 60_000
        val seconds = (millis % 60_000) / 1000.0
        return "%d:%05.2f".format(minutes, seconds)
    }
}
