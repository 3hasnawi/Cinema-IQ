package com.cinemaplus.rebuilt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRequestTest {
    // Recorded request contract from the original APK, including its first-page index.
    @Test fun firstMovieSearchMatchesOriginalClient() {
        assertEquals("video/V/2/videoKind/1/categoryNb/0/langNb/0/sortParam/desc/itemsPerPage/10/video_title_search/VGVzdA%3D%3D/pageNumber/0/enableTranslation/0/level/1",
            CatalogRequest.path(false, "VGVzdA%3D%3D", 1))
    }
    @Test fun seriesPaginationKeepsItsKindAndQuery() {
        assertEquals("video/V/2/videoKind/2/categoryNb/0/langNb/0/sortParam/desc/itemsPerPage/10/video_title_search/VGVzdA%3D%3D/pageNumber/1/enableTranslation/0/level/1",
            CatalogRequest.path(true, "VGVzdA%3D%3D", 2))
    }
    @Test fun unfilteredCatalogAlsoStartsAtSourceZero() {
        assertEquals("video/V/2/videoKind/1/categoryNb/0/langNb/0/sortParam/desc/itemsPerPage/10/video_title_search/-/pageNumber/0/enableTranslation/0/level/1",
            CatalogRequest.path(false, "-", 1))
    }
}
