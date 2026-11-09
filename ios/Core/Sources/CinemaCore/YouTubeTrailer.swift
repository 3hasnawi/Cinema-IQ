import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Metadata only: YouTube pages are never passed to AVPlayer as direct streams.
public enum YouTubeTrailer {
    public static func videoID(from data: Data, matchingTitle: String? = nil) -> String? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let items = root["items"] as? [[String: Any]] else { return nil }
        let expected = matchingTitle.map(words) ?? []
        for item in items {
            if !expected.isEmpty {
                let name = (item["snippet"] as? [String: Any])?["title"] as? String ?? ""
                let found = Set(words(name))
                guard expected.allSatisfy({ found.contains($0) }) else { continue }
            }
            guard let id = (item["id"] as? [String: Any])?["videoId"] as? String,
                  id.range(of: #"^[A-Za-z0-9_-]{11}$"#, options: .regularExpression) != nil else { continue }
            return id
        }
        return nil
    }

    private static func words(_ value: String) -> [String] {
        value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init).filter { $0.count > 1 }
    }

    public static func appURL(videoID: String) -> URL? {
        guard videoID.range(of: #"^[A-Za-z0-9_-]{11}$"#, options: .regularExpression) != nil else { return nil }
        var components = URLComponents()
        components.scheme = "youtube"
        components.host = "www.youtube.com"
        components.path = "/watch"
        components.queryItems = [URLQueryItem(name: "v", value: videoID)]
        return components.url
    }
}

/// A single bounded lookup. The caller decides whether the external YouTube app can open the result.
public actor YouTubeTrailerClient {
    private let key: String
    private var cache: [String: String] = [:]
    public init(key: String) { self.key = key.trimmingCharacters(in: .whitespacesAndNewlines) }

    public func search(title: String, year: String) async -> String? {
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, !title.isEmpty, !Task.isCancelled else { return nil }
        let query = [title, year, "official trailer"].filter { !$0.isEmpty }.joined(separator: " ")
        if let cached = cache[query] { return cached }
        guard var parts = URLComponents(string: "https://www.googleapis.com/youtube/v3/search") else { return nil }
        parts.queryItems = [URLQueryItem(name: "part", value: "snippet"),
            URLQueryItem(name: "type", value: "video"), URLQueryItem(name: "videoEmbeddable", value: "true"),
            URLQueryItem(name: "maxResults", value: "5"), URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "key", value: key)]
        guard let url = parts.url else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard !Task.isCancelled, (response as? HTTPURLResponse)?.statusCode == 200,
                  let id = YouTubeTrailer.videoID(from: data, matchingTitle: title) else { return nil }
            if cache.count >= 100 { cache.removeAll() }
            cache[query] = id
            return id
        } catch { return nil }
    }
}
