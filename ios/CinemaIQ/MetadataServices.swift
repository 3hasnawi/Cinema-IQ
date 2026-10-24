import Foundation
import CinemaCore

/// TMDB client, set up from the key the build puts in Info.plist; nil when the build has none.
enum TMDB {
    static let shared: TMDBClient? = {
        guard let key = Bundle.main.object(forInfoDictionaryKey: "TMDBAPIKey") as? String,
              !key.isEmpty, !key.hasPrefix("$(") else { return nil }
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("tmdb-cache.json")
        return TMDBClient(key: key, cacheURL: cache)
    }()
}

/// IntroDB skip timings (no key needed).
enum IntroDB {
    static let shared = IntroDBClient(cacheURL: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("introdb-cache.json"))
}

/// Skip timings of what plays, by the work's IMDb ID (TMDB's, else OMDb's). For an episode the series is used: the
/// one the page passed, else the series loaded from the source. Optional: a miss returns empty timings.
enum SkipTimings {
    static func load(_ media: Media, series: Media?, omdb: OMDbClient?) async -> (segments: SkipSegments, series: Media?) {
        let isEpisode = media.episode > 0
        var work = media
        var foundSeries = series
        if isEpisode {
            if let series { work = series }
            else if !media.seriesID.isEmpty {
                var probe = Media(id: media.seriesID, title: media.title); probe.isSeries = true
                if let found = try? await SourceClient().detail(probe) { work = found.0; foundSeries = found.0 }
            }
        }
        var imdb = await TMDB.shared?.work(for: work)?.imdbID ?? ""
        if imdb.isEmpty { imdb = await omdb?.info(for: work)?.imdbID ?? "" }
        guard !imdb.isEmpty, !Task.isCancelled else { return (SkipSegments(), foundSeries) }
        let segments = await IntroDB.shared.segments(imdbID: imdb, season: media.season, episode: media.episode, movie: !isEpisode)
        return (segments, foundSeries)
    }
}
