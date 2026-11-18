import Foundation
import XCTest
@testable import CinemaCore

final class PlaybackEnginePreferenceTests: XCTestCase {
    func testLegacyIPTVChoiceDoesNotForceMovieCompatibilityAndRemainsStored() throws {
        let name = "cinema.engine.tests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("compatibility", forKey: "apple.playback.engine.v1")
        XCTAssertEqual(PlaybackEnginePreference.shared.mode(in: defaults), .compatibility)
        XCTAssertEqual(PlaybackEnginePreference.tvMovies.mode(in: defaults), .automatic)
        PlaybackEnginePreference.tvMovies.save(.apple, in: defaults)
        XCTAssertEqual(PlaybackEnginePreference.tvMovies.mode(in: defaults), .apple)
        XCTAssertEqual(PlaybackEnginePreference.shared.mode(in: defaults), .compatibility)
        XCTAssertEqual(defaults.string(forKey: "apple.playback.engine.v1"), "compatibility")
    }

    func testMovieChoiceDoesNotChangeSharedDecoderAndInvalidMovieValueDefaultsToAutomatic() throws {
        let name = "cinema.engine.tests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        PlaybackEnginePreference.tvMovies.save(.compatibility, in: defaults)
        XCTAssertEqual(PlaybackEnginePreference.shared.mode(in: defaults), .automatic)
        PlaybackEnginePreference.shared.save(.apple, in: defaults)
        XCTAssertEqual(PlaybackEnginePreference.tvMovies.mode(in: defaults), .compatibility)
        defaults.set("invalid", forKey: PlaybackEnginePreference.tvMovies.rawValue)
        XCTAssertEqual(PlaybackEnginePreference.tvMovies.mode(in: defaults), .automatic)
        XCTAssertEqual(PlaybackEnginePreference.shared.mode(in: defaults), .apple)
    }
}
