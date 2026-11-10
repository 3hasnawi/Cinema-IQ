import XCTest
@testable import CinemaCore

final class VideoQualityTests: XCTestCase {
    private func stream(_ quality: String, _ path: String) throws -> StreamChoice {
        try XCTUnwrap(StreamChoice("https://example.test/" + path, quality: quality))
    }
    func testManualPreferenceUsesAvailableRenditionsRegardlessOfProviderOrder() throws {
        let high = try stream("1080p WEB-DL", "high.mp4")
        let low = try stream("480p", "low.mp4")
        let medium = try stream("720p", "medium.mp4")
        XCTAssertEqual(VideoQuality.p720.select(from: [high, low, medium]), medium)
        XCTAssertEqual(VideoQuality.p2160.select(from: [medium, high, low]), high)
        XCTAssertEqual(VideoQuality.p360.select(from: [high, medium, low]), low)
    }
    func testAutomaticPrefersHLSEndpointAndSourceDefault() throws {
        let fixed = try stream("1080p", "movie.mp4")
        let hls = try stream("HLS", "master.m3u8")
        let automatic = try stream("تلقائي", "vod?ext=m3u8")
        XCTAssertEqual(VideoQuality.automatic.select(from: [fixed, hls]), hls)
        XCTAssertEqual(VideoQuality.p720.select(from: [fixed, automatic]), automatic)
        XCTAssertTrue(VideoQuality.looksLikeHLS(automatic))
    }
    func testUnknownQualityDoesNotDiscardPlayableSource() throws {
        let unknown = try stream("نسخة المصدر", "movie.mp4")
        XCTAssertEqual(VideoQuality.p1080.select(from: [unknown]), unknown)
        XCTAssertNil(VideoQuality.automatic.select(from: []))
        XCTAssertNil(VideoQuality.p720.select(from: []))
    }
    func testResolutionLabelsDoNotMistakeCodecNumbersForQuality() {
        XCTAssertEqual(VideoQuality.resolution("1920x1080"), 1080)
        XCTAssertEqual(VideoQuality.resolution("4K HEVC"), 2160)
        XCTAssertEqual(VideoQuality.resolution("FHD"), 1080)
        XCTAssertEqual(VideoQuality.resolution("720P"), 720)
        XCTAssertNil(VideoQuality.resolution("H264 AAC"))
    }
}
