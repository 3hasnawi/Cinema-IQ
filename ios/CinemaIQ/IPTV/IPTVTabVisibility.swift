import SwiftUI

private struct IPTVTabVisibleKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    /// Whether the Live TV root tab is the one on screen. Playback pauses and polling stops when it is not.
    var iptvTabVisible: Bool {
        get { self[IPTVTabVisibleKey.self] }
        set { self[IPTVTabVisibleKey.self] = newValue }
    }
}
