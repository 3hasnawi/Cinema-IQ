import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Where the opening and the end credits are, in seconds; nil when unknown.
public struct SkipSegments: Codable, Equatable, Sendable {
    public var introStart: Double?
    public var introEnd: Double?
    public var creditsStart: Double?
    public init(introStart: Double? = nil, introEnd: Double? = nil, creditsStart: Double? = nil) {
        self.introStart = introStart; self.introEnd = introEnd; self.creditsStart = creditsStart
    }
    public var hasIntro: Bool { (introEnd ?? 0) > (introStart ?? 0) }
    /// True while `position` is inside the opening (leaving the last two seconds, where skipping is pointless).
    public func inIntro(_ position: Double) -> Bool {
        guard hasIntro, let end = introEnd else { return false }
        return position >= (introStart ?? 0) && position < end - 2
    }
}

public enum IntroDBParser {
    /// An IntroDB `segments` answer; it must be for the same title (and season and episode) that was asked for.
    public static func parse(_ data: Data, imdbID: String, season: Int, episode: Int, movie: Bool) -> SkipSegments {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              object["imdb_id"] as? String == imdbID, (object["is_movie"] as? Bool ?? false) == movie else { return SkipSegments() }
        if !movie && (number(object["season"]) != Double(season) || number(object["episode"]) != Double(episode)) { return SkipSegments() }
        func segment(_ name: String) -> (Double, Double)? {
            guard let part = object[name] as? [String: Any] else { return nil }
            func edge(_ key: String) -> Double? {
                if let ms = number(part[key + "_ms"]) { return ms / 1000 }
                return number(part[key + "_sec"])
            }
            guard let start = edge("start"), let end = edge("end"), end > start, start >= 0 else { return nil }
            return (start, end)
        }
        let intro = segment("intro")
        return SkipSegments(introStart: intro?.0, introEnd: intro?.1, creditsStart: segment("outro")?.0)
    }
    static func number(_ value: Any?) -> Double? {
        if let n = value as? NSNumber { return n.doubleValue }
        if let s = value as? String { return Double(s) }
        return nil
    }
}

/// IntroDB (api.introdb.app): anonymous, read-only skip timings by IMDb ID. Found timings are kept for a week,
/// misses for six hours.
public actor IntroDBClient {
    private struct Entry: Codable { let segments: SkipSegments; let at: Date }
    private let cacheURL: URL?
    private var cache: [String: Entry] = [:]
    private var loaded = false

    public init(cacheURL: URL?) { self.cacheURL = cacheURL }

    public func segments(imdbID: String, season: Int, episode: Int, movie: Bool) async -> SkipSegments {
        guard imdbID.range(of: "^tt\\d+$", options: .regularExpression) != nil, movie || (season > 0 && episode > 0) else { return SkipSegments() }
        let key = "\(imdbID)|\(movie)|\(season)|\(episode)"
        load()
        if let entry = cache[key], -entry.at.timeIntervalSinceNow < (entry.segments == SkipSegments() ? 6 * 3600 : 7 * 86_400) {
            return entry.segments
        }
        var components = URLComponents(string: "https://api.introdb.app/segments")!
        components.queryItems = [URLQueryItem(name: "imdb_id", value: imdbID)] + (movie
            ? [URLQueryItem(name: "is_movie", value: "true")]
            : [URLQueryItem(name: "season", value: String(season)), URLQueryItem(name: "episode", value: String(episode))])
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 6
        request.setValue("Cinema-iQ/IntroDB", forHTTPHeaderField: "User-Agent")
        guard let reply = try? await URLSession.shared.data(for: request),
              let status = (reply.1 as? HTTPURLResponse)?.statusCode, status == 200 || status == 404 else { return SkipSegments() }
        let found = status == 404 ? SkipSegments() : IntroDBParser.parse(reply.0, imdbID: imdbID, season: season, episode: episode, movie: movie)
        cache[key] = Entry(segments: found, at: Date())
        if cache.count > 300 { for old in cache.sorted(by: { $0.value.at < $1.value.at }).prefix(cache.count - 300).map(\.key) { cache[old] = nil } }
        if let cacheURL { try? JSONEncoder().encode(cache).write(to: cacheURL, options: .atomic) }
        return found
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let cacheURL, let data = try? Data(contentsOf: cacheURL),
           let stored = try? JSONDecoder().decode([String: Entry].self, from: data) { cache = stored }
    }
}
