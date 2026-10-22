import XCTest
@testable import CinemaCore

final class CinemaCoreTests: XCTestCase {
    func testStreamIdentityAndValidation() throws {
        let url = try DawKey("daw:episode:b~12:b~34:mp4").streamURL()
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.first(where: { $0.name == "id" })?.value, "b~34")
        XCTAssertEqual(query.first(where: { $0.name == "kind" })?.value, "series")
        XCTAssertThrowsError(try DawKey("daw:movie:../escape:mp4"))
        XCTAssertThrowsError(try DawKey("daw:movie:123:mkv").streamURL())
        XCTAssertThrowsError(try DawKey("daw:series:123").streamURL())
        XCTAssertThrowsError(try DawKey("cin:123"))
    }
    func testAndroidIdentityRoundTrip() throws {
        let json = Data(#"{"nb":"daw:episode:12:34:mp4","ar_title":"Episode","kind":2,"season":2,"episodeNummer":4,"seriesId":"daw:series:12"}"#.utf8)
        let media = try JSONDecoder().decode(Media.self, from: json)
        XCTAssertEqual(media.recentKey, "recent/daw:series:12")
        XCTAssertEqual(media.episode, 4)
        XCTAssertTrue(media.isSeries)
        XCTAssertEqual(try JSONDecoder().decode(Media.self, from: JSONEncoder().encode(media)), media)
    }
    func testDuplicateAndMalformedSearchRows() {
        let rows: [[String: Any]] = [["id": 1, "name": "AR| Test - 2026", "_kind": "movie", "ext": "mp4"], ["id": 1,"name": "Duplicate"], ["id": "../x","name": "Bad"]]
        let items = DawwirliClient.items(rows)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.title, "Test")
    }
    func testSeriesFixture() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "series", withExtension: "json"))
        let data = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let seasons = DawwirliClient.parseSeasons(data, parent: "64878")
        XCTAssertFalse(seasons.isEmpty)
        let episodes = seasons.flatMap(\.episodes)
        XCTAssertGreaterThanOrEqual(episodes.count, 2)
        XCTAssertTrue(episodes.allSatisfy { $0.seriesID == "daw:series:64878" && $0.episode > 0 })
    }
}
