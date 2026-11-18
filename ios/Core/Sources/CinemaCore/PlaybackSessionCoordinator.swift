import Foundation

/// Serializes potentially blocking platform audio calls away from the UI executor.
/// A retired player's delayed release must never deactivate the next player's session.
public actor PlaybackSessionCoordinator {
    private let activate: @Sendable () throws -> Void
    private let deactivate: @Sendable () throws -> Void
    private var owner: UUID?

    public init(activate: @escaping @Sendable () throws -> Void, deactivate: @escaping @Sendable () throws -> Void) {
        self.activate = activate; self.deactivate = deactivate
    }

    public func acquire(_ owner: UUID) throws {
        try Task.checkCancellation()
        try activate()
        self.owner = owner
    }

    public func release(_ owner: UUID) {
        guard self.owner == owner else { return }
        self.owner = nil
        try? deactivate()
    }
}
