package com.cinemaplus.rebuilt.data.iptv

import java.text.Normalizer
import java.util.Locale

/** TV search accepts Arabic spelling variants and physical/IME channel numbers alike. */
object IptvChannelSearch {
    fun matches(channel: IptvChannel, query: String, number: Int): Boolean {
        val prepared = prepareQuery(query)
        return prepared.isBlank || prepared.matches(prepareChannel(channel, number))
    }

    internal class PreparedChannel internal constructor(
        internal val name: String, internal val group: String, internal val number: Int
    )

    internal class PreparedQuery internal constructor(private val text: String, private val number: Int?) {
        val isBlank: Boolean get() = text.isEmpty()
        fun matches(channel: PreparedChannel): Boolean = isBlank ||
            (number != null && number > 0 && number == channel.number) ||
            channel.name.contains(text) || channel.group.contains(text)
    }

    internal fun prepareChannel(channel: IptvChannel, number: Int): PreparedChannel =
        PreparedChannel(normalize(channel.name), normalize(channel.group), number)

    internal fun prepareQuery(query: String): PreparedQuery {
        val text = normalize(query)
        return PreparedQuery(text, text.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }?.toIntOrNull())
    }

    private val whitespace = Regex("\\s+")

    private fun normalize(value: String): String = buildString {
        for (character in Normalizer.normalize(value, Normalizer.Form.NFKD)) when (character) {
            in '\u064B'..'\u065F', '\u0670', '\u0640' -> Unit
            'أ', 'إ', 'آ' -> append('ا')
            'ى' -> append('ي')
            in '٠'..'٩' -> append('0' + (character - '٠'))
            in '۰'..'۹' -> append('0' + (character - '۰'))
            else -> append(character)
        }
    }.lowercase(Locale.ROOT).trim().replace(whitespace, " ")
}
