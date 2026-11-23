import Foundation

public enum ContinueWatching {
    /// Prefer the next unfinished episode, including across seasons. If the viewer watched out of
    /// order, keep an earlier unfinished episode on the shelf until the whole series is complete.
    public static func remainingEpisode(after current: Media, in episodes: [Media], isWatched: (String) -> Bool) -> Media? {
        guard current.episode > 0, let index = episodes.firstIndex(where: { $0.id == current.id }) else { return nil }
        func eligible(_ media: Media) -> Bool {
            media.episode > 0 && media.id != current.id &&
                (current.seriesID.isEmpty || media.seriesID.isEmpty || current.seriesID == media.seriesID) && !isWatched(media.id)
        }
        return episodes.dropFirst(index + 1).first(where: eligible) ?? episodes.prefix(index).first(where: eligible)
    }

    /// Only explicit shelf entries are candidates. Playback activity can advance independently of
    /// the cloud recent document, whose contents may stay identical while a movie is rewatched.
    public static func ordered(_ candidates: [Media], activity: [String: Date], isWatched: (String) -> Bool) -> [Media] {
        var seen = Set<String>()
        return candidates.enumerated().filter { !isWatched($0.element.id) && seen.insert($0.element.recentKey).inserted }
            .sorted { lhs, rhs in
                let a = activity[lhs.element.recentKey] ?? .distantPast
                let b = activity[rhs.element.recentKey] ?? .distantPast
                return a == b ? lhs.offset < rhs.offset : a > b
            }.map(\.element)
    }
}
