package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class SubtitleDiagnosticTest {
    @Test fun extractsExtensionWithoutQueryOrToken() {
        assertEquals("srt", SubtitleDiagnostic.extension("https://example.com/Arabic.SRT?token=secret"))
        assertEquals("vtt", SubtitleDiagnostic.extension("https://example.com/English.vtt#fragment"))
        assertEquals("unknown", SubtitleDiagnostic.extension("https://example.com/download?file=English.vtt&token=secret"))
    }
    @Test fun stableReferenceDoesNotExposeUrl() {
        val url = "https://example.com/Arabic.vtt?token=secret"
        val ref = SubtitleDiagnostic.reference(url)
        assertEquals(12, ref.length)
        assertEquals(ref, SubtitleDiagnostic.reference(url))
        assertFalse(ref.contains("secret"))
        assertNotEquals(ref, SubtitleDiagnostic.reference("https://example.com/English.vtt"))
    }
}
