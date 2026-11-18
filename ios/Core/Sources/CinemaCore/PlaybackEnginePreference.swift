import Foundation

/// Keep the installed live-TV preference; movie players have independent automatic defaults.
public enum PlaybackEnginePreference: String, Sendable {
    case shared = "apple.playback.engine.v1"
    case tvMovies = "apple.tv.movie.playback.engine.v1"
    case phoneMovies = "apple.phone.movie.playback.engine.v1"

    public func mode(in defaults: UserDefaults) -> IPTVPlaybackMode {
        defaults.string(forKey: rawValue).flatMap(IPTVPlaybackMode.init(rawValue:)) ?? .automatic
    }

    public func save(_ mode: IPTVPlaybackMode, in defaults: UserDefaults) {
        defaults.set(mode.rawValue, forKey: rawValue)
    }
}
