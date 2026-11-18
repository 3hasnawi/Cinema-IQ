package com.cinemaplus.rebuilt.data.iptv

import java.text.Normalizer
import java.util.Locale

/** Presentation only: never use this label as a channel ID, search key or provider catalogue value. */
object IptvChannelDisplayName {
    private val countryTags = Locale.getISOCountries().toSet() + "UK"

    fun of(channel: IptvChannel): String = of(channel.name, channel.group)

    fun of(name: String, group: String): String {
        val original = name.trim()
        if (original.isEmpty()) return "قناة"
        val normalizedGroup = normalized(group)
        var display = original
        // Providers can prepend both a category label and a country tag to the same title.
        repeat(4) {
            val next = removePrefix(display, normalizedGroup) ?: return display
            if (next.isBlank()) return original
            display = next
        }
        return display
    }

    private fun removePrefix(name: String, group: String): String? {
        if (name.startsWith('[') || name.startsWith('(')) {
            val close = if (name[0] == '[') ']' else ')'
            val end = name.indexOf(close, 1)
            if (end > 1 && removable(name.substring(1, end), group)) {
                return name.substring(end + 1).trimStart().removeOptionalSeparator()
            }
        }
        for (index in name.indices) {
            if ((name[index] == ':' || name[index] == '|') && removable(name.substring(0, index), group)) {
                return name.substring(index + 1).trimStart()
            }
        }
        return null
    }

    private fun removable(prefix: String, group: String): Boolean {
        val label = normalized(prefix)
        if (group.isNotEmpty() && label.equals(group, ignoreCase = true)) return true
        // SD is also an ISO country code; keep the common quality label unless it is the exact group.
        if (label.equals("SD", ignoreCase = true)) return false
        return label.length == 2 && label.all { it in 'A'..'Z' || it in 'a'..'z' } &&
            label.uppercase(Locale.ROOT) in countryTags
    }

    private fun normalized(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
    private fun String.removeOptionalSeparator(): String =
        if (startsWith(':') || startsWith('|')) substring(1).trimStart() else this
}
