import Foundation

/// Keep the installed shared preference for existing callers; TV movies have an independent automatic default.
public enum PlaybackEnginePreference: String, Sendable {
    case shared = "apple.playback.engine.v1"
    case tvMovies = "apple.tv.movie.playback.engine.v1"

    public func mode(in defaults: UserDefaults) -> IPTVPlaybackMode {
        defaults.string(forKey: rawValue).flatMap(IPTVPlaybackMode.init(rawValue:)) ?? .automatic
    }

    public func save(_ mode: IPTVPlaybackMode, in defaults: UserDefaults) {
        defaults.set(mode.rawValue, forKey: rawValue)
    }
}
