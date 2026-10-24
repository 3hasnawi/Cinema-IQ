import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One actor of a work, with a portrait when TMDB has one.
public struct TMDBPerson: Codable, Hashable, Sendable, Identifiable {
    public let id: Int
    public let name: String
    public let photo: String
    public init(id: Int, name: String, photo: String) { self.id = id; self.name = name; self.photo = photo }
}

/// What TMDB adds to a work: its IMDb ID (for IntroDB and OMDb), cast with portraits, directors or creators,
/// sharper artwork and an Arabic story when the source has none.
public struct TMDBWork: Codable, Equatable, Sendable {
    public var id = 0
    public var imdbID = ""
    public var cast: [TMDBPerson] = []
    public var directors: [String] = []
    public var creators: [String] = []
    public var overview = ""
    public var backdrop = ""
    public var poster = ""
    public var year = ""
    public init() {}
}

/// One season in TMDB: its first air date and, by episode number, each episode's date, name, story and still.
public struct TMDBSeason: Codable, Equatable, Sendable {
    public struct Episode: Codable, Equatable, Sendable {
        public var airDate = ""
        public var name = ""
        public var overview = ""
        public var still = ""
    }
    public var airDate = ""
    public var episodes: [Int: Episode] = [:]
    public init() {}
}

public enum TMDBParser {
    static let images = "https://image.tmdb.org/t/p/"

    /// Letters and digits only, lowercased: "Slow Horses!" and "slow horses" match.
    public static func normalize(_ text: String) -> String {
        text.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(String.init).joined()
    }

    /// The first result whose title matches (exactly, or containing it for longer titles when the year agrees)
    /// and whose year is within one of the wanted year; nil when none does.
    public static func pick(_ results: [[String: Any]], title: String, year: Int?) -> [String: Any]? {
        let wanted = normalize(title)
        guard !wanted.isEmpty else { return nil }
        for result in results {
            if text(result, "backdrop_path").isEmpty && text(result, "poster_path").isEmpty { continue }
            let resultYear = Int(text(result, "release_date", "first_air_date").prefix(4))
            if let year, let resultYear, abs(resultYear - year) > 1 { continue }
            let names = [text(result, "title", "name"), text(result, "original_title", "original_name")].map(normalize).filter { !$0.isEmpty }
            let exact = names.contains(wanted)
            let loose = year != nil && resultYear != nil && wanted.count >= 6 &&
                names.contains { $0.contains(wanted) || (wanted.contains($0) && $0.count >= 6) }
            if exact || loose { return result }
        }
        return nil
    }

    /// A work's details page fetched with `append_to_response=credits,external_ids`.
    public static func work(_ data: [String: Any], series: Bool) -> TMDBWork {
        var work = TMDBWork()
        work.id = data["id"] as? Int ?? 0
        let imdb = text(data, "imdb_id").isEmpty ? text(data["external_ids"] as? [String: Any] ?? [:], "imdb_id") : text(data, "imdb_id")
        work.imdbID = imdb.range(of: "^tt\\d+$", options: .regularExpression) != nil ? imdb : ""
        let credits = data["credits"] as? [String: Any] ?? [:]
        var seen = Set<Int>()
        work.cast = (credits["cast"] as? [[String: Any]] ?? []).compactMap { actor -> TMDBPerson? in
            guard let id = actor["id"] as? Int, id > 0, seen.insert(id).inserted else { return nil }
            let name = text(actor, "name", "original_name")
            guard !name.isEmpty else { return nil }
            let path = text(actor, "profile_path")
            return TMDBPerson(id: id, name: name, photo: path.isEmpty ? "" : images + "w185" + path)
        }.prefix(30).map { $0 }
        work.directors = unique((credits["crew"] as? [[String: Any]] ?? []).filter { text($0, "job") == "Director" }.map { text($0, "name") })
        if series { work.creators = unique((data["created_by"] as? [[String: Any]] ?? []).map { text($0, "name") }) }
        work.overview = text(data, "overview")
        let backdrop = text(data, "backdrop_path"), poster = text(data, "poster_path")
        work.backdrop = backdrop.isEmpty ? "" : images + "w1280" + backdrop
        work.poster = poster.isEmpty ? "" : images + "w780" + poster
        let year = String(text(data, series ? "first_air_date" : "release_date").prefix(4))
        work.year = year.count == 4 && year.allSatisfy(\.isNumber) ? year : ""
        return work
    }

    /// A season page (`tv/{id}/season/{n}`).
    public static func season(_ data: [String: Any]) -> TMDBSeason {
        var season = TMDBSeason()
        season.airDate = text(data, "air_date")
        for row in data["episodes"] as? [[String: Any]] ?? [] {
            guard let number = row["episode_number"] as? Int, number > 0 else { continue }
            var episode = TMDBSeason.Episode()
            episode.airDate = text(row, "air_date")
            episode.name = text(row, "name")
            episode.overview = text(row, "overview")
            let still = text(row, "still_path")
            episode.still = still.isEmpty ? "" : images + "w780" + still
            season.episodes[number] = episode
        }
        return season
    }

    static func text(_ object: [String: Any], _ keys: String...) -> String {
        for key in keys {
            if let value = object[key] as? String, !value.isEmpty { return value.trimmingCharacters(in: .whitespaces) }
        }
        return ""
    }
    static func unique(_ names: [String]) -> [String] {
        var seen = Set<String>()
        return names.filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}

/// TMDB lookups (Arabic first) with a disk cache: works for a week, misses for a day; seasons for six hours.
/// Network failures are not remembered.
public actor TMDBClient {
    private struct WorkEntry: Codable { let work: TMDBWork?; let at: Date }
    private struct SeasonEntry: Codable { let season: TMDBSeason; let at: Date }
    private let key: String
    private let cacheURL: URL?
    private var works: [String: WorkEntry] = [:]
    private var seasons: [String: SeasonEntry] = [:]
    private var loaded = false
    private var inFlight: [String: Task<TMDBWork?, Never>] = [:]

    public init(key: String, cacheURL: URL?) { self.key = key; self.cacheURL = cacheURL }

    /// The work behind a movie or series (for an episode, pass its series).
    public func work(for media: Media) async -> TMDBWork? {
        guard !key.isEmpty else { return nil }
        let series = media.isSeries || media.episode > 0
        let cacheKey = "\(series ? "tv" : "movie")|\(media.id)"
        load()
        if let entry = works[cacheKey], -entry.at.timeIntervalSinceNow < (entry.work == nil ? 86_400 : 7 * 86_400) { return entry.work }
        if let running = inFlight[cacheKey] { return await running.value }
        let task = Task { () -> TMDBWork? in
            do {
                let found = try await self.find(media, series: series)
                await self.remember(cacheKey, found)
                return found
            } catch { return nil }
        }
        inFlight[cacheKey] = task
        let result = await task.value
        inFlight[cacheKey] = nil
        return result
    }

    /// One season of a series.
    public func season(_ number: Int, of work: TMDBWork) async -> TMDBSeason? {
        guard !key.isEmpty, work.id > 0, number >= 0 else { return nil }
        let cacheKey = "\(work.id)|\(number)"
        load()
        if let entry = seasons[cacheKey], -entry.at.timeIntervalSinceNow < 6 * 3600 { return entry.season }
        guard let data = try? await get("3/tv/\(work.id)/season/\(number)", ["language": "ar"]) else { return nil }
        let season = TMDBParser.season(data)
        seasons[cacheKey] = SeasonEntry(season: season, at: Date())
        save()
        return season
    }

    private func find(_ media: Media, series: Bool) async throws -> TMDBWork? {
        let year = Int(media.year.prefix(4))
        var titles: [String] = []
        for title in [media.enTitle, media.title, OMDbParser.searchTitle(media) ?? ""] {
            let cleaned = title.replacingOccurrences(of: "\\s*-\\s*S\\d+E\\d+.*$", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            if !cleaned.isEmpty && !titles.contains(cleaned) { titles.append(cleaned) }
        }
        for title in titles {
            for useYear in [true, false] where !useYear || year != nil {
                var query = ["query": title, "language": "ar", "include_adult": "false"]
                if useYear, let year { query[series ? "first_air_date_year" : "year"] = String(year) }
                let results = try await get(series ? "3/search/tv" : "3/search/movie", query)["results"] as? [[String: Any]] ?? []
                guard let match = TMDBParser.pick(results, title: title, year: useYear ? year : nil), let id = match["id"] as? Int else { continue }
                let data = try await get("3/\(series ? "tv" : "movie")/\(id)",
                                         ["language": "ar", "append_to_response": "credits,external_ids"])
                return TMDBParser.work(data, series: series)
            }
        }
        return nil
    }

    private func get(_ path: String, _ query: [String: String]) async throws -> [String: Any] {
        var components = URLComponents(string: "https://api.themoviedb.org/" + path)!
        components.queryItems = [URLQueryItem(name: "api_key", value: key)] + query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 12
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw CinemaError.invalidResponse }
        return object
    }

    private func remember(_ cacheKey: String, _ work: TMDBWork?) {
        works[cacheKey] = WorkEntry(work: work, at: Date())
        save()
    }
    private var seasonsURL: URL? { cacheURL.map { $0.deletingPathExtension().appendingPathExtension("seasons.json") } }
    private func load() {
        guard !loaded else { return }
        loaded = true
        if let cacheURL, let data = try? Data(contentsOf: cacheURL),
           let stored = try? JSONDecoder().decode([String: WorkEntry].self, from: data) { works = stored }
        if let seasonsURL, let data = try? Data(contentsOf: seasonsURL),
           let stored = try? JSONDecoder().decode([String: SeasonEntry].self, from: data) { seasons = stored }
    }
    private func save() {
        if works.count > 1000 { for old in works.sorted(by: { $0.value.at < $1.value.at }).prefix(works.count - 1000).map(\.key) { works[old] = nil } }
        if seasons.count > 300 { for old in seasons.sorted(by: { $0.value.at < $1.value.at }).prefix(seasons.count - 300).map(\.key) { seasons[old] = nil } }
        if let cacheURL { try? JSONEncoder().encode(works).write(to: cacheURL, options: .atomic) }
        if let seasonsURL { try? JSONEncoder().encode(seasons).write(to: seasonsURL, options: .atomic) }
    }
}
