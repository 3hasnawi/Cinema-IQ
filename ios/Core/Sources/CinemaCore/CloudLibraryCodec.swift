import Foundation

/// Converts the iPhone library to and from the Android app's cloud records:
/// `favorite/<id>` and `recent/<series or id>` hold a title as Android's film JSON, `playback/<id>` holds
/// position and duration in milliseconds, the completed flag and, once finished, the title with `watchedAt`.
/// An empty value is a removal (tombstone).
public enum CloudLibraryCodec {
    public struct Playback: Equatable, Sendable {
        public var seconds: Double
        public var duration: Double
        public var completed: Bool
        public var watched: Media?
        public init(seconds: Double, duration: Double, completed: Bool, watched: Media? = nil) {
            self.seconds = seconds; self.duration = duration; self.completed = completed; self.watched = watched
        }
    }

    public static func documentID(_ key: String) -> String { SHA256.hex(key) }

    public static func filmJSON(_ media: Media) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        guard let data = try? encoder.encode(media), let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }
    public static func film(_ json: String) -> Media? {
        guard !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Media.self, from: data)
    }

    public static func playbackJSON(_ playback: Playback, watchedAt: Date = Date()) -> String {
        var object: [String: Any] = [
            "position": Int64((max(0, playback.seconds) * 1000).rounded()),
            "duration": Int64((max(0, playback.duration) * 1000).rounded()),
            "completed": playback.completed
        ]
        if playback.completed, let film = playback.watched,
           let data = filmJSON(film).data(using: .utf8),
           var watched = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            watched["watchedAt"] = Int64(watchedAt.timeIntervalSince1970 * 1000)
            object["watched"] = watched
        }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }

    public static func playback(_ json: String) -> Playback? {
        guard !json.isEmpty, let data = json.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        func number(_ key: String) -> Double { (object[key] as? NSNumber)?.doubleValue ?? Double(object[key] as? String ?? "") ?? 0 }
        var watched: Media?
        if let film = object["watched"] as? [String: Any], let filmData = try? JSONSerialization.data(withJSONObject: film) {
            watched = try? JSONDecoder().decode(Media.self, from: filmData)
        }
        let completed = (object["completed"] as? Bool) ?? ((object["completed"] as? NSNumber)?.boolValue ?? false)
        return Playback(seconds: number("position") / 1000, duration: number("duration") / 1000, completed: completed, watched: watched)
    }

    /// The `recent/` key groups the episodes of a series, as on Android.
    public static func recentKey(_ media: Media) -> String { "recent/" + (media.seriesID.isEmpty ? media.id : media.seriesID) }
}
