package com.cinemaplus.rebuilt.data.iptv

import java.text.Normalizer
import java.util.Locale

/** TV search accepts Arabic spelling variants and physical/IME channel numbers alike. */
object IptvChannelSearch {
    fun matches(channel: IptvChannel, query: String, number: Int): Boolean {
        val text = normalize(query)
        if (text.isEmpty()) return true
        if (text.all { it in '0'..'9' } && text.toIntOrNull() == number && number > 0) return true
        return normalize(channel.name).contains(text) || normalize(channel.group).contains(text)
    }

    private fun normalize(value: String): String = buildString {
        for (character in Normalizer.normalize(value, Normalizer.Form.NFKD)) when (character) {
            in '\u064B'..'\u065F', '\u0670', '\u0640' -> Unit
            'أ', 'إ', 'آ' -> append('ا')
            'ى' -> append('ي')
            in '٠'..'٩' -> append('0' + (character - '٠'))
            in '۰'..'۹' -> append('0' + (character - '۰'))
            else -> append(character)
        }
    }.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
}
