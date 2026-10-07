package com.cinemaplus.rebuilt.data

/** UI page 1 is source page 0, as verified in the original client's Movies/Series groups. */
internal object CatalogRequest {
    /** [category] is the source category number; "0" means all categories. */
    fun path(series: Boolean, encodedQuery: String, page: Int, category: String = "0"): String {
        require(page >= 1) { "UI pages start at one" }
        return "video/V/2/videoKind/${if(series) 2 else 1}/categoryNb/$category/langNb/0/sortParam/desc/itemsPerPage/10/video_title_search/$encodedQuery/pageNumber/${page - 1}/enableTranslation/0/level/1"
    }
}
