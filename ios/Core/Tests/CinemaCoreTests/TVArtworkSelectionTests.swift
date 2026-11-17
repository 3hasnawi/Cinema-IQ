import XCTest
@testable import CinemaCore

final class TVArtworkSelectionTests: XCTestCase {
    func testMatchedTMDBLandscapeTakesPriorityOverProviderPosterAndBackdrop() {
        var media = Media(id: "cinemana:fixture", title: "Fixture")
        media.backdrop = "https://provider.example/portrait.jpg"; media.poster = "https://provider.example/poster.jpg"
        var work = TMDBWork(); work.backdrop = "https://image.tmdb.org/t/p/w1280/landscape.jpg"
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: work), work.backdrop)
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: nil), media.backdrop)
    }
    func testSourceLandscapeRemainsAvailableWhenTMDBOnlyHasAPoster() {
        var media = Media(id: "cinemana:fixture", title: "Fixture"); media.backdrop = "source-landscape"
        var work = TMDBWork(); work.poster = "tmdb-poster"
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: work), "source-landscape")
        media.backdrop = "  \n"
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: work), "tmdb-poster")
    }
    func testMissingOrUnmatchedMetadataUsesExistingSourceThenOptionalPoster() {
        var media = Media(id: "fixture", title: "Fixture"); media.poster = "source-poster"
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: nil, fallbackPoster: "optional-poster"), "source-poster")
        media.poster = ""
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: nil, fallbackPoster: "optional-poster"), "optional-poster")
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: nil), "")
    }
}
