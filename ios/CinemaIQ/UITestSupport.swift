#if DEBUG
import Foundation

/// UI tests inspect the real AVPlayer and controls with a local reference clip.
/// Neither this flag nor the fixture is part of a Release app.
enum UITestSupport {
    static let playerFixtureEnabled = ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-player")

    static func playbackURL(or sourceURL: URL) -> URL? {
        guard playerFixtureEnabled else { return sourceURL }
        return Bundle.main.url(forResource: "UIPlayerFixture", withExtension: "mp4")
    }
}
#endif
