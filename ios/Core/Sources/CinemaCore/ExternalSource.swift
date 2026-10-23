import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// MARK: - Providers

/// An external video provider: a REST API that speaks the Cinema iQ JSON format (see ios/EXTERNAL_SOURCES.md).
/// The built-in demo provider serves a small catalogue of openly licensed films from inside the app.
public struct ExternalProvider: Codable, Hashable, Identifiable, Sendable {
    /// Short slug used in media ids ("ext:<id>:<item>"): lowercase letters, digits and dashes.
    public let id: String
    public var name: String
    /// HTTPS base address, e.g. https://media.example.com/cinema-iq/v1 (or demo:// for the built-in demo).
    public var baseURL: URL
    /// Optional access token, sent as "Authorization: Bearer"; the app keeps it in the Keychain, not here.
    public var token: String?

    public init(id: String, name: String, baseURL: URL, token: String? = nil) throws {
        guard ExternalProvider.valid(id: id) else { throw CinemaError.invalidID }
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 40 else { throw CinemaError.invalidID }
        guard ExternalProvider.valid(base: baseURL) else { throw CinemaError.unsupportedSource }
        self.id = id; self.name = name; self.baseURL = baseURL; self.token = token?.isEmpty == true ? nil : token
    }
    enum CodingKeys: String, CodingKey { case id, name, baseURL }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(id: c.decode(String.self, forKey: .id), name: c.decode(String.self, forKey: .name),
                      baseURL: c.decode(URL.self, forKey: .baseURL))
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id); try c.encode(name, forKey: .name); try c.encode(baseURL, forKey: .baseURL)
    }

    public static func valid(id: String) -> Bool { id.range(of: "^[a-z0-9][a-z0-9-]{0,23}$", options: .regularExpression) != nil }
    /// Only HTTPS without credentials in the address, or the built-in demo.
    public static func valid(base: URL) -> Bool {
        if base.scheme == "demo" { return true }
        return base.scheme?.lowercased() == "https" && base.host?.isEmpty == false && base.user == nil && base.password == nil
            && base.query == nil && base.fragment == nil
    }
    /// A slug made from a name, for providers added in Settings.
    public static func slug(from name: String, taken: Set<String>) -> String {
        let latin = name.lowercased().unicodeScalars.map { CharacterSet.alphanumerics.contains($0) && $0.isASCII ? Character($0) : "-" }
        var base = String(latin).split(separator: "-").joined(separator: "-")
        if base.isEmpty || base == "demo" { base = "src" }
        base = String(base.prefix(18))
        var candidate = base, n = 2
        while taken.contains(candidate) || candidate == "demo" { candidate = "\(base)-\(n)"; n += 1 }
        return candidate
    }

    /// Openly licensed sample films (Blender Foundation, CC BY) and Apple's HLS test stream.
    public static let demo = try! ExternalProvider(id: "demo", name: "عروض مفتوحة (تجريبي)", baseURL: URL(string: "demo://catalog")!)
}

/// The providers the app knows; set by the app at launch and whenever Settings change them.
public enum ExternalProviders {
    private final class Store: @unchecked Sendable {
        let lock = NSLock()
        var providers: [ExternalProvider] = []
    }
    private static let store = Store()

    public static var all: [ExternalProvider] { store.lock.lock(); defer { store.lock.unlock() }; return store.providers }
    public static func set(_ providers: [ExternalProvider]) {
        var seen = Set<String>()
        let unique = providers.filter { seen.insert($0.id).inserted }
        store.lock.lock(); store.providers = unique; store.lock.unlock()
    }
    public static func provider(_ id: String) -> ExternalProvider? { all.first { $0.id == id } }
    static func require(_ id: String) throws -> ExternalProvider {
        guard let provider = provider(id) else { throw CinemaError.unsupportedSource }
        return provider
    }
}

/// Media id of an external item: "ext:<provider>:<item>".
public struct ExternalKey: Sendable {
    public let provider: String
    public let item: String
    public init(_ id: String) throws {
        let parts = id.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, parts[0] == "ext", ExternalProvider.valid(id: parts[1]),
              ExternalKey.valid(item: parts[2]) else { throw CinemaError.invalidID }
        provider = parts[1]; item = parts[2]
    }
    public static func id(provider: String, item: String) -> String { "ext:\(provider):\(item)" }
    /// Item ids go into URL paths, so only unreserved characters are accepted.
    public static func valid(item: String) -> Bool { item.range(of: "^[A-Za-z0-9._~-]{1,128}$", options: .regularExpression) != nil && item != "." && item != ".." }
}

// MARK: - Transport

/// How a provider's JSON is fetched; HTTP for real providers, an in-app catalogue for the demo, a mock in tests.
public protocol ExternalTransport: Sendable {
    func get(_ path: String, query: [String: String]) async throws -> Data
}

/// GET requests to the provider's HTTPS address, through the shared source session (timeouts, one retry on 5xx).
struct HTTPExternalTransport: ExternalTransport {
    let provider: ExternalProvider
    func get(_ path: String, query: [String: String]) async throws -> Data {
        var url = provider.baseURL
        for part in path.split(separator: "/") { url.appendPathComponent(String(part)) }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let items = query.filter { !$0.value.isEmpty }.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        if !items.isEmpty { components?.queryItems = items }
        guard let final = components?.url, let host = provider.baseURL.host else { throw CinemaError.invalidID }
        var headers = ["Accept": "application/json"]
        if let token = provider.token { headers["Authorization"] = "Bearer " + token }
        let data = try await SourceHTTP.read(final, root: host, headers: headers)
        // Catalogue pages are small; anything larger is not a Cinema iQ response.
        guard data.count <= 4 * 1024 * 1024 else { throw CinemaError.invalidResponse }
        return data
    }
}

/// Rate limiting and duplicate requests: one request at a time per address, and identical requests share a result
/// for a short while, so scrolling and re-renders do not hammer a provider.
actor ExternalRequestCache {
    static let shared = ExternalRequestCache()
    private var cached: [String: (Data, Date)] = [:]
    private var running: [String: Task<Data, Error>] = [:]
    func data(_ key: String, ttl: TimeInterval = 120, load: @escaping @Sendable () async throws -> Data) async throws -> Data {
        if let entry = cached[key], -entry.1.timeIntervalSinceNow < ttl { return entry.0 }
        if let task = running[key] { return try await task.value }
        let task = Task { try await load() }
        running[key] = task
        defer { running[key] = nil }
        let data = try await task.value
        cached[key] = (data, Date())
        if cached.count > 200 { cached = cached.filter { -$0.value.1.timeIntervalSinceNow < ttl } }
        return data
    }
    func clear() { cached = [:] }
}

// MARK: - Client

/// One page of a catalogue.
public struct ExternalPage: Sendable {
    public let items: [Media]
    public let hasMore: Bool
}
public struct ExternalCategory: Hashable, Sendable {
    public let id: String
    public let title: String
}
/// What the player needs for one item.
public struct ExternalPlayback: Sendable {
    public let streams: [StreamChoice]
    public let subtitles: [SubtitleTrack]
}

/// Reads one external provider. The endpoints and JSON are documented in ios/EXTERNAL_SOURCES.md.
public struct ExternalClient: Sendable {
    public let provider: ExternalProvider
    let transport: ExternalTransport

    public init(provider: ExternalProvider, transport: ExternalTransport? = nil) {
        self.provider = provider
        self.transport = transport ?? (provider.baseURL.scheme == "demo" ? DemoTransport() as ExternalTransport : HTTPExternalTransport(provider: provider))
    }

    private func json(_ path: String, _ query: [String: String] = [:], cache: Bool = true) async throws -> [String: Any] {
        let key = provider.id + "|" + path + "|" + query.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "&")
        let transport = self.transport
        let load: @Sendable () async throws -> Data = { try await transport.get(path, query: query) }
        let data = cache ? try await ExternalRequestCache.shared.data(key, load: load) : try await load()
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { throw CinemaError.notJSON }
        return object
    }

    /// Shelves of the provider's home page.
    public func home() async throws -> [Shelf] {
        let object = try await json("home")
        return ExternalParser.shelves(object, provider: provider.id)
    }
    /// Movies or series, a search, or a category; pages start at 1.
    public func catalog(series: Bool, query: String = "", category: String = "", page: Int = 1) async throws -> ExternalPage {
        guard page >= 1, page <= 500 else { throw CinemaError.invalidID }
        let object = try await json("catalog", ["type": series ? "series" : "movie", "q": query.trimmingCharacters(in: .whitespaces),
                                                "category": category, "page": String(page)])
        return ExternalParser.page(object, provider: provider.id)
    }
    public func categories() async throws -> [ExternalCategory] {
        ExternalParser.categories(try await json("categories"))
    }
    /// The item with its seasons and episodes (series) or alone (movie, episode).
    public func detail(_ media: Media) async throws -> (Media, [Season]) {
        let key = try ExternalKey(media.id)
        guard key.provider == provider.id else { throw CinemaError.invalidID }
        // An episode's page is its series' page, so the queue of episodes is known.
        let pageID = media.episode > 0 && !media.seriesID.isEmpty ? (try ExternalKey(media.seriesID)).item : key.item
        let object = try await json("items/" + pageID)
        guard var item = ExternalParser.item(object["item"], provider: provider.id) else { throw CinemaError.invalidResponse }
        if media.episode > 0 {
            // Keep the opened episode as the work shown; the series supplies the seasons.
            item = media
        }
        return (item, ExternalParser.seasons(object["seasons"], provider: provider.id, seriesID: ExternalKey.id(provider: provider.id, item: pageID)))
    }
    /// Stream addresses and subtitle files; never cached, because signed links can expire.
    public func playback(_ media: Media) async throws -> ExternalPlayback {
        let key = try ExternalKey(media.id)
        guard key.provider == provider.id else { throw CinemaError.invalidID }
        let object = try await json("items/\(key.item)/streams", cache: false)
        let playback = ExternalParser.playback(object)
        guard !playback.streams.isEmpty else { throw CinemaError.noStreams }
        return playback
    }
}

// MARK: - JSON

/// Converts the provider JSON into the app's models. Unknown fields are ignored; invalid items are skipped.
public enum ExternalParser {
    static func text(_ object: [String: Any], _ key: String) -> String {
        if let s = object[key] as? String { return s.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let n = object[key] as? Int { return String(n) }
        if let n = object[key] as? Double { return n == n.rounded() ? String(Int(n)) : String(n) }
        return ""
    }
    /// HTTPS images only.
    static func image(_ raw: String) -> String {
        guard let url = URL(string: raw), url.scheme?.lowercased() == "https", url.host != nil else { return "" }
        return raw
    }

    public static func item(_ value: Any?, provider: String) -> Media? {
        guard let object = value as? [String: Any] else { return nil }
        let raw = text(object, "id"), title = text(object, "title")
        guard ExternalKey.valid(item: raw), !title.isEmpty else { return nil }
        var media = Media(id: ExternalKey.id(provider: provider, item: raw), title: String(title.prefix(200)))
        let type = text(object, "type").lowercased()
        media.isSeries = type == "series"
        media.poster = image(text(object, "poster"))
        media.backdrop = image(text(object, "backdrop"))
        media.overview = String(text(object, "overview").prefix(4000))
        media.year = String(text(object, "year").prefix(4))
        media.duration = text(object, "duration")
        media.rating = text(object, "rating")
        media.quality = text(object, "quality")
        media.enTitle = text(object, "enTitle")
        if type == "episode" {
            media.episode = max(1, Int(text(object, "episode")) ?? 1)
            media.season = max(1, Int(text(object, "season")) ?? 1)
            media.episodeTitle = text(object, "episodeTitle")
            let series = text(object, "seriesId")
            if ExternalKey.valid(item: series) { media.seriesID = ExternalKey.id(provider: provider, item: series) }
        }
        return media
    }
    static func items(_ value: Any?, provider: String) -> [Media] {
        var seen = Set<String>()
        return (value as? [Any] ?? []).compactMap { item($0, provider: provider) }.filter { seen.insert($0.id).inserted }
    }
    public static func shelves(_ object: [String: Any], provider: String) -> [Shelf] {
        (object["shelves"] as? [[String: Any]] ?? []).enumerated().compactMap { index, shelf in
            let items = items(shelf["items"], provider: provider)
            guard !items.isEmpty else { return nil }
            let id = text(shelf, "id")
            return Shelf(id: id.isEmpty ? "shelf-\(index)" : id, title: text(shelf, "title").isEmpty ? "المحتوى" : text(shelf, "title"), items: items)
        }
    }
    public static func page(_ object: [String: Any], provider: String) -> ExternalPage {
        ExternalPage(items: items(object["items"], provider: provider), hasMore: object["hasMore"] as? Bool ?? false)
    }
    public static func categories(_ object: [String: Any]) -> [ExternalCategory] {
        (object["categories"] as? [[String: Any]] ?? []).compactMap { row in
            let id = text(row, "id"), title = text(row, "title")
            return ExternalKey.valid(item: id) && !title.isEmpty ? ExternalCategory(id: id, title: title) : nil
        }
    }
    public static func seasons(_ value: Any?, provider: String, seriesID: String) -> [Season] {
        let rows = value as? [[String: Any]] ?? []
        var result: [Season] = []
        for row in rows {
            let number = Int(text(row, "number")) ?? (result.count + 1)
            guard number >= 1, !result.contains(where: { $0.id == number }) else { continue }
            let episodes = (row["episodes"] as? [Any] ?? []).compactMap { raw -> Media? in
                guard var object = raw as? [String: Any] else { return nil }
                object["type"] = "episode"
                if object["season"] == nil { object["season"] = number }
                guard var media = item(object, provider: provider) else { return nil }
                media.seriesID = seriesID
                return media
            }.sorted { $0.episode < $1.episode }
            if !episodes.isEmpty { result.append(Season(id: number, episodes: episodes)) }
        }
        return result.sorted { $0.id < $1.id }
    }
    /// Only HTTPS streams in a format AVPlayer plays: HLS (m3u8) or MP4/MOV.
    public static func playback(_ object: [String: Any]) -> ExternalPlayback {
        let streams = (object["streams"] as? [[String: Any]] ?? []).compactMap { row -> StreamChoice? in
            let raw = text(row, "url")
            guard let url = URL(string: raw), playable(url) else { return nil }
            let quality = text(row, "quality")
            return StreamChoice(raw, quality: quality.isEmpty ? "تلقائي" : String(quality.prefix(20)))
        }
        var seenStreams = Set<String>()
        let subtitles = (object["subtitles"] as? [[String: Any]] ?? []).compactMap { row -> SubtitleTrack? in
            guard let url = URL(string: text(row, "url")), url.scheme?.lowercased() == "https", url.host != nil,
                  ["vtt", "srt"].contains(url.pathExtension.lowercased()) else { return nil }
            let language = text(row, "language").lowercased()
            let title = text(row, "title")
            return SubtitleTrack(url: url, language: language, title: title.isEmpty ? (language.isEmpty ? "ترجمة" : language) : title)
        }
        return ExternalPlayback(streams: streams.filter { seenStreams.insert($0.id).inserted }, subtitles: subtitles)
    }
    static func playable(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", url.host != nil, url.user == nil, url.password == nil else { return false }
        return ["m3u8", "mp4", "m4v", "mov"].contains(url.pathExtension.lowercased())
    }
}

// MARK: - Demo

/// The built-in demo provider: openly licensed films served from this catalogue, streamed from public test hosts.
/// Big Buck Bunny, Sintel (trailer), Tears of Steel and Elephants Dream are Blender Foundation films under Creative
/// Commons Attribution, streamed from Blender's own server and Mux's public test stream; artwork from Wikimedia Commons.
/// The second HLS stream is Apple's public test stream.
struct DemoTransport: ExternalTransport {
    static let commons = "https://upload.wikimedia.org/wikipedia/commons/"
    static let blender = "https://download.blender.org/"
    static let films: [[String: Any]] = [
        ["id": "big-buck-bunny", "title": "Big Buck Bunny", "enTitle": "Big Buck Bunny", "type": "movie", "year": "2008", "duration": "10",
         "overview": "فيلم رسوم متحركة قصير من مؤسسة Blender، مرخّص بالمشاع الإبداعي (CC BY 3.0). يُبث بصيغة HLS بعدة جودات.",
         "poster": commons + "c/c5/Big_buck_bunny_poster_big.jpg", "backdrop": commons + "c/c5/Big_buck_bunny_poster_big.jpg",
         "quality": "HLS", "stream": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"],
        ["id": "sintel", "title": "Sintel", "enTitle": "Sintel", "type": "movie", "year": "2010", "duration": "1",
         "overview": "العرض الدعائي لفيلم Sintel من مؤسسة Blender، مرخّص بالمشاع الإبداعي (CC BY 3.0).",
         "poster": commons + "8/8f/Sintel_poster.jpg", "backdrop": commons + "8/8f/Sintel_poster.jpg", "quality": "720p",
         "stream": blender + "durian/trailer/sintel_trailer-720p.mp4"],
        ["id": "tears-of-steel", "title": "Tears of Steel", "enTitle": "Tears of Steel", "type": "movie", "year": "2012", "duration": "12",
         "overview": "فيلم خيال علمي قصير من مؤسسة Blender، مرخّص بالمشاع الإبداعي (CC BY 3.0).",
         "poster": commons + "7/70/Tos-poster.png", "backdrop": "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg",
         "quality": "720p", "stream": blender + "demo/movies/ToS/tears_of_steel_720p.mov"],
        ["id": "elephants-dream", "title": "Elephants Dream", "enTitle": "Elephants Dream", "type": "movie", "year": "2006", "duration": "11",
         "overview": "أول فيلم مفتوح من مؤسسة Blender، مرخّص بالمشاع الإبداعي (CC BY 2.5).",
         "poster": commons + "0/0c/ElephantsDreamPoster.jpg", "backdrop": "https://orange.blender.org/wp-content/themes/orange/images/media/gallery/s1_proog.jpg",
         "quality": "720p", "stream": blender + "ED/elephantsdream-720-h264-st-aac.mov"],
    ]
    static let hls = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"
    static let series: [String: Any] = ["id": "open-movies", "title": "أفلام Blender المفتوحة", "enTitle": "Blender Open Movies",
        "type": "series", "year": "2006", "overview": "الأفلام المفتوحة من مؤسسة Blender كحلقات، لتجربة صفحة المسلسل وتشغيل الحلقة التالية.",
        "poster": commons + "0/0c/ElephantsDreamPoster.jpg", "backdrop": "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg"]

    private static func strip(_ film: [String: Any]) -> [String: Any] { film.filter { $0.key != "stream" } }
    private static func episodes() -> [[String: Any]] {
        films.enumerated().map { index, film in
            ["id": "open-movies-\(index + 1)", "episode": index + 1, "season": 1, "episodeTitle": film["title"]!,
             "title": "أفلام Blender المفتوحة", "duration": film["duration"]!, "poster": film["poster"]!, "backdrop": film["backdrop"]!,
             "seriesId": "open-movies"]
        }
    }
    private static func encode(_ object: Any) throws -> Data { try JSONSerialization.data(withJSONObject: object) }

    func get(_ path: String, query: [String: String]) async throws -> Data {
        let movies = Self.films.map(Self.strip)
        let test: [String: Any] = ["id": "apple-hls", "title": "بث HLS تجريبي", "enTitle": "Apple HLS test stream", "type": "movie",
                                   "overview": "بث تجريبي من Apple بعدة جودات لاختبار تشغيل HLS واختيار الجودة.", "quality": "HLS"]
        switch path {
        case "home":
            return try Self.encode(["shelves": [["id": "films", "title": "أفلام مفتوحة الترخيص", "items": movies],
                                                ["id": "series", "title": "مسلسل تجريبي", "items": [Self.series]],
                                                ["id": "streams", "title": "بث تجريبي", "items": [test]]]])
        case "catalog":
            let series = query["type"] == "series"
            let q = (query["q"] ?? "").lowercased()
            var items: [[String: Any]] = series ? [Self.series] : movies + [test]
            if !q.isEmpty { items = items.filter { (($0["title"] as? String ?? "") + " " + ($0["enTitle"] as? String ?? "")).lowercased().contains(q) } }
            return try Self.encode(["items": (query["page"] ?? "1") == "1" ? items : [], "hasMore": false])
        case "categories":
            return try Self.encode(["categories": [["id": "animation", "title": "رسوم متحركة"], ["id": "sci-fi", "title": "خيال علمي"]]])
        default: break
        }
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 2, parts[0] == "items" else { throw CinemaError.http(404) }
        let id = parts[1]
        if parts.count == 3, parts[2] == "streams" {
            if id == "apple-hls" { return try Self.encode(["streams": [["url": Self.hls, "quality": "تلقائي"]]]) }
            let index = Int(id.replacingOccurrences(of: "open-movies-", with: "")).map { $0 - 1 }
            let film = id.hasPrefix("open-movies-") ? index.flatMap { Self.films.indices.contains($0) ? Self.films[$0] : nil }
                : Self.films.first { $0["id"] as? String == id }
            guard let stream = film?["stream"] else { throw CinemaError.http(404) }
            return try Self.encode(["streams": [["url": stream, "quality": "720p"]]])
        }
        if id == "open-movies" { return try Self.encode(["item": Self.series, "seasons": [["number": 1, "episodes": Self.episodes()]]]) }
        if id == "apple-hls" { return try Self.encode(["item": test]) }
        guard let film = Self.films.first(where: { $0["id"] as? String == id }) else { throw CinemaError.http(404) }
        return try Self.encode(["item": Self.strip(film)])
    }
}
