package com.cinemaplus.rebuilt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TmdbCreditsTest {
    @Test fun parsesCastAndDirectorsWithoutCallingOtherCrewDirectors() {
        val result = TmdbMetadata.parseCredits(JSONObject("""{
            "release_date":"2025-06-10","credits":{
                "cast":[{"id":1,"name":"Actor","profile_path":"/actor.jpg"},
                    {"id":1,"name":"Actor"},{"id":2,"name":"No photo","profile_path":null},{"id":3,"name":""}],
                "crew":[{"name":"Director","job":"Director"},{"name":"Writer","job":"Writer"}]}
        }"""), false)
        assertEquals(listOf("Actor", "No photo"), result.cast.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w185/actor.jpg", result.cast[0].image)
        assertEquals("", result.cast[1].image)
        assertEquals(listOf("Director"), result.directors)
        assertEquals("2025", result.year)
        assertTrue(result.creators.isEmpty())
    }
    @Test fun keepsSeriesCreatorsDistinctFromDirectorsAndHandlesMissingData() {
        val result = TmdbMetadata.parseCredits(JSONObject("""{"first_air_date":"2020-01-01",
            "created_by":[{"name":"Creator"}]}"""), true)
        assertEquals(listOf("Creator"), result.creators)
        assertTrue(result.directors.isEmpty())
        assertEquals("2020", result.year)
        val missing = TmdbMetadata.parseCredits(JSONObject(), false)
        assertTrue(missing.cast.isEmpty())
        assertEquals("", missing.year)
    }
}
