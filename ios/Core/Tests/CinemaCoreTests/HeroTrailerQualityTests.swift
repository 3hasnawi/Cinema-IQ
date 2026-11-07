import XCTest
@testable import CinemaCore

final class HeroTrailerQualityTests: XCTestCase {
    private func stream(_ quality: String, _ file: String? = nil) -> StreamChoice {
        StreamChoice("https://media.example.org/\(file ?? quality + ".mp4")", quality: quality)!
    }

    func testPreferredQualityMatchesAcrossTitlesWithoutPersistingURLs() {
        let selected = stream("Full HD 1080p", "old-title.mp4")
        let saved = PlaybackQualityPreference.value(for: selected)
        XCTAssertEqual(saved, "1080")
        let streams = [stream("360p"), stream("720p"), stream("1080p", "new-title.mp4")]
        XCTAssertEqual(PlaybackQualityPreference.choose(streams, preferred: saved), streams[2])
    }

    func testNearestLowerFallbackAndNoMutationOfPreference() {
        let saved = "1080"
        let streams = [stream("2160p"), stream("480p"), stream("720p")]
        XCTAssertEqual(PlaybackQualityPreference.choose(streams, preferred: saved), streams[2])
        XCTAssertEqual(saved, "1080")
        XCTAssertEqual(PlaybackQualityPreference.choose([stream("2160p"), stream("1440p")], preferred: saved)?.quality, "1440p")
        XCTAssertNil(PlaybackQualityPreference.choose([], preferred: saved))
    }

    func testAutomaticAndAdaptiveFallback() {
        let hls = stream("تلقائي", "master.m3u8")
        XCTAssertEqual(PlaybackQualityPreference.choose([stream("720p"), hls], preferred: "auto"), hls)
        XCTAssertEqual(PlaybackQualityPreference.choose([stream("720p"), hls], preferred: "1080"), hls)
        XCTAssertEqual(PlaybackQualityPreference.value(for: hls), "auto")
        XCTAssertEqual(PlaybackQualityPreference.height("4K HDR"), 2160)
        XCTAssertEqual(PlaybackQualityPreference.height("1920x1080"), 1080)
        XCTAssertEqual(PlaybackQualityPreference.height("Full HD"), 1080)
        XCTAssertEqual(PlaybackQualityPreference.height("HD"), 720)
        XCTAssertNil(PlaybackQualityPreference.height("HDR"))
        let original = stream("Original")
        XCTAssertEqual(PlaybackQualityPreference.choose([stream("720p"), original],
            preferred: PlaybackQualityPreference.value(for: original)), original)
        XCTAssertNil(PlaybackQualityPreference.height("10800p"))
    }

    func testTrailerRejectsWebpagesCredentialsAndUnsupportedSchemes() {
        for invalid in ["https://youtube.com/watch?v=test", "https://vimeo.com/123", "http://media.example.org/trailer.mp4",
                        "file:///tmp/trailer.mp4", "https://user:password@media.example.org/trailer.mp4",
                        "https://media.example.org/trailer.mkv", "javascript:alert(1)"] {
            XCTAssertNil(HeroTrailer.url(invalid), invalid)
        }
        XCTAssertNotNil(HeroTrailer.url("https://media.example.org/trailer.MP4?token=source-value"))
        XCTAssertEqual(HeroTrailer.sourceURL(["stream": "https://media.example.org/movie.mp4"]), "")
    }

    func testMissingTrailerIsBackwardCompatibleAndExternalTrailerRoundTrips() throws {
        let old = Data(#"{"nb":"17","ar_title":"Old item"}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(Media.self, from: old).trailerURL, "")
        let item = try XCTUnwrap(ExternalParser.item([
            "id": "new", "title": "Trailer fixture", "type": "movie",
            "trailerURL": "https://media.example.org/trailer.mp4"
        ], provider: "demo"))
        let roundTrip = try JSONDecoder().decode(Media.self, from: JSONEncoder().encode(item))
        XCTAssertEqual(roundTrip.trailerURL, item.trailerURL)
        XCTAssertEqual(ExternalParser.item(["id": "new", "title": "Title", "trailerURL": "https://youtube.com/watch?v=test"], provider: "demo")?.trailerURL, "")
    }

    func testDemoTrailerIsSintelOnlyAndNotAFeatureFilmSubstitution() async throws {
        let shelves = try await ExternalClient(provider: .demo).home()
        let films = shelves.flatMap(\.items)
        let sintel = try XCTUnwrap(films.first { $0.id.hasSuffix(":sintel") })
        XCTAssertEqual(sintel.trailerURL, "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4")
        XCTAssertTrue(films.filter { $0.id != sintel.id }.allSatisfy { $0.trailerURL.isEmpty })
        let url = await SourceClient().trailer(sintel)
        XCTAssertEqual(url?.absoluteString, sintel.trailerURL)
    }

    func testCarouselWrapsWithoutInvalidIndices() {
        XCTAssertEqual(HeroTrailer.nextIndex(0, count: 8, step: -1), 7)
        XCTAssertEqual(HeroTrailer.nextIndex(7, count: 8, step: 1), 0)
        XCTAssertEqual(HeroTrailer.nextIndex(0, count: 1, step: 1), 0)
        XCTAssertNil(HeroTrailer.nextIndex(0, count: 0, step: 1))
    }
}
