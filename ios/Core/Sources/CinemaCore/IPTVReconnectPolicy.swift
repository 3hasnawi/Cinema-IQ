import Foundation

/// Shared decisions; only the TV session opts into automatic recovery.
public enum IPTVReconnectPolicy {
    public struct Retry: Sendable, Equatable { public let delay: TimeInterval; public let renewLink: Bool }
    public static func retry(_ failure: PlaybackFailure, attempts: Int) -> Retry? {
        guard ![.protectedContent, .secureConnection, .unsupported].contains(failure) else { return nil }
        let delays: [TimeInterval] = [1, 2, 4, 8, 15, 30]
        let access = failure == .unauthorized || failure == .missing
        return Retry(delay: max(delays[max(0, min(attempts, delays.count - 1))], failure == .rateLimited ? 30 : 0),
                     renewLink: (access && attempts > 0) || (!access && failure != .rateLimited && attempts == 2))
    }
    public static func allowed(active: Bool, pausedByUser: Bool) -> Bool { active && !pausedByUser }
}
