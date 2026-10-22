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
struct SourceHTTP {
    static func read(_ url: URL, expectedHost: String) async throws -> Data {
        var request = URLRequest(url: url); request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse,
              response.url?.scheme == "https", response.url?.host == expectedHost else { throw CinemaError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else { throw CinemaError.http(response.statusCode) }
        return data
    }
    static func json(_ url: URL, expectedHost: String) async throws -> Any {
        try JSONSerialization.jsonObject(with: await read(url, expectedHost: expectedHost))
    }
}
