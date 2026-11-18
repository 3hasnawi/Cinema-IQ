package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvChannelSearchTest {
    private val channel = IptvChannel("one", "source", "الأَخْبَار الأولى HD", "قنوات إخبارية", "https://example.test/live.m3u8")

    @Test fun arabicSpellingAndDiacriticsDoNotHideAChannel() {
        assertTrue(IptvChannelSearch.matches(channel, "الاخبار", 12))
        assertTrue(IptvChannelSearch.matches(channel, "اﻷخبار", 12))
        assertTrue(IptvChannelSearch.matches(channel, "الاولي", 12))
        assertTrue(IptvChannelSearch.matches(channel, "اخبارية", 12))
    }

    @Test fun arabicAndLatinChannelNumbersSelectTheSameStation() {
        listOf("12", "١٢", "۱۲", "012").forEach { assertTrue(IptvChannelSearch.matches(channel, it, 12)) }
        assertFalse(IptvChannelSearch.matches(channel, "13", 12))
        assertFalse(IptvChannelSearch.matches(channel, "0", 0))
    }

    @Test fun englishAndWhitespaceAreInsensitive() {
        assertTrue(IptvChannelSearch.matches(channel, "  hd  ", 12))
        assertTrue(IptvChannelSearch.matches(channel, "", 12))
        assertFalse(IptvChannelSearch.matches(channel, "رياضة", 12))
    }

    @Test fun numericChannelNamesStillMatchWhenTheirGuideNumberDiffers() {
        assertTrue(IptvChannelSearch.matches(channel.copy(name = "Sport 24"), "24", 5))
    }
}
