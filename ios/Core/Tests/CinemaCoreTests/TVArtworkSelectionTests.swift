import XCTest
@testable import CinemaCore

final class TVArtworkSelectionTests: XCTestCase {
    func testFeaturedFormatsPreferMatchingOrientationAndKeepOrderedFallbacks() {
        var media = Media(id: "cinemana:one", title: "One")
        media.poster = " provider-poster "; media.backdrop = "provider-backdrop"
        var metadata = TMDBWork(); metadata.poster = "matched-poster"; metadata.backdrop = "matched-backdrop"
        XCTAssertEqual(FeaturedArtworkFormat.poster.candidates(for: media, tmdb: metadata, fallbackPoster: "omdb"),
                       ["matched-poster", "provider-poster", "omdb", "matched-backdrop", "provider-backdrop"])
        XCTAssertEqual(FeaturedArtworkFormat.backdrop.candidates(for: media, tmdb: metadata, fallbackPoster: "omdb"),
                       ["matched-backdrop", "provider-backdrop", "matched-poster", "provider-poster", "omdb"])
        media.poster = " "; media.backdrop = "only-image"
        XCTAssertEqual(FeaturedArtworkFormat.poster.candidates(for: media, tmdb: nil), ["only-image"])
        media.poster = "only-image"
        XCTAssertEqual(FeaturedArtworkFormat.poster.candidates(for: media, tmdb: nil, fallbackPoster: "only-image"), ["only-image"])
    }

    func testMatchedTMDBLandscapeTakesPriorityOverProviderPosterAndBackdrop() {
        var media = Media(id: "cinemana:fixture", title: "Fixture")
        media.backdrop = "https://provider.example/portrait.jpg"; media.poster = "https://provider.example/poster.jpg"
        var work = TMDBWork(); work.backdrop = "https://image.tmdb.org/t/p/w1280/landscape.jpg"
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: work), work.backdrop)
        XCTAssertEqual(TVArtworkSelection.url(for: media, tmdb: nil), media.backdrop)
        XCTAssertEqual(TVArtworkSelection.providerFallback(for: media, excluding: work.backdrop), media.backdrop)
        XCTAssertEqual(TVArtworkSelection.providerFallback(for: media, excluding: media.backdrop), media.poster)
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
