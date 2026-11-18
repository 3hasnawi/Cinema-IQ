import Foundation
import XCTest
@testable import CinemaCore

final class PlaybackSessionCoordinatorTests: XCTestCase {
    private final class Calls: @unchecked Sendable {
        private let lock = NSLock()
        private var recorded: [String] = []
        func add(_ value: String) { lock.lock(); defer { lock.unlock() }; recorded.append(value) }
        var values: [String] { lock.lock(); defer { lock.unlock() }; return recorded }
    }

    func testDelayedOldCloseCannotSilenceReopenedMovieAndCloseIsIdempotent() async throws {
        let calls = Calls()
        let session = PlaybackSessionCoordinator(activate: { calls.add("activate") }, deactivate: { calls.add("deactivate") })
        let old = UUID(), current = UUID()
        try await session.acquire(old)
        try await session.acquire(current)
        await session.release(old)
        XCTAssertEqual(calls.values, ["activate", "activate"])
        await session.release(current)
        await session.release(current)
        XCTAssertEqual(calls.values, ["activate", "activate", "deactivate"])
    }

    func testFailedActivationDoesNotStealExistingOwnership() async throws {
        enum Failure: Error { case unavailable }
        let calls = Calls()
        let session = PlaybackSessionCoordinator(activate: {
            if !calls.values.isEmpty { throw Failure.unavailable }
            calls.add("activate")
        }, deactivate: { calls.add("deactivate") })
        let old = UUID()
        try await session.acquire(old)
        do { try await session.acquire(UUID()); XCTFail("Expected activation failure") }
        catch Failure.unavailable { }
        await session.release(old)
        XCTAssertEqual(calls.values, ["activate", "deactivate"])
    }

    func testCancelledOpeningNeverActivatesAudioAfterLeavingPlayer() async {
        let calls = Calls()
        let session = PlaybackSessionCoordinator(activate: { calls.add("activate") }, deactivate: { calls.add("deactivate") })
        let opening = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            do { try await session.acquire(UUID()); XCTFail("Cancelled playback must not start") }
            catch is CancellationError { }
            catch { XCTFail("Unexpected error: \(error)") }
        }
        await opening.value
        XCTAssertTrue(calls.values.isEmpty)
    }

    func testBlockingPlatformShutdownLeavesMainActorResponsive() async throws {
        let entered = expectation(description: "Platform shutdown entered")
        let gate = DispatchSemaphore(value: 0)
        let session = PlaybackSessionCoordinator(activate: {
            XCTAssertFalse(Thread.isMainThread)
        }, deactivate: {
            XCTAssertFalse(Thread.isMainThread)
            entered.fulfill()
            _ = gate.wait(timeout: .now() + 5)
        })
        let owner = UUID()
        try await session.acquire(owner)
        let closing = Task { await session.release(owner) }
        await fulfillment(of: [entered], timeout: 2)
        defer { gate.signal() }
        // The platform operation is still blocked when this UI work must execute.
        let mainRan = await MainActor.run { 42 }
        XCTAssertEqual(mainRan, 42)
        gate.signal()
        await closing.value
    }
}
