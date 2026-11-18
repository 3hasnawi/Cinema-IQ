import XCTest
@testable import CinemaCore

final class PlaybackSeekQueueTests: XCTestCase {
    func testRequestedPositionRemainsPendingUntilDecoderConfirmsIt() throws {
        var seeks = PlaybackSeekQueue()
        seeks.enqueue(30)
        let attempt = try XCTUnwrap(seeks.begin())
        XCTAssertEqual(seeks.target, 30)
        XCTAssertNil(seeks.begin(), "A second decoder seek must not race the first")
        XCTAssertEqual(seeks.complete(attempt, finished: true), .settled)
        XCTAssertFalse(seeks.isPending)
    }

    func testLatestDragWinsAfterOlderSeekCompletes() throws {
        var seeks = PlaybackSeekQueue()
        seeks.enqueue(30)
        let old = try XCTUnwrap(seeks.begin())
        seeks.enqueue(45)
        XCTAssertEqual(seeks.complete(old, finished: true), .seekLatest)
        XCTAssertEqual(seeks.target, 45)
        let latest = try XCTUnwrap(seeks.begin())
        XCTAssertEqual(latest.seconds, 45)
        XCTAssertEqual(seeks.complete(old, finished: false), .ignored)
        XCTAssertEqual(seeks.complete(latest, finished: true), .settled)
    }

    func testInterruptedSeekRetriesOnceThenWaitsForTransportInsteadOfSpinning() throws {
        var seeks = PlaybackSeekQueue()
        seeks.enqueue(30)
        let first = try XCTUnwrap(seeks.begin())
        XCTAssertEqual(seeks.complete(first, finished: false), .seekLatest)
        let retry = try XCTUnwrap(seeks.begin())
        XCTAssertEqual(seeks.complete(retry, finished: false), .awaitTrigger)
        XCTAssertEqual(seeks.target, 30)
        // A later native Play/readiness event can retry the retained target.
        let resumed = try XCTUnwrap(seeks.begin())
        XCTAssertEqual(seeks.complete(resumed, finished: true), .settled)
    }

    func testLateCompletionAfterCloseCannotEraseNewMovieSeek() throws {
        var seeks = PlaybackSeekQueue()
        seeks.enqueue(30)
        let retired = try XCTUnwrap(seeks.begin())
        seeks.reset()
        seeks.enqueue(90)
        let current = try XCTUnwrap(seeks.begin())
        XCTAssertEqual(seeks.complete(retired, finished: true), .ignored)
        XCTAssertEqual(seeks.target, 90)
        XCTAssertNil(seeks.begin())
        XCTAssertEqual(seeks.complete(current, finished: true), .settled)
    }

    func testInvalidTimesDoNotReplacePendingPositionAndNegativeTimeClampsToStart() throws {
        var seeks = PlaybackSeekQueue()
        seeks.enqueue(30)
        XCTAssertFalse(seeks.enqueue(.nan))
        XCTAssertFalse(seeks.enqueue(.infinity))
        XCTAssertEqual(seeks.target, 30)
        XCTAssertTrue(seeks.enqueue(-10))
        XCTAssertEqual(try XCTUnwrap(seeks.begin()).seconds, 0)
    }
}
