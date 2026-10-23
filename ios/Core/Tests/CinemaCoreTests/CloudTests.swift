import XCTest
@testable import CinemaCore

final class CloudTests: XCTestCase {
    func testDocumentIDsMatchAndroidSHA256() {
        XCTAssertEqual(SHA256.hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(SHA256.hex(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        // Longer than one block, with Arabic text.
        XCTAssertEqual(SHA256.hex(String(repeating: "a", count: 1000)).count, 64)
        XCTAssertEqual(CloudLibraryCodec.documentID("favorite/42"), SHA256.hex("favorite/42"))
    }
    func testFilmJSONUsesAndroidKeysAndRoundTrips() {
        var media = Media(id: "21", title: "حلقة"); media.seriesID = "99"; media.season = 2; media.episode = 3; media.isSeries = true
        let json = CloudLibraryCodec.filmJSON(media)
        for key in ["\"nb\":\"21\"", "\"seriesId\":\"99\"", "\"episodeNummer\":3", "\"kind\":2", "\"ar_title\""] { XCTAssertTrue(json.contains(key), key) }
        let back = CloudLibraryCodec.film(json)
        XCTAssertEqual(back?.id, "21"); XCTAssertEqual(back?.seriesID, "99"); XCTAssertEqual(back?.episode, 3)
        XCTAssertEqual(CloudLibraryCodec.recentKey(media), "recent/99")
        XCTAssertNil(CloudLibraryCodec.film(""))
    }
    func testPlaybackUsesMillisecondsAndCarriesWatchedTitle() {
        let film = Media(id: "7", title: "فيلم")
        let json = CloudLibraryCodec.playbackJSON(.init(seconds: 12.5, duration: 3600, completed: true, watched: film), watchedAt: Date(timeIntervalSince1970: 10))
        XCTAssertTrue(json.contains("\"position\":12500")); XCTAssertTrue(json.contains("\"duration\":3600000"))
        XCTAssertTrue(json.contains("\"watchedAt\":10000"))
        let back = CloudLibraryCodec.playback(json)
        XCTAssertEqual(back?.seconds, 12.5); XCTAssertEqual(back?.completed, true); XCTAssertEqual(back?.watched?.id, "7")
        // Android writes numbers as longs and may omit the title.
        let android = CloudLibraryCodec.playback(#"{"position":61000,"duration":120000,"completed":false}"#)
        XCTAssertEqual(android, .init(seconds: 61, duration: 120, completed: false))
    }
    func testFirestoreValuesAndErrorCodes() {
        XCTAssertEqual(CloudClient.string(["stringValue": "x"]), "x")
        XCTAssertEqual(CloudClient.bool(["booleanValue": true]), true)
        XCTAssertNotNil(CloudClient.date(["timestampValue": "2026-10-22T15:04:05.123456Z"]))
        XCTAssertNotNil(CloudClient.date(["timestampValue": "2026-10-22T15:04:05Z"]))
        XCTAssertEqual(CloudClient.errorCode("WEAK_PASSWORD : Password should be at least 6 characters"), "WEAK_PASSWORD")
        XCTAssertTrue(CloudError.auth("EMAIL_EXISTS").localizedDescription.contains("مسجّل"))
    }
}
