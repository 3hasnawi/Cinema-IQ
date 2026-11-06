import XCTest
@testable import CinemaCore

final class PlaybackOverlayTests: XCTestCase {
    func testPausedPlaybackKeepsInfoWhenNativeControlsHide() {
        XCTAssertTrue(PlaybackOverlayPolicy.isVisible(ready: true, paused: true, transportVisible: false, active: true))
    }

    func testRemoteControlsShowInfoAndPlayingAutoHideRemovesIt() {
        XCTAssertTrue(PlaybackOverlayPolicy.isVisible(ready: true, paused: false, transportVisible: true, active: true))
        XCTAssertFalse(PlaybackOverlayPolicy.isVisible(ready: true, paused: false, transportVisible: false, active: true))
    }

    func testNoOverlayDuringPreparationOrInactiveScene() {
        XCTAssertFalse(PlaybackOverlayPolicy.isVisible(ready: false, paused: true, transportVisible: true, active: true))
        XCTAssertFalse(PlaybackOverlayPolicy.isVisible(ready: true, paused: true, transportVisible: true, active: false))
    }

    func testMovieUsesSourcePosterAndConfirmedIMDbRating() {
        var film = Media(id: "film", title: "فيلم")
        film.poster = "https://example.com/poster.jpg"; film.year = "2024"; film.rating = "4"
        let info = OMDbParser.info(Data(#"{"Response":"True","imdbRating":"8.2","Year":"2023"}"#.utf8))
        let details = PlaybackOverlayDetails(media: film, info: info)
        XCTAssertEqual(details.title, "فيلم")
        XCTAssertEqual(details.poster, film.poster)
        XCTAssertEqual(details.year, "2024")
        XCTAssertEqual(details.rating, "IMDb 8.2")
        XCTAssertTrue(details.episode.isEmpty)
    }

    func testEpisodeUsesSeriesPosterAndIncludesEpisodeIdentity() {
        var series = Media(id: "series", title: "مسلسل")
        series.poster = "https://example.com/series.jpg"; series.year = "2022"; series.rating = "9"
        var episode = Media(id: "episode", title: "الحلقة")
        episode.episode = 4; episode.season = 2; episode.poster = "https://example.com/still.jpg"
        let details = PlaybackOverlayDetails(media: episode, series: series)
        XCTAssertEqual(details.title, series.title)
        XCTAssertEqual(details.poster, series.poster)
        XCTAssertEqual(details.year, "2022")
        XCTAssertEqual(details.episode, "الموسم 2 · الحلقة 4")
    }

    func testMissingFactsAreOmittedRatherThanMadeUp() {
        var film = Media(id: "film", title: "فيلم")
        film.year = "N/A"; film.rating = "0"
        let details = PlaybackOverlayDetails(media: film)
        XCTAssertTrue(details.year.isEmpty)
        XCTAssertTrue(details.rating.isEmpty)
        XCTAssertTrue(details.facts.isEmpty)
        XCTAssertEqual(details.accessibilitySummary, "فيلم")
    }

    func testMetadataFallbackFillsMissingPosterAndYear() {
        let film = Media(id: "film", title: "فيلم")
        let info = OMDbParser.info(Data(#"{"Response":"True","Poster":"https://example.com/poster.jpg","Year":"2021","imdbRating":"N/A"}"#.utf8))
        let details = PlaybackOverlayDetails(media: film, info: info)
        XCTAssertEqual(details.poster, "https://example.com/poster.jpg")
        XCTAssertEqual(details.year, "2021")
        XCTAssertTrue(details.rating.isEmpty)
    }
}
