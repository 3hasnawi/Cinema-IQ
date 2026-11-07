import Foundation

/// Only direct, explicitly labelled trailer media is eligible. A YouTube webpage is not an AVPlayer stream.
/// Never use the feature film's playback URL as a preview or scrape a hosting service's private streams.
public enum HeroTrailer {
    public static func url(_ raw: String) -> URL? {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil,
              ["mp4", "m4v", "mov", "m3u8"].contains(url.pathExtension.lowercased()) else { return nil }
        return url
    }

    public static func sourceURL(_ object: [String: Any]) -> String {
        for key in ["trailerURL", "trailerUrl", "trailer_url", "trailer"] {
            if let raw = object[key] as? String, let url = url(raw) { return url.absoluteString }
        }
        return ""
    }

    /// Circular selection in logical reading order; callers map physical arrows using layout direction.
    public static func nextIndex(_ index: Int, count: Int, step: Int) -> Int? {
        guard count > 0 else { return nil }
        return ((index % count + step % count) % count + count) % count
    }
}

extension SourceClient {
    /// Catalog metadata may already contain the trailer. Otherwise fetch that title's metadata, not its streams.
    public func trailer(_ media: Media) async -> URL? {
        if let ready = HeroTrailer.url(media.trailerURL) { return ready }
        guard !Task.isCancelled else { return nil }
        let found: URL?
        switch try? ContentSource.owner(of: media.id) {
        case .cinemana: found = try? await CinemanaClient().trailer(media.id)
        case .dawwirli: found = try? await DawwirliClient().trailer(media.id)
        case .external:
            let detail = try? await detail(media)
            found = detail.flatMap { HeroTrailer.url($0.0.trailerURL) }
        default: found = nil // No verified direct-trailer field exists in the Vodu adapter.
        }
        return Task.isCancelled ? nil : found
    }
}
