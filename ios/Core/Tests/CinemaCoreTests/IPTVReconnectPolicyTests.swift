import XCTest
@testable import CinemaCore

final class IPTVReconnectPolicyTests: XCTestCase {
    func testTemporaryRetriesBackOffAndRemainBounded() {
        XCTAssertEqual(IPTVReconnectPolicy.retry(.network, attempts: 0)?.delay, 1)
        XCTAssertEqual(IPTVReconnectPolicy.retry(.server, attempts: 3)?.delay, 8)
        XCTAssertEqual(IPTVReconnectPolicy.retry(.timeout, attempts: 1000)?.delay, 30)
        XCTAssertEqual(IPTVReconnectPolicy.retry(.rateLimited, attempts: 0)?.delay, 30)
    }
    func testExpiredAccessRenewsAfterADirectRetryButRateLimitsDoNotReloadTheCatalogue() {
        XCTAssertFalse(IPTVReconnectPolicy.retry(.missing, attempts: 0)!.renewLink)
        XCTAssertTrue(IPTVReconnectPolicy.retry(.unauthorized, attempts: 1)!.renewLink)
        XCTAssertTrue(IPTVReconnectPolicy.retry(.network, attempts: 2)!.renewLink)
        XCTAssertFalse(IPTVReconnectPolicy.retry(.rateLimited, attempts: 2)!.renewLink)
    }
    func testPermanentAndUserStoppedPlaybackDoNotRetry() {
        XCTAssertNil(IPTVReconnectPolicy.retry(.protectedContent, attempts: 0))
        XCTAssertNil(IPTVReconnectPolicy.retry(.unsupported, attempts: 0))
        XCTAssertNil(IPTVReconnectPolicy.retry(.secureConnection, attempts: 0))
        XCTAssertFalse(IPTVReconnectPolicy.allowed(active: false, pausedByUser: false))
        XCTAssertFalse(IPTVReconnectPolicy.allowed(active: true, pausedByUser: true))
        XCTAssertTrue(IPTVReconnectPolicy.allowed(active: true, pausedByUser: false))
    }
}
