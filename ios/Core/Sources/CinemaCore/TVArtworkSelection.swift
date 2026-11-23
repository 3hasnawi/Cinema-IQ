import Foundation

/// Artwork in display order, including substitutes when a provider image is missing or fails to load.
public enum FeaturedArtworkFormat {
    case poster, backdrop

    public func candidates(for media: Media, tmdb: TMDBWork?, fallbackPoster: String = "") -> [String] {
        let posters = [tmdb?.poster ?? "", media.poster, fallbackPoster]
        let backdrops = [tmdb?.backdrop ?? "", media.backdrop]
        let ordered = self == .poster ? posters + backdrops : backdrops + posters
        var seen = Set<String>()
        return ordered.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}

/// Matched landscape metadata takes precedence over provider images that may actually be portrait posters.
public enum TVArtworkSelection {
    public static func url(for media: Media, tmdb: TMDBWork?, fallbackPoster: String = "") -> String {
        FeaturedArtworkFormat.backdrop.candidates(for: media, tmdb: tmdb, fallbackPoster: fallbackPoster).first ?? ""
    }
    public static func providerFallback(for media: Media, excluding selected: String) -> String {
        [media.backdrop, media.poster].map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty && $0 != selected } ?? ""
    }
}
