package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvChannelDisplayNameTest {
    @Test fun removesCountryTagsWithoutRemovingGenuineCountrySuffixes() {
        assertEquals("MBC Iraq", IptvChannelDisplayName.of("IQ: MBC Iraq", "Iraq"))
        assertEquals("Sama Dubai", IptvChannelDisplayName.of("AE:Sama Dubai", "UAE"))
        assertEquals("MBC Iraq", IptvChannelDisplayName.of("MBC Iraq", "Iraq"))
    }

    @Test fun removesRepeatedExplicitCategoryAndCountryPrefixes() {
        assertEquals("MBC Iraq", IptvChannelDisplayName.of("Iraq | IQ: MBC Iraq", "Iraq"))
        assertEquals("MBC Iraq", IptvChannelDisplayName.of("Iraq: [IQ] (Iraq) MBC Iraq", "Iraq"))
        assertEquals("العراقية HD", IptvChannelDisplayName.of("العراق | IQ: العراقية HD", "العراق"))
    }

    @Test fun supportsBracketParenthesisColonAndPipeBoundaries() {
        for (name in listOf("[IQ] MBC Iraq", "(IQ) MBC Iraq", "IQ: MBC Iraq", "IQ | MBC Iraq")) {
            assertEquals(name, "MBC Iraq", IptvChannelDisplayName.of(name, "Iraq"))
        }
        for (name in listOf("[Iraq] MBC Iraq", "(Iraq) MBC Iraq", "Iraq: MBC Iraq", "Iraq | MBC Iraq")) {
            assertEquals(name, "MBC Iraq", IptvChannelDisplayName.of(name, "Iraq"))
        }
    }

    @Test fun recognizesIsoCountryTagsAndTheCommonUkAliasOnlyWhenExplicit() {
        assertEquals("BBC One HD", IptvChannelDisplayName.of("UK: BBC One HD", "General"))
        assertEquals("BBC One HD", IptvChannelDisplayName.of("[GB] BBC One HD", "General"))
        assertEquals("CNN", IptvChannelDisplayName.of("US | CNN", "General"))
        assertEquals("ZZ: Example", IptvChannelDisplayName.of("ZZ: Example", "General"))
        assertEquals("[ZZ] Example", IptvChannelDisplayName.of("[ZZ] Example", "General"))
    }

    @Test fun preservesBarePrefixesAndUnrecognizedSeparators() {
        for (name in listOf("IQ MBC Iraq", "Iraq MBC Iraq", "Iraqi: News", "IraqNet", "IQ - MBC Iraq", "Iraq - MBC Iraq")) {
            assertEquals(name, name, IptvChannelDisplayName.of(name, "Iraq"))
        }
    }

    @Test fun preservesBrandLabelsAndNonMatchingCategoryNames() {
        for (name in listOf("BBC: Arabic", "OSN: Movies", "OSN TV Movies Comedy", "Sports: Arena", "[OSN] Cinema")) {
            assertEquals(name, name, IptvChannelDisplayName.of(name, "Iraq"))
        }
        assertEquals("BBC: Arabic", IptvChannelDisplayName.of("IQ: BBC: Arabic", "Iraq"))
    }

    @Test fun preservesResolutionTagsNumbersAndInternalBrandSpacing() {
        assertEquals("beIN  Sports HD 1 FHD UHD 4K", IptvChannelDisplayName.of("  IQ: beIN  Sports HD 1 FHD UHD 4K  ", "Iraq"))
        for (name in listOf("SD: Arena 0", "HD: Arena 1", "FHD: Arena 2", "UHD: Arena 3", "4K: Arena 4")) {
            assertEquals(name, name, IptvChannelDisplayName.of(name, "Iraq"))
        }
        assertEquals("OSN  Movies", IptvChannelDisplayName.of("  OSN  Movies  ", "Iraq"))
    }

    @Test fun emptyInputHasAnArabicFallbackButPrefixOnlyInputKeepsItsOriginalLabel() {
        assertEquals("قناة", IptvChannelDisplayName.of("", "Iraq"))
        assertEquals("قناة", IptvChannelDisplayName.of("  \t\n", "Iraq"))
        assertEquals("IQ:", IptvChannelDisplayName.of("  IQ:  ", "Iraq"))
        assertEquals("[IQ]", IptvChannelDisplayName.of("[IQ]", "Iraq"))
        assertEquals("Iraq | IQ:", IptvChannelDisplayName.of("Iraq | IQ:", "Iraq"))
    }

    @Test fun channelOverloadOnlyChangesPresentationAndKeepsRawIdentityMetadataAndUrl() {
        val channel = IptvChannel("subscription-one:stable-id", "subscription-one", "IQ: MBC Iraq", "Iraq",
            "https://media.example/live.m3u8?token=private", "https://media.example/logo.png", "mbc-iq")
        val original = channel.copy()

        assertEquals("MBC Iraq", IptvChannelDisplayName.of(channel))
        assertEquals(original, channel)
        assertEquals("IQ: MBC Iraq", channel.name)
        assertEquals("subscription-one:stable-id", channel.id)
        assertEquals("mbc-iq", channel.tvgId)
    }
}
