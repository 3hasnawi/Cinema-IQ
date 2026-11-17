import Foundation

/// Matched landscape metadata takes precedence over provider images that may actually be portrait posters.
public enum TVArtworkSelection {
    public static func url(for media: Media, tmdb: TMDBWork?, fallbackPoster: String = "") -> String {
        [tmdb?.backdrop ?? "", media.backdrop, tmdb?.poster ?? "", media.poster, fallbackPoster]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty } ?? ""
    }
}
