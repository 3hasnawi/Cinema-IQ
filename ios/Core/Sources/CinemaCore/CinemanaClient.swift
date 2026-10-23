import Foundation

public struct CinemanaClient: Sendable {
    public init() {}
    private func get(_ path: String) async throws -> Any {
        guard let url = URL(string: "https://cinemana.shabakaty.cc/api/android/" + path) else { throw CinemaError.invalidID }
        return try await SourceHTTP.json(url, root: "shabakaty.cc")
    }
    public static func catalogPath(series: Bool, query: String, page: Int) throws -> String {
        guard page >= 1 else { throw CinemaError.invalidID }
        let encoded = query.isEmpty ? "-" : Data(query.utf8).base64EncodedString().addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        return "video/V/2/videoKind/\(series ? 2 : 1)/categoryNb/0/langNb/0/sortParam/desc/itemsPerPage/10/video_title_search/\(encoded)/pageNumber/\(page - 1)/enableTranslation/0/level/1"
    }
    public func catalog(series: Bool, query: String, page: Int) async throws -> [Media] {
        let data = try await get(Self.catalogPath(series: series, query: query, page: page))
        let result = Self.items(data, series: series)
        if !Self.rows(data).isEmpty && result.isEmpty { throw CinemaError.invalidResponse }
        return result
    }
    public func home() async throws -> [Shelf] {
        let groups = Self.rows(try await get("videoGroups/lang/ar/level/1"))
        var result: [Shelf] = []
        for group in groups.prefix(8) {
            try Task.checkCancellation()
            let id = DawwirliClient.text(group, "groupsID", "groupID", "nb", "id")
            guard id.range(of: "^[0-9]+$", options: .regularExpression) != nil else { continue }
            var items = Self.items(group["content"])
            if items.isEmpty {
                do { items = Self.items(try await get("videoListPagination/groupID/\(id)/level/1/itemsPerPage/12/page/1")) }
                catch { try Task.checkCancellation(); continue }
            }
            if !items.isEmpty {
                let name = DawwirliClient.text(group, "ar_title", "ar_name", "title", "name")
                result.append(Shelf(id: id, title: name.isEmpty ? "اكتشف المزيد" : name, items: items))
            }
        }
        if result.isEmpty { return [Shelf(id: "latest", title: "أضيف حديثاً", items: try await catalog(series: false, query: "", page: 1))] }
        return result
    }
    public func detail(_ media: Media) async throws -> (Media, [Season]) {
        guard try ContentSource.owner(of: media.id) == .cinemana else { throw CinemaError.invalidID }
        guard var row = Self.rows(try await get("allVideoInfo/id/\(media.id)")).first else { throw CinemaError.invalidResponse }
        if DawwirliClient.text(row, "nb", "videoId", "videoID", "id").isEmpty { row["nb"] = media.id }
        guard var result = Self.items([row], series: media.isSeries).first else { throw CinemaError.invalidResponse }
        if result.poster.isEmpty { result.poster = media.poster }
        if result.backdrop.isEmpty { result.backdrop = media.backdrop }
        if result.overview.isEmpty { result.overview = media.overview }
        if !media.seriesID.isEmpty { result.seriesID = media.seriesID }
        if media.episode > 0 { result.season = media.season; result.episode = media.episode }
        // Episode metadata may identify the episode as a movie; its parent determines the queue.
        let parent = media.seriesID.isEmpty ? media.id : media.seriesID
        let seasons = result.isSeries || media.episode > 0 ? Self.seasons(try await get("videoSeason/id/\(parent)"), parent: parent) : []
        return (result, seasons)
    }
    public func streams(_ id: String) async throws -> [StreamChoice] {
        guard try ContentSource.owner(of: id) == .cinemana else { throw CinemaError.invalidID }
        return Self.parseStreams(try await get("transcoddedFiles/id/\(id)"))
    }
    public func subtitles(_ id: String) async throws -> [SubtitleTrack] {
        guard try ContentSource.owner(of: id) == .cinemana else { throw CinemaError.invalidID }
        return SubtitleCatalog.cinemana(Self.rows(try await get("allVideoInfo/id/\(id)")).first?["translations"])
    }
    public static func rows(_ value: Any?) -> [[String: Any]] {
        if let array = value as? [[String: Any]] { return array }
        guard let object = value as? [String: Any] else { return [] }
        for key in ["data", "result", "videos", "items", "list", "groups", "content"] {
            if object[key] is [String: Any] || object[key] is [[String: Any]] { return rows(object[key]) }
        }
        return [object]
    }
    private static func image(_ raw: String) -> String {
        guard !raw.isEmpty, let url = URL(string: raw, relativeTo: URL(string: "https://cnth2.shabakaty.cc/"))?.absoluteURL,
              url.scheme == "https" else { return "" }
        return url.absoluteString
    }
    public static func items(_ value: Any?, series: Bool = false) -> [Media] {
        rows(value).compactMap { row -> Media? in
            let id = DawwirliClient.text(row, "nb", "videoId", "videoID", "id")
            guard id.range(of: "^[1-9][0-9]*$", options: .regularExpression) != nil else { return nil }
            let name = DawwirliClient.text(row, "ar_title", "en_title", "title", "name")
            var media = Media(id: id, title: name.isEmpty ? "عمل \(id)" : name)
            media.poster = image(DawwirliClient.text(row, "imgMediumThumbObjUrl", "imgThumbObjUrl", "imgObjUrl", "poster"))
            media.backdrop = image(DawwirliClient.text(row, "imgObjUrl", "imgLargeThumbObjUrl", "imgMediumThumbObjUrl"))
            media.overview = DawwirliClient.text(row, "ar_content", "ar_description", "description", "content", "en_content")
            media.year = DawwirliClient.text(row, "year"); media.duration = DawwirliClient.text(row, "duration")
            media.rating = DawwirliClient.text(row, "stars", "rate", "imdbRate", "imdb_rate", "imdbRating", "rating")
            media.isSeries = series || DawwirliClient.text(row, "kind", "videoKind") == "2"
            media.season = Int(DawwirliClient.text(row, "season")) ?? 1
            media.episode = Int(DawwirliClient.text(row, "episodeNummer", "episodeNumber", "episode")) ?? 0
            media.seriesID = DawwirliClient.text(row, "seriesId")
            media.enTitle = DawwirliClient.text(row, "en_title")
            media.quality = DawwirliClient.text(row, "videoQuality", "quality", "resolution", "contentQuality")
            return media
        }.uniqueMedia()
    }
    public static func parseStreams(_ value: Any?) -> [StreamChoice] {
        var seen = Set<String>()
        return rows(value).compactMap { row in
            let url = DawwirliClient.text(row, "videoUrl", "url")
            let quality = DawwirliClient.text(row, "resolution", "quality")
            guard let stream = StreamChoice(url, quality: quality.isEmpty ? "تلقائي" : quality), seen.insert(stream.id).inserted else { return nil }
            return stream
        }
    }
    public static func seasons(_ value: Any?, parent: String) -> [Season] {
        if let object = value as? [String: Any] {
            let groups = object.compactMap { key, value -> Season? in
                guard let number = Int(key) else { return nil }
                let episodes = items(value).map { item -> Media in var item = item; item.season = number; item.seriesID = parent; return item }.sorted { $0.episode < $1.episode }
                return episodes.isEmpty ? nil : Season(id: number, episodes: episodes)
            }.sorted { $0.id < $1.id }
            if !groups.isEmpty { return groups }
            for key in ["data", "result", "seasons"] { if let nested = object[key] { return seasons(nested, parent: parent) } }
        }
        return Dictionary(grouping: items(value), by: \.season).map { number, items in
            Season(id: number, episodes: items.map { item in var item = item; item.seriesID = parent; return item }.sorted { $0.episode < $1.episode })
        }.sorted { $0.id < $1.id }
    }
}
