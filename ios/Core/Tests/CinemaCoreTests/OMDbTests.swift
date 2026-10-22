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
}
