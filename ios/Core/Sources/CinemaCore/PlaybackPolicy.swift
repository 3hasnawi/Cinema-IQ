import Foundation

/// Track indices belong to one AVPlayerItem and can change between qualities.
public struct PlaybackTrackIdentity: Equatable, Sendable {
    public let language: String?
    public let title: String
    public let characteristics: [String]

    public init(language: String?, title: String, characteristics: [String] = []) {
        self.language = language?.lowercased().replacingOccurrences(of: "_", with: "-")
        self.title = title
        self.characteristics = characteristics.sorted()
    }
}

public enum PlaybackPolicy {
    /// Offers Next during known credits or the last 20 seconds, and after the item finishes.
    public static func offersNext(hasNext: Bool, ended: Bool, position: Double,
                                  duration: Double, creditsStart: Double?) -> Bool {
        guard hasNext else { return false }
        if ended { return true }
        guard position.isFinite, position >= 0, duration.isFinite, duration > 0 else { return false }
        if let creditsStart, creditsStart.isFinite, creditsStart >= 0,
           creditsStart < duration, position >= creditsStart { return true }
        return duration - position <= 20
    }

    /// Never reuse an old index or guess between ambiguous language/commentary variants.
    public static func matchingTrack(_ selected: PlaybackTrackIdentity,
                                     in options: [PlaybackTrackIdentity]) -> Int? {
        if let exact = options.firstIndex(of: selected) { return exact }
        let matches = options.indices.filter { index in
            let option = options[index]
            guard option.characteristics == selected.characteristics else { return false }
            if let language = selected.language { return option.language == language }
            return option.title == selected.title
        }
        return matches.count == 1 ? matches[0] : nil
    }
}

/// Once Next or the natural end marks this playback complete, periodic/close saves must not undo it.
/// A fresh instance starts a new viewing session (including an intentional replay).
public struct PlaybackCompletion: Sendable {
    public private(set) var completed: Bool
    public init(completed: Bool = false) { self.completed = completed }
    @discardableResult public mutating func record(completed: Bool) -> Bool {
        self.completed = self.completed || completed
        return self.completed
    }
}
