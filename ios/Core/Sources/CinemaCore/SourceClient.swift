import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum ContentSource: String, CaseIterable, Identifiable, Sendable {
    case cinemana, vodu, dawwirli
    public var id: String { rawValue }
    public var title: String {
        switch self { case .cinemana: return "سينمانا"; case .vodu: return "فودو"; case .dawwirli: return "دورلي" }
    }
    public static func owner(of id: String) throws -> ContentSource {
        if id.hasPrefix("daw:") { _ = try DawKey(id); return .dawwirli }
        if id.hasPrefix("vodu:") { _ = try VoduKey(id); return .vodu }
        if id.range(of: "^[1-9][0-9]*$", options: .regularExpression) != nil { return .cinemana }
        throw CinemaError.invalidID
    }
}
public struct StreamChoice: Identifiable, Hashable, Sendable {
    public let url: URL
    public let quality: String
    public var id: String { url.absoluteString }
    public init?(_ raw: String, quality: String) {
        guard let url = URL(string: raw), url.scheme?.lowercased() == "https", url.host != nil,
              url.user == nil, url.password == nil else { return nil }
        self.url = url; self.quality = quality
    }
}
public struct SourceClient: Sendable {
    public init() {}
    public func home(_ source: ContentSource) async throws -> [Shelf] {
        switch source {
        case .dawwirli: return try await DawwirliClient().home()
        case .cinemana: return try await CinemanaClient().home()
        case .vodu:
            async let movies = VoduClient().catalog(series: false, query: "", page: 1)
            async let series = VoduClient().catalog(series: true, query: "", page: 1)
            return try await [Shelf(id: "movies", title: "أحدث الأفلام", items: movies), Shelf(id: "series", title: "أحدث المسلسلات", items: series)]
        }
    }
    public func catalog(_ source: ContentSource, series: Bool, query: String, page: Int) async throws -> [Media] {
        guard page >= 1 else { throw CinemaError.invalidID }
        switch source {
        case .cinemana: return try await CinemanaClient().catalog(series: series, query: query, page: page)
        case .vodu: return try await VoduClient().catalog(series: series, query: query, page: page)
        case .dawwirli:
            guard page == 1 else { return [] }
            if !query.isEmpty { return try await DawwirliClient().search(query).filter { $0.isSeries == series } }
            return try await DawwirliClient().home().flatMap(\.items).filter { $0.isSeries == series }.uniqueMedia()
        }
    }
    public func detail(_ media: Media) async throws -> (Media, [Season]) {
        switch try ContentSource.owner(of: media.id) {
        case .dawwirli: return try await DawwirliClient().detail(media)
        case .cinemana: return try await CinemanaClient().detail(media)
        case .vodu: return try await VoduClient().detail(media)
        }
    }
    public func streams(_ media: Media) async throws -> [StreamChoice] {
        let streams: [StreamChoice]
        switch try ContentSource.owner(of: media.id) {
        case .dawwirli:
            let url = try DawKey(media.id).streamURL()
            streams = [StreamChoice(url.absoluteString, quality: "تلقائي")].compactMap { $0 }
        case .cinemana: streams = try await CinemanaClient().streams(media.id)
        case .vodu: streams = try await VoduClient().streams(media.id)
        }
        guard !streams.isEmpty else { throw CinemaError.noStreams }
        return streams
    }
}
extension Array where Element == Media {
    func uniqueMedia() -> [Media] { var seen = Set<String>(); return filter { seen.insert($0.id).inserted } }
}
/// One session for every source. Android's OkHttp identifies itself as `okhttp`; some source front ends reject
/// unknown clients, so the iPhone app sends the same agent instead of CFNetwork's default.
enum SourceNetwork {
    static let userAgent = "okhttp/4.12.0"
    static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 45
        config.httpAdditionalHeaders = ["User-Agent": userAgent, "Accept-Language": "ar,en;q=0.8"]
        return URLSession(configuration: config)
    }()
    /// A response may come from the source's own subdomains (redirects inside its network are fine); anything else is not trusted.
    static func belongs(_ host: String?, to root: String) -> Bool {
        guard let host = host?.lowercased() else { return false }
        let root = root.lowercased()
        return host == root || host.hasSuffix("." + root)
    }
    static func retryable(_ error: Error) -> Bool {
        if case CinemaError.http(let code) = error { return code >= 500 }
        if case CinemaError.network(let code) = error { return [-1001, -1005, -1017].contains(code) }
        return false
    }
}
struct SourceHTTP {
    /// Idempotent read with one retry for a brief hiccup or a 5xx answer, as on Android.
    static func read(_ url: URL, root: String) async throws -> Data {
        do { return try await once(url, root: root) }
        catch where SourceNetwork.retryable(error) {
            try await Task.sleep(nanoseconds: 700_000_000)
            return try await once(url, root: root)
        }
    }
    private static func once(_ url: URL, root: String) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue(SourceNetwork.userAgent, forHTTPHeaderField: "User-Agent")
        let data: Data, response: URLResponse
        do { (data, response) = try await SourceNetwork.session.data(for: request) }
        catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            throw CinemaError.network(error.code.rawValue)
        }
        guard let http = response as? HTTPURLResponse else { throw CinemaError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw CinemaError.http(http.statusCode) }
        guard http.url?.scheme == "https", SourceNetwork.belongs(http.url?.host, to: root) else { throw CinemaError.redirected }
        return data
    }
    static func json(_ url: URL, root: String) async throws -> Any {
        let data = try await read(url, root: root)
        guard let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { throw CinemaError.notJSON }
        return value
    }
}
/// Result of a quick reachability test of one source, shown in Settings.
public struct SourceProbe: Sendable {
    public let source: ContentSource
    public let items: Int
    public let error: String?
}
extension SourceClient {
    public func probe(_ source: ContentSource) async -> SourceProbe {
        do {
            let shelves = try await home(source)
            return SourceProbe(source: source, items: shelves.reduce(0) { $0 + $1.items.count }, error: nil)
        } catch { return SourceProbe(source: source, items: 0, error: error.localizedDescription) }
    }
}
