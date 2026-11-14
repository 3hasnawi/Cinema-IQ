import XCTest
@testable import CinemaCore

final class PlaybackFailureTests: XCTestCase {
    func testOnlyNativeDecoderFailureAllowsAutomaticFallback() {
        for code in [-11821, -11828, -11829, -11833] {
            XCTAssertTrue(PlaybackFailure.classify(NSError(domain: "AVFoundationErrorDomain", code: code)).permitsDecoderFallback)
        }
        for status in [401, 403, 404, 410, 429, 500, 503] {
            let failure = PlaybackFailure.classify(NSError(domain: "AVFoundationErrorDomain", code: -11828), httpStatus: status)
            XCTAssertFalse(failure.permitsDecoderFallback)
        }
        XCTAssertEqual(PlaybackFailure.classify(nil, httpStatus: 403), .unauthorized)
        XCTAssertEqual(PlaybackFailure.classify(nil, httpStatus: 404), .missing)
    }
    func testNestedNetworkAndProtectedFailuresAreNotReportedAsCodecFailures() {
        let error = NSError(domain: "AVFoundationErrorDomain", code: -11828,
            userInfo: [NSUnderlyingErrorKey: NSError(domain: NSURLErrorDomain, code: -1009)])
        XCTAssertEqual(PlaybackFailure.classify(error), .network)
        XCTAssertEqual(PlaybackFailure.classify(NSError(domain: "AVFoundationErrorDomain", code: -11831)), .protectedContent)
        XCTAssertEqual(PlaybackFailure.classify(NSError(domain: NSURLErrorDomain, code: -1001)), .timeout)
        XCTAssertEqual(PlaybackFailure.classify(NSError(domain: NSURLErrorDomain, code: -1202)), .secureConnection)
    }
    func testMessagesNeverCopySensitiveErrorDetails() {
        let error = NSError(domain: "Unknown", code: 0, userInfo: [NSLocalizedDescriptionKey: "https://provider.example/u/password?token=secret"])
        XCTAssertEqual(PlaybackFailure.classify(error), .unknown)
        XCTAssertFalse(PlaybackFailure.classify(error).message.contains("secret"))
    }
    func testBackendRoutingPreservesAppleForHLSAndHonorsExplicitChoice() {
        let hls = URL(string: "https://provider.example/live.m3u8")!, ts = URL(string: "https://provider.example/live.TS")!
        XCTAssertFalse(IPTVPlaybackMode.automatic.startsWithCompatibility(hls))
        XCTAssertTrue(IPTVPlaybackMode.automatic.startsWithCompatibility(ts))
        XCTAssertFalse(IPTVPlaybackMode.apple.startsWithCompatibility(ts))
        XCTAssertTrue(IPTVPlaybackMode.compatibility.startsWithCompatibility(hls))
    }
}
