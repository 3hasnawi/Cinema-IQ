package com.cinemaplus.rebuilt.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class GenreTest {
    @Test fun parsesCategoriesAndSkipsAllEntry() {
        val json = JSONArray("""[{"nb":"0","ar_title":"الكل"},{"nb":"3","ar_title":"أكشن","en_title":"Action"},{"nb":"5","en_title":"Drama"},{"nb":"","ar_title":"x"}]""")
        assertEquals(listOf(Category("3", "أكشن"), Category("5", "Drama")), JsonMapper.categories(json))
    }
    @Test fun categoryIsPartOfCatalogPath() {
        assertEquals("video/V/2/videoKind/1/categoryNb/7/langNb/0/sortParam/desc/itemsPerPage/10/video_title_search/-/pageNumber/0/enableTranslation/0/level/1",
            CatalogRequest.path(false, "-", 1, "7"))
    }
}
