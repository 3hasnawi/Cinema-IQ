import XCTest
@testable import CinemaCore

final class FeaturedMoviesTests: XCTestCase {
    private func media(_ id: String, series: Bool = false, episode: Int = 0, wide: Bool = false) -> Media {
        var item = Media(id: id, title: "Work " + id)
        item.isSeries = series; item.episode = episode
        if wide { item.backdrop = "https://img.example/" + id + ".jpg" }
        return item
    }

    func testCuratedMoviesWinOverEarlierRowsAndWideArtwork() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "featured-home", withExtension: "json"))
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        let groups = CinemanaClient.rows(object)
        let selected = CinemanaClient.homeGroups(groups, prioritizeFeaturedMovies: true)
        XCTAssertEqual(selected.compactMap { $0["groupsID"] as? String }, ["1","2","3","4","5","6","7","8","10"])
        let shelves = selected.map { Shelf(id: $0["groupsID"] as! String,
                                          title: $0["ar_title"] as! String, items: CinemanaClient.items($0["content"])) }
        let result = FeaturedMovies.select(from: shelves)
        XCTAssertEqual(result.map(\.id), ["301", "302"])
        XCTAssertFalse(result[0].poster.isEmpty)
        XCTAssertEqual(result[0].poster, "https://cnth2.shabakaty.cc/posters/featured.jpg")
        XCTAssertEqual(result[0].backdrop, result[0].poster, "Keep the curated poster fallback rather than an unrelated wide image")
    }

    func testFeaturedMoviesArePreferredToMixedHighlightsAndKeepOrder() {
        let shelves = [Shelf(id: "featured", title: "مختارات", items: [media("1", wide: true)]),
                       Shelf(id: "featuredMovies", title: "Featured Movies", items: [media("4"), media("3"), media("4"), media("5", series: true), media("6", episode: 2)]),
                       Shelf(id: "featured-series", title: "مسلسلات مميزة", items: [media("7")])]
        XCTAssertEqual(FeaturedMovies.select(from: shelves).map(\.id), ["4", "3"])
    }

    func testGenericFeaturedGroupOnlyContributesMovies() {
        let shelves = [Shelf(id: "latest", title: "أحدث الأفلام", items: [media("1")]),
                       Shelf(id: "featured", title: "مميز على دورلي", items: [media("2", series: true), media("3"), media("4", episode: 1)])]
        XCTAssertEqual(FeaturedMovies.select(from: shelves).map(\.id), ["3"])
    }

    func testUncuratedFallbackSkipsBlankCardsWhenMovieArtworkExists() {
        let shelves = [Shelf(id: "films", title: "الأفلام", items: [media("1", wide: true), media("2")])]
        XCTAssertEqual(FeaturedMovies.select(from: shelves).map(\.id), ["1"])
    }

    func testProvidersWithoutHighlightsFallBackToMoviesOnly() {
        let shelves = [Shelf(id: "series", title: "أحدث المسلسلات", items: [media("1")]),
                       Shelf(id: "movies", title: "أحدث الأفلام", items: [media("2"), media("3", series: true), media("4", episode: 2), media("5"), media("2")])]
        XCTAssertEqual(FeaturedMovies.select(from: shelves).map(\.id), ["2", "5"])
        XCTAssertEqual(FeaturedMovies.select(from: shelves, limit: 1).map(\.id), ["2"])
        XCTAssertTrue(FeaturedMovies.select(from: shelves, limit: 0).isEmpty)
    }

    func testFeaturedTitlesNormalizeArabicAndIdentifiersWithoutPromotingOtherSections() {
        XCTAssertEqual(FeaturedMovies.priority(id: "42", title: "الأَفْلَام المُمَيَّزَة"), 2)
        XCTAssertEqual(FeaturedMovies.priority(id: "featured_movies", title: "المحتوى"), 2)
        XCTAssertEqual(FeaturedMovies.priority(id: "featuredMovies", title: "المحتوى"), 2)
        XCTAssertEqual(FeaturedMovies.priority(id: "featured-series", title: "المسلسلات المميزة"), 0)
        XCTAssertEqual(FeaturedMovies.priority(id: "99", title: "الأعلى تقييماً"), 0)
    }

    func testOrdinaryHomeRequestsAndExtraRequestBudgetRemainBounded() {
        var groups: [[String: Any]] = (1...12).map { ["groupsID": String($0), "ar_title": "أفلام مميزة"] }
        XCTAssertEqual(CinemanaClient.homeGroups(groups, prioritizeFeaturedMovies: false).count, 8)
        XCTAssertEqual(CinemanaClient.homeGroups(groups, prioritizeFeaturedMovies: true).count, 8)
        for i in 0..<8 { groups[i]["ar_title"] = "أحدث الأفلام" }
        XCTAssertEqual(CinemanaClient.homeGroups(groups, prioritizeFeaturedMovies: true).count, 10)
        XCTAssertTrue(CinemanaClient.homeGroups([], prioritizeFeaturedMovies: true).isEmpty)
        XCTAssertTrue(FeaturedMovies.select(from: []).isEmpty)
    }
}
