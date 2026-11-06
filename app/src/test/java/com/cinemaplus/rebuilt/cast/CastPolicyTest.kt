package com.cinemaplus.rebuilt.cast

import org.junit.Assert.*
import org.junit.Test

class CastPolicyTest {
    @Test fun receiverMimeTypesAndSignedQueries() {
        assertEquals("application/x-mpegURL", CastPolicy.mime("https://cdn.example/a.m3u8?signature=hidden"))
        assertEquals("video/mp4", CastPolicy.mime("https://cdn.example/vod?id=12&ext=mp4"))
        assertEquals("application/dash+xml", CastPolicy.mime("https://cdn.example/a.mpd"))
        assertNull(CastPolicy.mime("https://cdn.example/a.mkv"))
    }
    @Test fun rejectsCredentialsAndLocalResources() {
        listOf("file:///tmp/a.mp4", "https://user:password@cdn.example/a.mp4", "javascript:bad", "not a URL", "https:///a").forEach {
            assertFalse(CastPolicy.valid(it))
            assertNull(CastPolicy.mime(it))
        }
    }
    @Test fun onlyReceiverCompatibleSubtitles() {
        assertTrue(CastPolicy.subtitle("https://cdn.example/ar.vtt?token=x", "text/vtt"))
        assertFalse(CastPolicy.subtitle("https://cdn.example/ar.srt", "application/x-subrip"))
        assertFalse(CastPolicy.subtitle("file:///ar.vtt", "text/vtt"))
    }
}
