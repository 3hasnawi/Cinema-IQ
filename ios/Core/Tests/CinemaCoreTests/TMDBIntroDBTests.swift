import XCTest
@testable import CinemaCore

final class TMDBIntroDBTests: XCTestCase {
    func testPickMatchesTitleAndYear() {
        let results: [[String: Any]] = [
            ["id": 1, "name": "Slow Horses", "first_air_date": "2010-01-01", "poster_path": "/a.jpg"],
            ["id": 2, "name": "Slow Horses", "first_air_date": "2022-04-01", "poster_path": "/b.jpg"],
        ]
        XCTAssertEqual(TMDBParser.pick(results, title: "slow horses!", year: 2022)?["id"] as? Int, 2)
        XCTAssertNil(TMDBParser.pick(results, title: "Fast Horses", year: nil))
    }
    func testWorkReadsCastPortraitsAndImdb() {
        let data: [String: Any] = ["id": 9, "first_air_date": "2024-01-17", "overview": "قصة",
            "external_ids": ["imdb_id": "tt123"],
            "credits": ["cast": [["id": 5, "name": "Lee Dong-wook", "profile_path": "/p.jpg"], ["id": 5, "name": "Dup"], ["id": 6, "name": "Kim"]],
                        "crew": [["job": "Director", "name": "Lee Kwon"]]],
            "created_by": [["name": "Ji Ho-jin"]]]
        let work = TMDBParser.work(data, series: true)
        XCTAssertEqual(work.imdbID, "tt123")
        XCTAssertEqual(work.cast.map(\.name), ["Lee Dong-wook", "Kim"])
        XCTAssertEqual(work.cast.first?.photo, "https://image.tmdb.org/t/p/w185/p.jpg")
        XCTAssertEqual(work.directors, ["Lee Kwon"]); XCTAssertEqual(work.creators, ["Ji Ho-jin"]); XCTAssertEqual(work.year, "2024")
    }
    func testSeasonEpisodes() {
        let season = TMDBParser.season(["air_date": "2026-07-22", "episodes": [["episode_number": 1, "air_date": "2026-07-22", "name": "Jinman", "still_path": "/s.jpg"]]])
        XCTAssertEqual(season.airDate, "2026-07-22")
        XCTAssertEqual(season.episodes[1]?.still, "https://image.tmdb.org/t/p/w780/s.jpg")
    }
    func testIntroDBSegmentsForTheSameEpisodeOnly() {
        let json = #"{"imdb_id":"tt123","is_movie":false,"season":1,"episode":2,"intro":{"start_ms":5000,"end_ms":65000},"outro":{"start_sec":2400,"end_sec":2500}}"#
        let found = IntroDBParser.parse(Data(json.utf8), imdbID: "tt123", season: 1, episode: 2, movie: false)
        XCTAssertEqual(found.introStart, 5); XCTAssertEqual(found.introEnd, 65); XCTAssertEqual(found.creditsStart, 2400)
        XCTAssertTrue(found.inIntro(30)); XCTAssertFalse(found.inIntro(64)); XCTAssertFalse(found.inIntro(2))
        XCTAssertEqual(IntroDBParser.parse(Data(json.utf8), imdbID: "tt123", season: 1, episode: 3, movie: false), SkipSegments())
    }
}
