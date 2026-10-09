package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class EpisodeLabelsTest {
    @Test fun realEpisodeNameIsKept() { assertEquals("Pilot", EpisodeLabels.title("Pilot", setOf("Breaking Bad"))) }
    @Test fun parentNamesAreHiddenAcrossLanguages() {
        assertEquals("", EpisodeLabels.title(" Show  Name ", setOf("show name", "اسم المسلسل")))
        assertEquals("", EpisodeLabels.title("اسم المسلسل", setOf("Show Name", "اسم المسلسل")))
    }
    @Test fun absentTitleStaysEmpty() { assertEquals("", EpisodeLabels.title(" ", setOf("Show"))) }
    @Test fun similarButDistinctEpisodeNameIsKept() { assertEquals("Show Finale", EpisodeLabels.title("Show Finale", setOf("Show"))) }
}
