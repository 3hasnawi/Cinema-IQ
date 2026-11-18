import XCTest
@testable import CinemaCore

final class TopShelfTests: XCTestCase {
    private func media(_ id: String = "123") -> Media {
        var m = Media(id: id, title: "عنوان العمل")
        m.poster = "https://images.example/poster.jpg"; m.backdrop = "https://images.example/wide.jpg"
        return m
    }
    func testRoutesRoundTripNamespacedMediaAndSeparateDisplayFromPlay() throws {
        for id in ["123", "vodu:12", "daw:movie:42:mp4", "ext:demo:big-buck-bunny"] {
            for action in [TopShelfRoute.Action.display, .play] {
                let r = TopShelfRoute(mediaID: id, action: action)
                XCTAssertEqual(TopShelfRoute(url: try XCTUnwrap(r.url)), r)
            }
        }
    }
    func testRejectsInjectedDestinationsDuplicateQueriesAndUnboundedIDs() throws {
        for text in ["https://top-shelf?media=123&action=play", "cinemaiq://evil?media=123&action=play",
                     "cinemaiq://top-shelf?media=123&action=play&action=display", "cinemaiq://top-shelf?media=123&action=delete",
                     "cinemaiq://user:pass@top-shelf?media=123&action=play", "cinemaiq://top-shelf/path?media=123&action=play",
                     "cinemaiq://top-shelf?media=https%3A%2F%2Fevil.example%2Fstream.m3u8&action=play"] {
            XCTAssertNil(TopShelfRoute(url: try XCTUnwrap(URL(string: text))), text)
        }
        XCTAssertNil(TopShelfRoute(mediaID: String(repeating: "1", count: 1025), action: .play).url)
    }
    func testCompletedItemsAreExcludedAndProgressIsFiniteClamped() throws {
        XCTAssertNil(TopShelfEntry(media: media(), completed: true))
        XCTAssertEqual(try XCTUnwrap(TopShelfEntry(media: media(), seconds: .nan, duration: 10)).progress, 0)
        XCTAssertEqual(try XCTUnwrap(TopShelfEntry(media: media(), seconds: 20, duration: 10)).progress, 1)
        XCTAssertEqual(try XCTUnwrap(TopShelfEntry(media: media(), seconds: -2, duration: 10)).progress, 0)
    }
    func testPublicArtworkNeverReusesAuthenticationOrFileURLs() {
        for address in ["file:///private/watch.jpg", "https://u:p@images.example/a.jpg", "https://images.example/a.jpg?access_token=secret",
                        "https://images.example/a.jpg?X-Amz-Signature=secret", "https://images.example/a.jpg?password=secret"] {
            XCTAssertNil(TopShelfEntry.publicImage(address))
        }
        XCTAssertNotNil(TopShelfEntry.publicImage("https://images.example/a.jpg?width=1920"))
    }
    func testEpisodeNumbersRemainWithAnEpisodeNameAndLandscapeIsExplicit() throws {
        var m = media(); m.episode = 3; m.season = 2; m.episodeTitle = "بداية جديدة"
        let item = try XCTUnwrap(TopShelfEntry(media: m))
        XCTAssertTrue(item.subtitle.contains("3")); XCTAssertTrue(item.subtitle.contains("2"))
        XCTAssertFalse(item.landscape, "A provider's backdrop field may actually contain a portrait")
        let wide = try XCTUnwrap(TopShelfEntry(media: m, landscapeURL: "https://image.tmdb.org/t/p/w1280/test.jpg"))
        XCTAssertTrue(wide.landscape)
    }
    func testRecentEntriesLeadAndFeaturedDuplicatesAreRemovedWithBounds() throws {
        let items = try (1...30).map { try XCTUnwrap(TopShelfEntry(media: media(String($0)))) }
        let snapshot = TopShelfSnapshot(context: UUID(), source: "cinemana", continuing: Array(items.prefix(10)), featured: items)
        XCTAssertEqual(snapshot.continuing.count, 8); XCTAssertEqual(snapshot.featured.count, 8)
        XCTAssertEqual(snapshot.entries.first?.mediaID, "1")
        XCTAssertEqual(snapshot.featured.first?.mediaID, "9")
        XCTAssertEqual(Set(snapshot.entries.map(\.mediaID)).count, 16)
        XCTAssertTrue(snapshot.usable())
    }
    func testExpiredAndFutureSnapshotsAreRejectedAndSerializationIsMetadataOnly() throws {
        let now = Date(); let item = try XCTUnwrap(TopShelfEntry(media: media()))
        let stale = TopShelfSnapshot(context: UUID(), source: "cinemana", continuing: [item], featured: [], now: now.addingTimeInterval(-8 * 86_400))
        XCTAssertFalse(stale.usable(at: now))
        let future = TopShelfSnapshot(context: UUID(), source: "cinemana", continuing: [item], featured: [], now: now.addingTimeInterval(120))
        XCTAssertFalse(future.usable(at: now))
        let snapshot = TopShelfSnapshot(context: UUID(), source: "cinemana", continuing: [item], featured: [], now: now)
        let bytes = try JSONEncoder().encode(snapshot)
        XCTAssertEqual(try JSONDecoder().decode(TopShelfSnapshot.self, from: bytes), snapshot)
        let text = String(decoding: bytes, as: UTF8.self)
        for forbidden in ["accessToken", "password", "streamURL", "refreshToken"] { XCTAssertFalse(text.contains(forbidden)) }
    }
}
