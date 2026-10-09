package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class WatchNextLaunchPolicyTest {
    private fun film(id: String) = Film(id, id, "", "")

    @Test fun matchingProfileStartsRequestedTitle() {
        val title = film("one")
        assertEquals(WatchNextLaunchPolicy.Result.Play(title),
            WatchNextLaunchPolicy.resolve("one", "main", "main", listOf(title)))
    }
    @Test fun oldIntentsRemainCompatible() {
        val title = film("one")
        assertEquals(WatchNextLaunchPolicy.Result.Play(title),
            WatchNextLaunchPolicy.resolve("one", null, "main", listOf(title)))
    }
    @Test fun anotherProfileCannotStartEvenIfTitleExists() {
        assertEquals(WatchNextLaunchPolicy.Result.WrongProfile,
            WatchNextLaunchPolicy.resolve("one", "other", "main", listOf(film("one"))))
    }
    @Test fun obsoleteEpisodeDoesNotPlayItsReplacement() {
        assertEquals(WatchNextLaunchPolicy.Result.Missing,
            WatchNextLaunchPolicy.resolve("old", "main", "main", listOf(film("next"))))
    }
}
