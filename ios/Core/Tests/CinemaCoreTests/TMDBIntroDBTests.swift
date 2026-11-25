import XCTest
@testable import CinemaCore

final class TMDBIntroDBTests: XCTestCase {
    func testFeaturedPosterUsesOriginalLanguageAndMatchedMovieMetadataWithoutChangingSourceIdentity() {
        var media = Media(id: "cinema-id", title: "عنوان المصدر")
        media.enTitle = "Provider title"; media.duration = "900"; media.year = "2024"; media.quality = "4K"
        let work = TMDBParser.work(["title": "العنوان العربي", "original_title": "千と千尋の神隠し",
                                    "release_date": "2025-01-23", "runtime": 139,
                                    "vote_average": 7.56, "vote_count": 120], series: false)
        let display = TMDBWork.featuredMedia(for: media, matched: work)
        XCTAssertEqual(display.title, "千と千尋の神隠し")
        XCTAssertEqual(display.year, "2025"); XCTAssertEqual(display.duration, "2:19:00")
        XCTAssertEqual(work.posterInfo?.rating, "7.6")
        XCTAssertEqual(display.id, media.id); XCTAssertEqual(display.quality, "4K")
        XCTAssertEqual(media.title, "عنوان المصدر")
    }

    func testIncompleteTMDBResponsePreservesSourceFieldsAndDoesNotPresentUnratedScore() throws {
        var media = Media(id: "cinema-id", title: "لا تستسلم أبداً")
        media.year = "2026"; media.duration = "8340"; media.rating = "6.1"
        let work = TMDBParser.work(["title": "Never Surrender", "original_title": "Never Surrender",
                                    "runtime": 0, "vote_count": 0, "vote_average": 10.0], series: false)
        let display = TMDBWork.featuredMedia(for: media, matched: work)
        XCTAssertEqual(display.title, "Never Surrender")
        XCTAssertEqual(display.year, media.year); XCTAssertEqual(display.duration, media.duration)
        XCTAssertNil(work.posterInfo?.rating); XCTAssertEqual(display.rating, media.rating)
        var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(work)) as? [String: Any])
        legacy.removeValue(forKey: "posterInfo")
        let cached = try JSONDecoder().decode(TMDBWork.self, from: JSONSerialization.data(withJSONObject: legacy))
        XCTAssertNil(cached.posterInfo); XCTAssertEqual(TMDBWork.featuredMedia(for: media, matched: cached), media)
    }

    func testFeaturedTitleWhileLoadingAndWhenTheOriginalIsArabic() {
        var source = Media(id: "demo", title: "الكيت كات")
        source.enTitle = "Kit Kat"
        XCTAssertEqual(TMDBWork.featuredMedia(for: source, matched: nil).title, "Kit Kat")
        let match = TMDBParser.work(["title": "الكيت كات", "original_title": "الكيت كات"], series: false)
        XCTAssertEqual(TMDBWork.featuredMedia(for: source, matched: match).title, "الكيت كات")
        source.enTitle = "  "
        XCTAssertEqual(TMDBWork.featuredMedia(for: source, matched: nil).title, source.title)
    }

    func testExactTMDBTitleWithoutArtworkBeatsRelatedMovieAndWrongYear() {
        let results: [[String: Any]] = [
            ["id": 1, "title": "Never Surrender", "release_date": "1998-01-01"],
            ["id": 2, "title": "Never Surrender Again", "release_date": "2026-01-01", "poster_path": "/other.jpg"],
            ["id": 3, "title": "Never Surrender", "release_date": "2026-01-01"],
        ]
        XCTAssertEqual(TMDBParser.pick(results, title: "Never Surrender", year: 2026)?["id"] as? Int, 3)
    }

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
    func testSeasonEpisodeRatingNeedsVotesAndIsOneDecimal() {
        let season = TMDBParser.season(["episodes": [
            ["episode_number": 1, "vote_average": 8.36, "vote_count": 12],
            ["episode_number": 2, "vote_average": 9.0, "vote_count": 0],
            ["episode_number": 3, "vote_average": 0, "vote_count": 5]
        ]])
        XCTAssertEqual(season.episodes[1]?.rating, "8.4")
        XCTAssertEqual(season.episodes[2]?.rating, "")
        XCTAssertEqual(season.episodes[3]?.rating, "")
    }
    func testIntroDBSegmentsForTheSameEpisodeOnly() {
        let json = #"{"imdb_id":"tt123","is_movie":false,"season":1,"episode":2,"intro":{"start_ms":5000,"end_ms":65000},"outro":{"start_sec":2400,"end_sec":2500}}"#
        let found = IntroDBParser.parse(Data(json.utf8), imdbID: "tt123", season: 1, episode: 2, movie: false)
        XCTAssertEqual(found.introStart, 5); XCTAssertEqual(found.introEnd, 65); XCTAssertEqual(found.creditsStart, 2400)
        XCTAssertTrue(found.inIntro(30)); XCTAssertFalse(found.inIntro(64)); XCTAssertFalse(found.inIntro(2))
        XCTAssertEqual(IntroDBParser.parse(Data(json.utf8), imdbID: "tt123", season: 1, episode: 3, movie: false), SkipSegments())
    }
}
