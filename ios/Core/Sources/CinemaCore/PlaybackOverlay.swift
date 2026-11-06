import Foundation

/// The compact player lockup uses the series poster for episodes and never invents unavailable metadata.
public struct PlaybackOverlayDetails: Equatable, Sendable {
    public let title: String
    public let poster: String
    public let year: String
    public let rating: String
    public let episode: String

    public init(media: Media, series: Media? = nil, info: OMDbInfo? = nil) {
        let work = media.episode > 0 ? series ?? media : media
        func text(_ values: String?...) -> String {
            values.compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .first { !$0.isEmpty && $0.uppercased() != "N/A" } ?? ""
        }
        title = text(work.title, media.title, info?.title)
        poster = text(work.poster, info?.largePoster, media.poster)
        year = text(work.year, info?.year, media.year)
        if let raw = info?.imdbRating, let score = Double(raw), score.isFinite, score > 0, score <= 10 {
            rating = "IMDb \(raw)"
        } else if let score = Double(work.rating), score.isFinite, score > 0 {
            rating = "★ \(work.rating)"
        } else {
            rating = ""
        }
        episode = media.episode > 0 ? "الموسم \(media.season) · الحلقة \(media.episode)" : ""
    }

    public var facts: String { [rating, year].filter { !$0.isEmpty }.joined(separator: "  ·  ") }
    public var accessibilitySummary: String {
        [title, rating, year, episode].filter { !$0.isEmpty }.joined(separator: "، ")
    }
}

public enum PlaybackOverlayPolicy {
    /// Native controls own interaction and auto-hide. Pausing keeps the information visible independently.
    public static func isVisible(ready: Bool, paused: Bool, transportVisible: Bool, active: Bool) -> Bool {
        active && ready && (paused || transportVisible)
    }
}
