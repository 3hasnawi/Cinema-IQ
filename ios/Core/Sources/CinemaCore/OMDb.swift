import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct OMDbRating: Codable, Hashable, Sendable {
    public let source: String
    public let value: String
}

/// What OMDb knows about a film or series. Empty strings where OMDb says "N/A".
/// One season's episodes in OMDb: IMDb ratings and first-shown dates, by episode number.
public struct OMDbSeason: Sendable, Equatable {
    public var ratings: [Int: String] = [:]
    public var released: [Int: String] = [:]
    public init(ratings: [Int: String] = [:], released: [Int: String] = [:]) { self.ratings = ratings; self.released = released }
}

public struct OMDbInfo: Codable, Equatable, Sendable {
    public var imdbID = ""
    public var title = ""
    public var year = ""
    public var rated = ""
    public var runtime = ""
    public var genre = ""
    public var director = ""
    public var actors = ""
    public var plot = ""
    public var poster = ""
    public var imdbRating = ""
    public var imdbVotes = ""
    public var type = ""
    public var totalSeasons = ""
    public var ratings: [OMDbRating] = []

    /// OMDb's poster is 300 px wide; the same image is served larger by changing its size suffix.
    public var largePoster: String {
        poster.replacingOccurrences(of: "_SX[0-9]+", with: "_SX1000", options: .regularExpression)
    }
    public var rottenTomatoes: String { ratings.first { $0.source == "Rotten Tomatoes" }?.value ?? "" }
    public var metacritic: String { ratings.first { $0.source == "Metacritic" }?.value ?? "" }
}

public enum OMDbParser {
    public static func info(_ data: Data) -> OMDbInfo? {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (object["Response"] as? String) == "True" else { return nil }
        func text(_ key: String) -> String {
            let value = (object[key] as? String ?? "").trimmingCharacters(in: .whitespaces)
            return value == "N/A" ? "" : value
        }
        var info = OMDbInfo()
        info.imdbID = text("imdbID"); info.title = text("Title"); info.year = text("Year"); info.rated = text("Rated")
        info.runtime = text("Runtime"); info.genre = text("Genre"); info.director = text("Director"); info.actors = text("Actors")
        info.plot = text("Plot"); info.imdbRating = text("imdbRating"); info.imdbVotes = text("imdbVotes")
        info.type = text("Type"); info.totalSeasons = text("totalSeasons")
        let poster = text("Poster")
        info.poster = poster.hasPrefix("https://") ? poster : ""
        info.ratings = (object["Ratings"] as? [[String: Any]] ?? []).compactMap { row in
            guard let source = row["Source"] as? String, let value = row["Value"] as? String, value != "N/A" else { return nil }
            return OMDbRating(source: source, value: value)
        }
        return info
    }

    /// IMDb ratings of a season's episodes (episode number → "8.1") from a `Season=` lookup; unrated ones are left out.
    public static func seasonRatings(_ data: Data) -> [Int: String]? {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (object["Response"] as? String) == "True" else { return nil }
        var ratings: [Int: String] = [:]
        for row in object["Episodes"] as? [[String: Any]] ?? [] {
            guard let number = Int((row["Episode"] as? String ?? "").trimmingCharacters(in: .whitespaces)),
                  let rating = (row["imdbRating"] as? String)?.trimmingCharacters(in: .whitespaces),
                  !rating.isEmpty, rating != "N/A" else { continue }
            ratings[number] = rating
        }
        return ratings
    }

    /// First-shown dates of a season's episodes (episode number → "2008-01-20") from a `Season=` lookup.
    public static func seasonReleases(_ data: Data) -> [Int: String]? {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (object["Response"] as? String) == "True" else { return nil }
        var released: [Int: String] = [:]
        for row in object["Episodes"] as? [[String: Any]] ?? [] {
            guard let number = Int((row["Episode"] as? String ?? "").trimmingCharacters(in: .whitespaces)),
                  let date = (row["Released"] as? String)?.trimmingCharacters(in: .whitespaces),
                  date.count >= 10, date != "N/A" else { continue }
            released[number] = date
        }
        return released
    }

    /// The Latin title to search: the source's English title, or the title itself when it is written in Latin
    /// letters. Arabic-only titles cannot be matched reliably and are skipped.
    public static func searchTitle(_ media: Media) -> String? {
        for candidate in [media.enTitle, media.title] {
            let cleaned = candidate
                .replacingOccurrences(of: "\\(\\d{4}\\)", with: "", options: .regularExpression)
                .replacingOccurrences(of: "(?i)\\b(season|s)\\s*\\d+\\b.*$", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let latin = cleaned.unicodeScalars.filter { CharacterSet.letters.contains($0) }
            guard !latin.isEmpty, latin.allSatisfy({ $0.value < 0x0250 }) else { continue }
            return cleaned
        }
        return nil
    }
    public static func year(_ media: Media) -> String? {
        let digits = media.year.prefix(4)
        return digits.count == 4 && digits.allSatisfy(\.isNumber) ? String(digits) : nil
    }
}

/// OMDb lookups with a disk cache. The free key allows about 1000 requests a day, so found works are kept for
/// two weeks and misses for three days; network failures are not remembered.
public actor OMDbClient {
    private struct Entry: Codable { let info: OMDbInfo?; let at: Date }
    private struct SeasonEntry: Codable { let ratings: [Int: String]?; var released: [Int: String]? = nil; let at: Date }
    private let key: String
    private let cacheURL: URL?
    private var cache: [String: Entry] = [:]
    private var seasonCache: [String: SeasonEntry] = [:]
    private var loaded = false
    private var inFlight: [String: Task<OMDbInfo?, Never>] = [:]
    private var seasonsInFlight: [String: Task<OMDbSeason?, Never>] = [:]
    /// Season lookups live in their own file so the format of the works cache stays as it was.
    private var seasonCacheURL: URL? {
        cacheURL.map { $0.deletingPathExtension().appendingPathExtension("seasons.json") }
    }

    public init(key: String, cacheURL: URL?) {
        self.key = key; self.cacheURL = cacheURL
    }

    public func info(for media: Media) async -> OMDbInfo? {
        guard !key.isEmpty, let title = OMDbParser.searchTitle(media) else { return nil }
        let type = media.isSeries || media.episode > 0 ? "series" : "movie"
        let year = media.episode > 0 ? nil : OMDbParser.year(media)
        let cacheKey = "\(type)|\(title.lowercased())|\(year ?? "")"
        loadCache()
        let days: Double = 86_400
        if let entry = cache[cacheKey], -entry.at.timeIntervalSinceNow < (entry.info == nil ? 3 * days : 14 * days) { return entry.info }
        if let running = inFlight[cacheKey] { return await running.value }
        let task = Task { await self.fetch(title: title, type: type, year: year, cacheKey: cacheKey) }
        inFlight[cacheKey] = task
        let result = await task.value
        inFlight[cacheKey] = nil
        return result
    }

    /// IMDb ratings of the episodes of one season of a series: one request per season, cached like works.
    public func episodeRatings(for media: Media, season: Int) async -> [Int: String] {
        await seasonDetails(for: media, season: season).ratings
    }

    /// Ratings and first-shown dates of one season's episodes, from the same cached request.
    public func seasonDetails(for media: Media, season: Int) async -> OMDbSeason {
        guard !key.isEmpty, season > 0, let title = OMDbParser.searchTitle(media) else { return OMDbSeason() }
        let cacheKey = "\(title.lowercased())|\(season)"
        loadCache()
        let days: Double = 86_400
        // Entries saved before dates were kept (ratings without dates) are fetched again once.
        if let entry = seasonCache[cacheKey], -entry.at.timeIntervalSinceNow < (entry.ratings == nil ? 3 * days : 7 * days),
           entry.ratings == nil || entry.released != nil {
            return OMDbSeason(ratings: entry.ratings ?? [:], released: entry.released ?? [:])
        }
        if let running = seasonsInFlight[cacheKey] { return await running.value ?? OMDbSeason() }
        let task = Task { () -> OMDbSeason? in
            var components = URLComponents(string: "https://www.omdbapi.com/")!
            components.queryItems = [URLQueryItem(name: "apikey", value: self.key), URLQueryItem(name: "t", value: title),
                                     URLQueryItem(name: "type", value: "series"), URLQueryItem(name: "Season", value: String(season))]
            var request = URLRequest(url: components.url!)
            request.timeoutInterval = 12
            guard let reply = try? await URLSession.shared.data(for: request),
                  (reply.1 as? HTTPURLResponse)?.statusCode == 200 else { return nil }
            return OMDbSeason(ratings: OMDbParser.seasonRatings(reply.0) ?? [:], released: OMDbParser.seasonReleases(reply.0) ?? [:])
        }
        seasonsInFlight[cacheKey] = task
        let result = await task.value
        seasonsInFlight[cacheKey] = nil
        // Network failures are not remembered; "not found" is, as an empty entry.
        if let result {
            let empty = result.ratings.isEmpty && result.released.isEmpty
            seasonCache[cacheKey] = SeasonEntry(ratings: empty ? nil : result.ratings, released: empty ? nil : result.released, at: Date())
            saveSeasonCache()
        }
        return result ?? OMDbSeason()
    }

    private func fetch(title: String, type: String, year: String?, cacheKey: String) async -> OMDbInfo? {
        do {
            // A season's year often differs from the series' first year, so retry without it.
            var found = try await request(title: title, type: type, year: year)
            if found == nil, year != nil { found = try await request(title: title, type: type, year: nil) }
            cache[cacheKey] = Entry(info: found, at: Date())
            saveCache()
            return found
        } catch {
            return nil
        }
    }

    private func request(title: String, type: String, year: String?) async throws -> OMDbInfo? {
        var components = URLComponents(string: "https://www.omdbapi.com/")!
        components.queryItems = [URLQueryItem(name: "apikey", value: key), URLQueryItem(name: "t", value: title),
                                 URLQueryItem(name: "type", value: type), URLQueryItem(name: "plot", value: "full")]
            + (year.map { [URLQueryItem(name: "y", value: $0)] } ?? [])
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 12
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw CinemaError.invalidResponse }
        // 401 means the key is wrong or today's limit is used up: not a "not found".
        guard http.statusCode == 200 else { throw CinemaError.http(http.statusCode) }
        return OMDbParser.info(data)
    }

    private func loadCache() {
        guard !loaded else { return }
        loaded = true
        guard let cacheURL, let data = try? Data(contentsOf: cacheURL),
              let stored = try? JSONDecoder().decode([String: Entry].self, from: data) else { loadSeasonCache(); return }
        cache = stored
        loadSeasonCache()
    }
    private func loadSeasonCache() {
        guard let seasonCacheURL, let data = try? Data(contentsOf: seasonCacheURL),
              let stored = try? JSONDecoder().decode([String: SeasonEntry].self, from: data) else { return }
        seasonCache = stored
    }
    private func saveSeasonCache() {
        guard let seasonCacheURL else { return }
        if seasonCache.count > 500 {
            for old in seasonCache.sorted(by: { $0.value.at < $1.value.at }).prefix(seasonCache.count - 500).map(\.key) { seasonCache[old] = nil }
        }
        try? JSONEncoder().encode(seasonCache).write(to: seasonCacheURL, options: .atomic)
    }
    private func saveCache() {
        guard let cacheURL else { return }
        if cache.count > 2000 {
            for old in cache.sorted(by: { $0.value.at < $1.value.at }).prefix(cache.count - 2000).map(\.key) { cache[old] = nil }
        }
        try? JSONEncoder().encode(cache).write(to: cacheURL, options: .atomic)
    }
}
