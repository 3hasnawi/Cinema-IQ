import XCTest
@testable import CinemaCore

final class CloudLibraryImportTests: XCTestCase {
    @MainActor func testFailedUploadKeepsQueuedRecordsAndNeverReportsSuccess() async {
        let records = ["favorite/42": "film", "playback/42": "position"]
        var persisted: [String: String] = [:]
        var refreshed = false
        let result = await CloudLibraryImport.copy(
            records: records,
            enqueue: { persisted.merge($0) { _, latest in latest } },
            flush: { false }, // Timeout, offline, or a partially committed batch.
            refresh: { refreshed = true; return true },
            destinationIsCurrent: { true }
        )
        XCTAssertEqual(result, .pendingUpload)
        XCTAssertEqual(persisted, records)
        XCTAssertFalse(refreshed, "A successful read must not hide an unsuccessful upload")
    }

    @MainActor func testAcknowledgedUploadWithFailedReadHasDistinctOutcome() async {
        var persisted: [String: String] = [:]
        let result = await CloudLibraryImport.copy(
            records: ["favorite/42": "film"],
            enqueue: { persisted = $0 },
            flush: { persisted.removeAll(); return true },
            refresh: { false },
            destinationIsCurrent: { true }
        )
        XCTAssertEqual(result, .uploadedNeedsRefresh)
        XCTAssertTrue(persisted.isEmpty)
    }

    @MainActor func testRetryCanCompleteWithoutDroppingExistingPendingWrites() async {
        var persisted = ["favorite/other": "unsent existing change"]
        let records = ["favorite/42": "film"]
        let first = await CloudLibraryImport.copy(
            records: records,
            enqueue: { persisted.merge($0) { _, latest in latest } },
            flush: { false }, refresh: { true }, destinationIsCurrent: { true }
        )
        XCTAssertEqual(first, .pendingUpload)
        XCTAssertEqual(persisted.count, 2)
        var uploaded: [String: String] = [:]
        let retry = await CloudLibraryImport.copy(
            records: records,
            enqueue: { persisted.merge($0) { _, latest in latest } },
            flush: { uploaded = persisted; persisted.removeAll(); return true },
            refresh: { true }, destinationIsCurrent: { true }
        )
        XCTAssertEqual(retry, .completed)
        XCTAssertEqual(uploaded, ["favorite/42": "film", "favorite/other": "unsent existing change"])
        XCTAssertTrue(persisted.isEmpty)
    }

    @MainActor func testAccountChangeDuringFailedUploadPreservesOldQueueWithoutRefreshingNewAccount() async {
        var current = true
        var oldAccountQueue: [String: String] = [:]
        var refreshed = false
        let result = await CloudLibraryImport.copy(
            records: ["favorite/42": "film"],
            enqueue: { oldAccountQueue = $0 },
            flush: { current = false; return false },
            refresh: { refreshed = true; return true },
            destinationIsCurrent: { current }
        )
        XCTAssertEqual(result, .destinationChanged)
        XCTAssertEqual(oldAccountQueue, ["favorite/42": "film"])
        XCTAssertFalse(refreshed)
    }

    @MainActor func testAccountChangeAfterAcknowledgedUploadDoesNotRefreshNewAccount() async {
        var current = true
        var refreshed = false
        let result = await CloudLibraryImport.copy(
            records: ["favorite/42": "film"], enqueue: { _ in },
            flush: { current = false; return true },
            refresh: { refreshed = true; return true }, destinationIsCurrent: { current }
        )
        XCTAssertEqual(result, .destinationChanged)
        XCTAssertFalse(refreshed)
    }

    @MainActor func testAccountChangeDuringRefreshSuppressesOldSuccessMessage() async {
        var current = true
        let result = await CloudLibraryImport.copy(
            records: ["favorite/42": "film"], enqueue: { _ in }, flush: { true },
            refresh: { current = false; return true }, destinationIsCurrent: { current }
        )
        XCTAssertEqual(result, .destinationChanged)
    }

    @MainActor func testExternalOnlyLibraryDoesNotClaimAnUpload() async {
        var queued = false
        var uploaded = false
        let result = await CloudLibraryImport.copy(
            records: ["favorite/ext:demo:movie": "external"],
            enqueue: { _ in queued = true },
            flush: { uploaded = true; return true }, refresh: { true }, destinationIsCurrent: { true }
        )
        XCTAssertEqual(result, .noRecords)
        XCTAssertFalse(queued)
        XCTAssertFalse(uploaded)
    }

    @MainActor func testMixedLibraryCopiesOnlySupportedRecords() async {
        var queued: [String: String] = [:]
        let result = await CloudLibraryImport.copy(
            records: ["favorite/ext:demo:movie": "external", "favorite/42": "film"],
            enqueue: { queued = $0 }, flush: { true }, refresh: { true }, destinationIsCurrent: { true }
        )
        XCTAssertEqual(result, .completed)
        XCTAssertEqual(queued, ["favorite/42": "film"])
    }
}
