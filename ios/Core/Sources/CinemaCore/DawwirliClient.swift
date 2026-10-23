import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct DawwirliClient: Sendable {
    public init() {}
    public static func url(_ path: String, _ query: [String: String] = [:]) -> URL {
        var c = URLComponents(string: "https://premium.dawwirli.watch/" + path)!
        c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return c.url!
    }
    private func get(_ path: String, _ query: [String: String] = [:]) async throws -> [String: Any] {
        guard let json = try await SourceHTTP.json(Self.url(path, query), root: "dawwirli.watch") as? [String: Any] else { throw CinemaError.invalidResponse }
        return json
    }
    public func home() async throws -> [Shelf] {
        let data = try await get("api/ar/home")
        var shelves: [Shelf] = []
        let hero = Self.items(data["hero"])
        if !hero.isEmpty { shelves.append(Shelf(id: "featured", title: "مميز على دورلي", items: hero)) }
        for (i, row) in (data["rows"] as? [[String: Any]] ?? []).enumerated() {
            let items = Self.items(row["items"], series: Self.text(row, "kind") == "series")
            if !items.isEmpty { shelves.append(Shelf(id: "row-\(i)", title: Self.text(row, "title"), items: items)) }
        }
        return shelves
    }
    public func search(_ query: String) async throws -> [Media] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return [] }
        return Self.items(try await get("api/search", ["q": trimmed])["items"])
    }
    public func detail(_ media: Media) async throws -> (Media, [Season]) {
        let key = try DawKey(media.id)
        if key.type == "episode" {
            let seasons = Self.parseSeasons(try await get("api/series_info", ["series_id": key.parent]), parent: key.parent)
            guard let episode = seasons.flatMap(\.episodes).first(where: { $0.id == media.id }) else { throw CinemaError.invalidResponse }
            return (episode, seasons)
        }
        let data = try await get(key.type == "series" ? "api/series_info" : "api/info", [key.type == "series" ? "series_id" : "id": key.raw])
        guard let info = data["info"] as? [String: Any] else { throw CinemaError.invalidResponse }
        var result = media
        let title = Self.cleanTitle(Self.text(info, "name", "o_name"))
        if !title.isEmpty { result.title = title }
        result.overview = Self.text(info, "plot", "description")
        let poster = Self.image(Self.text(info, "cover_big", "movie_image", "cover"))
        if !poster.isEmpty { result.poster = poster }
        result.backdrop = Self.image((info["backdrop_path"] as? [String])?.first ?? "")
        result.year = String(Self.text(info, "releasedate", "releaseDate").prefix(4))
        result.duration = Self.text(info, "duration", "episode_run_time")
        result.rating = Self.text(info, "rating")
        return (result, key.type == "series" ? Self.parseSeasons(data, parent: key.raw) : [])
    }
    public static func cleanTitle(_ raw: String) -> String {
        raw.replacingOccurrences(of: "^(?:AR|EN|TR|HI|KR|FR)\\s*[|:-]\\s*", with: "", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "\\s*\\[MULTI-SUB\\]\\s*$", with: "", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "\\s+-\\s+\\d{4}\\s*$", with: "", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static func text(_ data: [String: Any], _ keys: String...) -> String {
        for key in keys { if let text = data[key] as? String, !text.isEmpty { return text }; if let n = data[key] as? NSNumber { return n.stringValue } }
        return ""
    }
    static func image(_ raw: String) -> String {
        guard !raw.isEmpty, let url = URL(string: raw, relativeTo: URL(string: "https://premium.dawwirli.watch/"))?.absoluteURL, url.scheme == "https" else { return "" }
        return url.absoluteString
    }
    public static func items(_ value: Any?, series: Bool = false) -> [Media] {
        var seen = Set<String>()
        return (value as? [[String: Any]] ?? []).compactMap { row in
            let raw = text(row, "id", "stream_id", "series_id")
            let isSeries = series || text(row, "kind", "_kind") == "series"
            let ext = text(row, "ext", "container_extension")
            let id = isSeries ? "daw:series:\(raw)" : "daw:movie:\(raw):\(ext.isEmpty ? "mp4" : ext)"
            let title = cleanTitle(text(row, "name", "title"))
            guard (try? DawKey(id)) != nil, !title.isEmpty, seen.insert(id).inserted else { return nil }
            var media = Media(id: id, title: title); media.isSeries = isSeries
            media.poster = image(text(row, "poster", "icon", "cover")); media.backdrop = image(text(row, "backdrop"))
            media.overview = text(row, "plot", "description"); media.year = text(row, "year"); media.rating = text(row, "rating")
            return media
        }
    }
    public static func parseSeasons(_ data: [String: Any], parent: String) -> [Season] {
        let info = data["info"] as? [String: Any] ?? [:]
        let groups = data["episodes"] as? [String: [[String: Any]]] ?? [:]
        return groups.compactMap { number, rows -> Season? in
            guard let season = Int(number) else { return nil }
            let episodes = rows.enumerated().compactMap { index, row -> Media? in
                let raw = text(row, "id"), ext = text(row, "container_extension")
                let id = "daw:episode:\(parent):\(raw):\(ext.isEmpty ? "mp4" : ext)"
                guard (try? DawKey(id)) != nil else { return nil }
                let meta = row["info"] as? [String: Any] ?? [:]
                var media = Media(id: id, title: cleanTitle(text(row, "title")))
                media.isSeries = true; media.season = season; media.episode = Int(text(row, "episode_num")) ?? index + 1
                media.seriesID = "daw:series:\(parent)"; media.episodeTitle = media.title
                media.poster = image(text(meta, "movie_image")); if media.poster.isEmpty { media.poster = image(text(info, "cover")) }
                media.overview = text(meta, "plot"); media.duration = text(meta, "duration")
                media.airDate = text(meta, "releasedate", "release_date", "air_date")
                return media
            }
            return Season(id: season, episodes: episodes.sorted { $0.episode < $1.episode })
        }.sorted { $0.id < $1.id }
    }
}
