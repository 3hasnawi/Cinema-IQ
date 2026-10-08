package com.cinemaplus.rebuilt.data

import com.cinemaplus.rebuilt.ui.TrailerResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerAndRatingTest {
    @Test fun parsesYoutubeIdsFromLinksAndRawIds() {
        assertEquals("dQw4w9WgXcQ", TrailerResolver.parseId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"))
        assertEquals("dQw4w9WgXcQ", TrailerResolver.parseId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", TrailerResolver.parseId("dQw4w9WgXcQ"))
        assertNull(TrailerResolver.parseId(""))
        assertNull(TrailerResolver.parseId("not a link"))
    }
    @Test fun ratingUsesFirstPositiveKeyAndNormalizes() {
        assertEquals("7.8", JsonMapper.rating(JSONObject("""{"stars":"0","rate":"7.8"}""")))
        assertEquals("8.1", JsonMapper.rating(JSONObject("""{"stars":81}""")))
        assertEquals("", JsonMapper.rating(JSONObject("""{"stars":"0"}""")))
        assertEquals("", JsonMapper.rating(JSONObject("{}")))
    }
    @Test fun imdbBadgeRequiresAnExplicitProviderField() {
        assertEquals("", JsonMapper.imdbRating(JSONObject("""{"stars":8.5,"tmdbRate":8.1}""")))
        assertEquals("8.7", JsonMapper.imdbRating(JSONObject("""{"imdbRate":87}""")))
        assertEquals("7.9", JsonMapper.imdbRating(JSONObject("""{"imdbRating":"7,9"}""")))
        val episode = JsonMapper.film(JSONObject("""{"nb":"episode-1","episode":1,"description":"Episode synopsis","stars":9,"imdb_rate":8.2}"""))!!
        assertEquals("Episode synopsis", episode.description)
        assertEquals("8.2", episode.imdbRating)
        assertEquals("9.0", episode.rating)
    }
}
