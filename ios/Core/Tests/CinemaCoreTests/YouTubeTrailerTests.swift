import XCTest
@testable import CinemaCore

final class YouTubeTrailerTests: XCTestCase {
    func testParsesValidIDAndBuildsAppURL() {
        let data = #"{"items":[{"id":{"videoId":"abcDEF_123-"}}]}"#.data(using: .utf8)!
        XCTAssertEqual(YouTubeTrailer.videoID(from: data), "abcDEF_123-")
        XCTAssertEqual(YouTubeTrailer.appURL(videoID: "abcDEF_123-")?.scheme, "youtube")
    }
    func testRejectsMalformedAndMissingVideoIDs() {
        let data = #"{"items":[{"id":{"videoId":"../../evil"}}]}"#.data(using: .utf8)!
        XCTAssertNil(YouTubeTrailer.videoID(from: data))
        XCTAssertNil(YouTubeTrailer.videoID(from: Data("bad".utf8)))
        XCTAssertNil(YouTubeTrailer.appURL(videoID: "../bad"))
    }
}
