import XCTest
@testable import CinemaCore

final class OMDbTests: XCTestCase {
    func testParsesInfoAndDropsNotAvailable() throws {
        let json = #"{"Title":"The Wild Robot","Year":"2024","Rated":"PG","Runtime":"102 min","Genre":"Animation, Adventure","Director":"Chris Sanders","Actors":"Lupita Nyong'o, Pedro Pascal","Plot":"A robot is shipwrecked.","Poster":"https://m.media-amazon.com/images/M/abc._V1_SX300.jpg","Ratings":[{"Source":"Internet Movie Database","Value":"8.2/10"},{"Source":"Rotten Tomatoes","Value":"97%"},{"Source":"Metacritic","Value":"N/A"}],"imdbRating":"8.2","imdbVotes":"150,000","imdbID":"tt29623480","Type":"movie","Response":"True"}"#
        let info = try XCTUnwrap(OMDbParser.info(Data(json.utf8)))
        XCTAssertEqual(info.imdbRating, "8.2"); XCTAssertEqual(info.rottenTomatoes, "97%"); XCTAssertEqual(info.metacritic, "")
        XCTAssertEqual(info.largePoster, "https://m.media-amazon.com/images/M/abc._V1_SX1000.jpg")
        XCTAssertNil(OMDbParser.info(Data(#"{"Response":"False","Error":"Movie not found!"}"#.utf8)))
    }
    func testSearchTitlePrefersLatinNames() {
        var arabic = Media(id: "1", title: "الروبوت البري"); arabic.enTitle = "The Wild Robot (2024)"; arabic.year = "2024"
        XCTAssertEqual(OMDbParser.searchTitle(arabic), "The Wild Robot")
        XCTAssertEqual(OMDbParser.year(arabic), "2024")
        XCTAssertNil(OMDbParser.searchTitle(Media(id: "2", title: "حبك نار")))
        XCTAssertEqual(OMDbParser.searchTitle(Media(id: "vodu:3", title: "Slow Horses Season 4")), "Slow Horses")
    }
    func testSeasonRatingsByEpisode() {
        let json = #"{"Title":"Slow Horses","Season":"1","Episodes":[{"Title":"Failure's Contagious","Episode":"1","imdbRating":"7.9"},{"Title":"Work Drinks","Episode":"2","imdbRating":"N/A"},{"Title":"Visiting Hours","Episode":"3","imdbRating":"8.1"}],"Response":"True"}"#
        XCTAssertEqual(OMDbParser.seasonRatings(Data(json.utf8)), [1: "7.9", 3: "8.1"])
        XCTAssertNil(OMDbParser.seasonRatings(Data(#"{"Response":"False","Error":"Series or season not found!"}"#.utf8)))
    }
    func testSeasonReleasesByEpisode() {
        let json = #"{"Episodes":[{"Episode":"1","Released":"2022-04-01"},{"Episode":"2","Released":"N/A"},{"Episode":"3","Released":"2022-04-15"}],"Response":"True"}"#
        XCTAssertEqual(OMDbParser.seasonReleases(Data(json.utf8)), [1: "2022-04-01", 3: "2022-04-15"])
    }
    func testAirDatesGiveSeasonYearAndDisplay() {
        var first = Media(id: "a", title: "a"); first.airDate = "2019-09-12 21:00:00"
        var second = Media(id: "b", title: "b"); second.airDate = "2018-12-30"
        let none = Media(id: "c", title: "c")
        XCTAssertEqual(AirDate.year(of: Season(id: 1, episodes: [first, second, none])), 2018)
        XCTAssertNil(AirDate.year(of: Season(id: 2, episodes: [none])))
        XCTAssertEqual(AirDate.display("2018-12-30"), "30 ديسمبر 2018")
        XCTAssertEqual(AirDate.display("nonsense"), "")
        // Kept through the library's JSON, and left out when empty.
        let decoded = try? JSONDecoder().decode(Media.self, from: JSONEncoder().encode(second))
        XCTAssertEqual(decoded?.airDate, "2018-12-30")
        XCTAssertFalse(String(decoding: try! JSONEncoder().encode(none), as: UTF8.self).contains("airDate"))
    }
}
