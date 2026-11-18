import Foundation

/// Only public catalogue metadata enters the Home-screen extension. Never persist streams or account records.
public struct TopShelfEntry: Codable, Hashable, Sendable {
    public let mediaID: String
    public let title: String
    public let subtitle: String
    public let summary: String
    public let imageURL: URL
    public let landscape: Bool
    public let progress: Double
    public init?(media: Media, seconds: Double = 0, duration: Double = 0, completed: Bool = false,
                 landscapeURL: String = "") {
        guard !completed, !media.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              TopShelfRoute(mediaID: media.id, action: .display).url != nil else { return nil }
        let wide = Self.publicImage(landscapeURL)
        guard let image = wide ?? Self.publicImage(media.poster) ?? Self.publicImage(media.backdrop) else { return nil }
        mediaID = media.id
        title = String(media.title.prefix(200))
        subtitle = String(media.subtitle.prefix(200))
        summary = String(media.overview.prefix(800))
        imageURL = image; landscape = wide != nil
        progress = seconds.isFinite && duration.isFinite && duration > 0 ? min(1, max(0, seconds / duration)) : 0
    }
    public static func publicImage(_ raw: String) -> URL? {
        guard raw.count <= 4096, let u = URLComponents(string: raw),
              ["https", "http"].contains(u.scheme?.lowercased() ?? ""), u.host?.isEmpty == false,
              u.user == nil, u.password == nil, u.fragment == nil,
              !(u.queryItems ?? []).contains(where: {
                  let key = $0.name.lowercased()
                  return ["username", "authorization", "auth", "apikey", "api_key", "session", "jwt"].contains(key) ||
                      ["token", "password", "signature", "credential"].contains { key.contains($0) }
              }) else { return nil }
        return u.url
    }
}

public struct TopShelfSnapshot: Codable, Equatable, Sendable {
    public let version: Int
    public let context: UUID
    public let updatedAt: Date
    public let source: String
    public let continuing: [TopShelfEntry]
    public let featured: [TopShelfEntry]
    public init(context: UUID, source: String, continuing: [TopShelfEntry], featured: [TopShelfEntry], now: Date = Date()) {
        version = 1; self.context = context; updatedAt = now; self.source = source
        var seen = Set<String>()
        func bounded(_ values: [TopShelfEntry]) -> [TopShelfEntry] {
            var selected: [TopShelfEntry] = []
            for value in values where seen.insert(value.mediaID).inserted {
                selected.append(value)
                if selected.count == 8 { break }
            }
            return selected
        }
        self.continuing = bounded(continuing)
        self.featured = bounded(featured)
    }
    public var entries: [TopShelfEntry] { continuing + featured }
    public func usable(at now: Date = Date()) -> Bool {
        version == 1 && updatedAt <= now.addingTimeInterval(60) && now.timeIntervalSince(updatedAt) < 7 * 86_400 &&
        source.count <= 256 && continuing.count <= 8 && featured.count <= 8 && entries.allSatisfy {
            !$0.mediaID.isEmpty && $0.mediaID.count <= 1024 && !$0.title.isEmpty && $0.title.count <= 200 &&
            TopShelfRoute(mediaID: $0.mediaID, action: .display).url != nil &&
            $0.subtitle.count <= 200 && $0.summary.count <= 800 && TopShelfEntry.publicImage($0.imageURL.absoluteString) != nil &&
            $0.progress.isFinite && (0...1).contains($0.progress)
        }
    }
}

/// A bounded local identifier, never a stream URL, is the only content accepted by the app's URL handler.
public struct TopShelfRoute: Equatable, Sendable {
    public enum Action: String, Codable, Sendable { case display, play }
    public let mediaID: String
    public let action: Action
    public init(mediaID: String, action: Action) { self.mediaID = mediaID; self.action = action }
    public var url: URL? {
        guard Self.validID(mediaID) else { return nil }
        var c = URLComponents(); c.scheme = "cinemaiq"; c.host = "top-shelf"
        c.queryItems = [URLQueryItem(name: "media", value: mediaID), URLQueryItem(name: "action", value: action.rawValue)]
        return c.url
    }
    public init?(url: URL) {
        guard url.absoluteString.count <= 4096, let c = URLComponents(url: url, resolvingAgainstBaseURL: false),
              c.scheme?.lowercased() == "cinemaiq", c.host == "top-shelf",
              c.path.isEmpty, c.port == nil, c.user == nil, c.password == nil, c.fragment == nil,
              let q = c.queryItems, q.count == 2, Set(q.map(\.name)) == ["media", "action"],
              let id = q.first(where: { $0.name == "media" })?.value, Self.validID(id),
              let value = q.first(where: { $0.name == "action" })?.value, let action = Action(rawValue: value) else { return nil }
        mediaID = id; self.action = action
    }
    private static func validID(_ id: String) -> Bool {
        guard !id.isEmpty, id.count <= 1024, id.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }),
              !id.contains("://") else { return false }
        return (try? ContentSource.owner(of: id)) != nil
    }
}
