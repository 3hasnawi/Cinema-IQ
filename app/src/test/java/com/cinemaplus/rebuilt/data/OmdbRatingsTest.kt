package com.cinemaplus.rebuilt.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OmdbRatingsTest {
    @Test fun acceptsOnlyTheRequestedTitleId() {
        val data = JSONObject("""{"Response":"True","imdbID":"tt123","imdbRating":"8.4"}""")
        assertEquals("8.4", OmdbRatings.parseRating(data, "tt123", null, null))
        assertEquals("", OmdbRatings.parseRating(data, "tt999", null, null))
    }
    @Test fun episodeMustMatchParentSeasonAndEpisode() {
        val data = JSONObject("""{"Response":"True","imdbID":"tt456","seriesID":"tt123","Season":"2","Episode":"3","imdbRating":"9.1"}""")
        assertEquals("9.1", OmdbRatings.parseRating(data, "tt123", 2, 3))
        assertEquals("", OmdbRatings.parseRating(data, "tt123", 1, 3))
        assertEquals("", OmdbRatings.parseRating(data, "tt123", 2, 4))
        assertEquals("", OmdbRatings.parseRating(data, "tt999", 2, 3))
        data.remove("seriesID")
        assertEquals("", OmdbRatings.parseRating(data, "tt123", 2, 3))
    }
    @Test fun doesNotTreatMissingOrInvalidScoresAsRatings() {
        for(score in listOf("N/A", "", "0", "11", "NaN", "Infinity")) {
            val data = JSONObject().put("Response", "True").put("imdbID", "tt123").put("imdbRating", score)
            assertEquals("", OmdbRatings.parseRating(data, "tt123", null, null))
        }
        assertEquals("", OmdbRatings.parseRating(JSONObject("""{"Response":"False","Error":"Request limit reached!"}"""), "tt123", null, null))
    }
}
