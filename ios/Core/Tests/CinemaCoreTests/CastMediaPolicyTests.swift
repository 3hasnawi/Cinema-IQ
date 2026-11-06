import XCTest
@testable import CinemaCore

final class CastMediaPolicyTests: XCTestCase {
    func testReceiverMimeAndSignedQueries() throws {
        XCTAssertEqual(CastMediaPolicy.mime(try XCTUnwrap(URL(string: "https://cdn.example/a.m3u8?token=x"))), "application/x-mpegURL")
        XCTAssertEqual(CastMediaPolicy.mime(try XCTUnwrap(URL(string: "https://cdn.example/vod?id=12&ext=mp4"))), "video/mp4")
        XCTAssertNil(CastMediaPolicy.mime(try XCTUnwrap(URL(string: "https://cdn.example/a.mkv"))))
    }
    func testRejectsCredentialsAndLocalFiles() throws {
        for raw in ["file:///tmp/a.mp4", "https://user:password@cdn.example/a.mp4", "javascript:bad", "https:///a"] {
            XCTAssertNil(CastMediaPolicy.mime(try XCTUnwrap(URL(string: raw))))
        }
    }
    func testVTTOnlyOnReceiver() throws {
        let vtt = SubtitleTrack(url: try XCTUnwrap(URL(string: "https://cdn.example/ar.vtt?token=x")), language: "ar", title: "العربية")
        let srt = SubtitleTrack(url: try XCTUnwrap(URL(string: "https://cdn.example/ar.srt")), language: "ar", title: "العربية")
        XCTAssertTrue(CastMediaPolicy.subtitle(vtt))
        XCTAssertFalse(CastMediaPolicy.subtitle(srt))
    }
}
