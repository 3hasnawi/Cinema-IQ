import Foundation

/// Keeps one native seek in flight and retains the latest time until the decoder confirms it.
/// Resetting retires every old completion, including one arriving after another movie opens.
public struct PlaybackSeekQueue: Sendable {
    public struct Attempt: Equatable, Sendable {
        public let id: UUID
        public let seconds: Double
        fileprivate let intent: UUID
    }

    public enum Completion: Equatable, Sendable {
        case ignored, settled, seekLatest, awaitTrigger
    }

    private struct Intent: Sendable {
        let id = UUID()
        let seconds: Double
        var immediateRetries = 1
    }

    private var pending: Intent?
    private var active: Attempt?
    public var target: Double? { pending?.seconds }
    public var isPending: Bool { pending != nil }

    public init() {}

    @discardableResult public mutating func enqueue(_ seconds: Double) -> Bool {
        guard seconds.isFinite else { return false }
        let target = max(0, seconds)
        if pending?.seconds != target { pending = Intent(seconds: target) }
        return true
    }

    public mutating func begin() -> Attempt? {
        guard active == nil, let pending else { return nil }
        let attempt = Attempt(id: UUID(), seconds: pending.seconds, intent: pending.id)
        active = attempt
        return attempt
    }

    public mutating func complete(_ attempt: Attempt, finished: Bool) -> Completion {
        guard active?.id == attempt.id else { return .ignored }
        active = nil
        guard var latest = pending else { return .settled }
        // An older successful seek must not clear a newer requested position.
        guard latest.id == attempt.intent else { return .seekLatest }
        if finished { pending = nil; return .settled }
        // A media-selection/transport operation can interrupt a seek. Retry once,
        // then retain the target for the next ready/play transition without spinning.
        if latest.immediateRetries > 0 {
            latest.immediateRetries -= 1; pending = latest
            return .seekLatest
        }
        return .awaitTrigger
    }

    public mutating func reset() { pending = nil; active = nil }
}
