import Foundation

/// Coordinates an explicit device-library copy without mistaking a queued write for a completed upload.
/// The caller owns persistence and networking; these closures also make interrupted imports testable offline.
@MainActor public enum CloudLibraryImport {
    public enum Outcome: Equatable, Sendable {
        case noRecords
        case pendingUpload
        case uploadedNeedsRefresh
        case completed
        case destinationChanged
    }

    public static func copy(
        records: [String: String],
        enqueue: ([String: String]) -> Void,
        flush: () async -> Bool,
        refresh: () async -> Bool,
        destinationIsCurrent: () -> Bool
    ) async -> Outcome {
        // Android does not understand external providers; never report that these records were uploaded.
        let transferable = records.filter { !$0.key.contains("ext:") }
        guard !transferable.isEmpty else { return .noRecords }
        guard destinationIsCurrent() else { return .destinationChanged }
        // Persist first, so a failure or an account switch cannot discard the copy request.
        enqueue(transferable)
        let uploaded = await flush()
        guard destinationIsCurrent() else { return .destinationChanged }
        guard uploaded else { return .pendingUpload }
        let refreshed = await refresh()
        guard destinationIsCurrent() else { return .destinationChanged }
        return refreshed ? .completed : .uploadedNeedsRefresh
    }
}
