package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class SubtitleFormatTest {
    @Test fun detectsSubRipRatherThanDeclaringItWebVtt() {
        assertEquals("application/x-subrip", SubtitleFormat.mimeType("1\n00:00:01,000 --> 00:00:03,000\nHello\n"))
    }
    @Test fun acceptsWebVttBomAndShortTimestamps() {
        assertEquals("text/vtt", SubtitleFormat.mimeType("\uFEFFWEBVTT\n\n00:01.000 --> 00:03.000\nHello\n"))
    }
    @Test fun supportsWindowsLineEndings() {
        assertEquals("application/x-subrip", SubtitleFormat.mimeType("1\r\n00:00:01,000 --> 00:00:03,000\r\nHello\r\n"))
    }
    @Test fun rejectsHtmlEmptyAndUnrecognizedResponses() {
        assertNull(SubtitleFormat.mimeType("<html>Please log in</html>"))
        assertNull(SubtitleFormat.mimeType(""))
        assertNull(SubtitleFormat.mimeType("WEBVTT\n"))
    }
    @Test fun knownLanguagesShareOptionsButUnknownLanguagesRemainSeparate() {
        assertEquals(SubtitleFormat.groupKey(Subtitle("a", "ar", "العربية · 1")), SubtitleFormat.groupKey(Subtitle("b", "ar", "العربية · 2")))
        assertNotEquals(SubtitleFormat.groupKey(Subtitle("a", "und")), SubtitleFormat.groupKey(Subtitle("b", "und")))
        assertEquals("العربية", SubtitleFormat.displayLabel(Subtitle("a", "ar", "العربية · 2")))
    }
}
