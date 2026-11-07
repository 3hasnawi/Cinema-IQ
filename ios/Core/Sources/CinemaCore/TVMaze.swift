import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A person identified by TVmaze, not by TMDB. Keep the namespace when combining providers.
public struct TVMazePerson: Codable, Hashable, Sendable, Identifiable {
    public let id: Int
    public let name: String
    public let photo: String
    public var stableID: String { "tvmaze:\(id)" }
    public init(id: Int, name: String, photo: String) {
        self.id = id; self.name = name; self.photo = photo
    }
}

/// Cast from an exactly identified series. Display sourceURL as the TVmaze attribution link.
public struct TVMazeCast: Codable, Equatable, Sendable {
    public let showID: Int
    public let imdbID: String
    public let sourceURL: String
    public let cast: [TVMazePerson]
    public init(showID: Int, imdbID: String, sourceURL: String, cast: [TVMazePerson]) {
        self.showID = showID; self.imdbID = imdbID; self.sourceURL = sourceURL; self.cast = cast
    }
}

public enum TVMazeParser {
    public static func isValidIMDbID(_ value: String) -> Bool {
        value.range(of: "^tt[0-9]{7,12}$", options: .regularExpression) == (value.startIndex..<value.endIndex)
    }

    /// Conservative source-title identity: Latin letters, digits and punctuation only. Preserve word
    /// boundaries, accents and punctuation; normalize only case and whitespace, never fuzzy similarity.
    public static func normalizedTitle(_ value: String) -> String? {
        let title = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.count <= 200,
              title.unicodeScalars.contains(where: { CharacterSet.letters.contains($0) }),
              title.range(of: "^[\\p{Latin}\\p{M}0-9\\p{P}\\s]+$", options: .regularExpression) != nil else { return nil }
        return title.lowercased(with: Locale(identifier: "en_US_POSIX"))
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    public static func premiereYear(_ value: String) -> String? {
        guard value.range(of: "^[12][0-9]{3}$", options: .regularExpression) == (value.startIndex..<value.endIndex) else { return nil }
        return value
    }

    /// Search ranking is not identity. Require exactly one same-name result across all premiere years,
    /// then require its premiere year to agree. Even a same-name remake with a different year is rejected.
    public static func exactTitleShow(_ rows: [[String: Any]], title: String, year: String) -> [String: Any]? {
        guard let wanted = normalizedTitle(title), premiereYear(year) != nil else { return nil }
        let matches = rows.compactMap { $0["show"] as? [String: Any] }.filter {
            normalizedTitle($0["name"] as? String ?? "") == wanted
        }
        guard matches.count == 1, let show = matches.first, let id = show["id"] as? Int, id > 0,
              let premiered = show["premiered"] as? String,
              premiered.range(of: "^[12][0-9]{3}-[0-9]{2}-[0-9]{2}$", options: .regularExpression) != nil,
              String(premiered.prefix(4)) == year else { return nil }
        return show
    }

    /// Only TVmaze's HTTPS image CDN is eligible. Missing or unsafe images keep their actor's name.
    public static func portraitURL(_ value: String) -> String {
        guard let url = URLComponents(string: value), url.scheme == "https",
              url.host?.lowercased() == "static.tvmaze.com", url.port == nil || url.port == 443,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.path.hasPrefix("/uploads/images/"), !url.path.contains(".."),
              let result = url.url else { return "" }
        return result.absoluteString
    }

    public static func cast(show: [String: Any], rows: [[String: Any]], imdbID: String) -> TVMazeCast? {
        guard isValidIMDbID(imdbID), let showID = show["id"] as? Int, showID > 0,
              let external = show["externals"] as? [String: Any], external["imdb"] as? String == imdbID else { return nil }
        return cast(showID: showID, rows: rows, imdbID: imdbID)
    }

    fileprivate static func cast(showID: Int, rows: [[String: Any]], imdbID: String) -> TVMazeCast {
        var seen = Set<Int>()
        let people = rows.compactMap { row -> TVMazePerson? in
            guard let person = row["person"] as? [String: Any],
                  let id = person["id"] as? Int, id > 0,
                  let rawName = person["name"] as? String else { return nil }
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, name != "N/A", seen.insert(id).inserted else { return nil }
            let image = person["image"] as? [String: Any] ?? [:]
            let medium = portraitURL(image["medium"] as? String ?? "")
            let original = portraitURL(image["original"] as? String ?? "")
            // A character image may depict somebody else and must never substitute for the person's image.
            return TVMazePerson(id: id, name: name, photo: medium.isEmpty ? original : medium)
        }.prefix(30).map { $0 }
        return TVMazeCast(showID: showID, imdbID: imdbID,
                          sourceURL: "https://www.tvmaze.com/shows/\(showID)", cast: people)
    }
}

private final class TVMazeRedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let shared = TVMazeRedirectGuard()

    static func permits(_ url: URL?) -> Bool {
        guard let url, let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        return parts.scheme == "https" && parts.host?.lowercased() == "api.tvmaze.com" &&
            (parts.port == nil || parts.port == 443) && parts.user == nil && parts.password == nil
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(Self.permits(request.url) ? request : nil)
    }
}

/// Keyless public TVmaze series cast, preferring a consistent IMDb identity, otherwise a strict
/// source title/premiere-year match. No person search or source account credentials are used. See https://www.tvmaze.com/api for
/// attribution and CC BY-SA requirements. Network failures, rate limits and malformed responses are not cached.
public actor TVMazeClient {
    private struct Entry: Codable { let value: TVMazeCast?; let at: Date }
    private let cacheURL: URL?
    private let session: URLSession
    private let now: @Sendable () -> Date
    private var entries: [String: Entry] = [:]
    private var inFlight: [String: Task<TVMazeCast?, Never>] = [:]
    private var loaded = false

    public init(cacheURL: URL? = nil, session: URLSession = .shared) {
        self.cacheURL = cacheURL; self.session = session; self.now = { Date() }
    }

    /// An internal clock allows deterministic expiry tests without sleeping.
    init(cacheURL: URL? = nil, session: URLSession, now: @escaping @Sendable () -> Date) {
        self.cacheURL = cacheURL; self.session = session; self.now = now
    }

    /// A valid metadata ID is authoritative, including a miss; conflicting IDs never permit title search.
    /// Title searches are deliberately not persisted, so changing search ambiguity is re-evaluated.
    public func cast(for media: Media, tmdbID: String, omdbID: String) async -> TVMazeCast? {
        guard media.isSeries || media.episode > 0, !Task.isCancelled else { return nil }
        let tmdb = TVMazeParser.isValidIMDbID(tmdbID) ? tmdbID : nil
        let omdb = TVMazeParser.isValidIMDbID(omdbID) ? omdbID : nil
        if let tmdb, let omdb, tmdb != omdb { return nil }
        if let identity = tmdb ?? omdb { return await cast(imdbID: identity) }
        // Episode years describe episodes, not necessarily the series premiere. Never search with them.
        guard media.isSeries, media.episode == 0 else { return nil }
        let rawTitle = media.enTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? media.title : media.enTitle
        guard let title = TVMazeParser.normalizedTitle(rawTitle),
              let year = TVMazeParser.premiereYear(media.year) else { return nil }
        do {
            var search = URLComponents(string: "https://api.tvmaze.com/search/shows")!
            search.queryItems = [URLQueryItem(name: "q", value: title)]
            guard let data = try await get(search.url!),
                  let results = try JSONSerialization.jsonObject(with: data) as? [[String: Any]],
                  let show = TVMazeParser.exactTitleShow(results, title: title, year: year),
                  let showID = show["id"] as? Int else { return nil }
            try Task.checkCancellation()
            guard let castData = try await get(URL(string: "https://api.tvmaze.com/shows/\(showID)/cast")!),
                  let rows = try JSONSerialization.jsonObject(with: castData) as? [[String: Any]] else { return nil }
            try Task.checkCancellation()
            let externalID = (show["externals"] as? [String: Any])?["imdb"] as? String ?? ""
            return TVMazeParser.cast(showID: showID, rows: rows,
                                     imdbID: TVMazeParser.isValidIMDbID(externalID) ? externalID : "")
        } catch { return nil }
    }

    public func cast(imdbID: String) async -> TVMazeCast? {
        guard TVMazeParser.isValidIMDbID(imdbID), !Task.isCancelled else { return nil }
        load()
        if let entry = entries[imdbID] {
            let age = now().timeIntervalSince(entry.at)
            let ttl: TimeInterval = entry.value == nil ? 86_400 : (entry.value?.cast.isEmpty == true ? 3600 : 7 * 86_400)
            if age >= 0, age < ttl { return entry.value }
        }
        if let running = inFlight[imdbID] { return await running.value }
        let task = Task { () -> TVMazeCast? in
            do {
                let result = try await self.fetch(imdbID: imdbID)
                try Task.checkCancellation()
                self.remember(result, imdbID: imdbID)
                return result
            } catch { return nil }
        }
        inFlight[imdbID] = task
        let value = await task.value
        inFlight[imdbID] = nil
        return Task.isCancelled ? nil : value
    }

    private func fetch(imdbID: String) async throws -> TVMazeCast? {
        var lookup = URLComponents(string: "https://api.tvmaze.com/lookup/shows")!
        lookup.queryItems = [URLQueryItem(name: "imdb", value: imdbID)]
        guard let data = try await get(lookup.url!, missingAllowed: true) else { return nil }
        guard let show = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let identity = TVMazeParser.cast(show: show, rows: [], imdbID: imdbID) else { throw CinemaError.invalidResponse }
        let url = URL(string: "https://api.tvmaze.com/shows/\(identity.showID)/cast")!
        guard let castData = try await get(url),
              let rows = try JSONSerialization.jsonObject(with: castData) as? [[String: Any]],
              let result = TVMazeParser.cast(show: show, rows: rows, imdbID: imdbID) else { throw CinemaError.invalidResponse }
        return result
    }

    private func get(_ url: URL, missingAllowed: Bool = false) async throws -> Data? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.httpShouldHandleCookies = false
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("CinemaIQ/1.0 (series metadata)", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request, delegate: TVMazeRedirectGuard.shared)
        guard let http = response as? HTTPURLResponse, TVMazeRedirectGuard.permits(http.url) else { throw CinemaError.invalidResponse }
        if http.statusCode == 404, missingAllowed { return nil }
        guard http.statusCode == 200, data.count <= 2_000_000 else { throw CinemaError.invalidResponse }
        return data
    }

    private func remember(_ value: TVMazeCast?, imdbID: String) {
        entries[imdbID] = Entry(value: value, at: now())
        if entries.count > 500 {
            for key in entries.sorted(by: { $0.value.at < $1.value.at }).prefix(entries.count - 500).map(\.key) { entries[key] = nil }
        }
        guard let cacheURL else { return }
        try? JSONEncoder().encode(entries).write(to: cacheURL, options: .atomic)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let cacheURL, let data = try? Data(contentsOf: cacheURL), data.count <= 4_000_000,
              let stored = try? JSONDecoder().decode([String: Entry].self, from: data) else { return }
        for (key, entry) in stored.sorted(by: { $0.value.at > $1.value.at }).prefix(500) {
            guard TVMazeParser.isValidIMDbID(key) else { continue }
            if let value = entry.value {
                guard value.imdbID == key, value.showID > 0,
                      value.sourceURL == "https://www.tvmaze.com/shows/\(value.showID)", value.cast.count <= 30,
                      Set(value.cast.map(\.id)).count == value.cast.count,
                      value.cast.allSatisfy({ $0.id > 0 && !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                          ($0.photo.isEmpty || TVMazeParser.portraitURL($0.photo) == $0.photo) }) else { continue }
            }
            entries[key] = entry
        }
    }
}
