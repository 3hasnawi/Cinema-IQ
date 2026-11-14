#if DEBUG
import Foundation
import CinemaCore

enum IPTVFixtureSupport {
    static var snapshot: Bool {
        let arguments = ProcessInfo.processInfo.arguments
        return arguments.contains("--cinema-ui-testing-iptv") && arguments.contains("--cinema-ui-testing-iptv-snapshot")
    }
    static func markReady(channelCount: Int) {
        guard snapshot, channelCount > 0,
              let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        // A non-sensitive readiness marker lets simctl wait for the real catalogue instead of a fixed delay.
        try? Data("channels=\(channelCount)".utf8).write(to: directory.appendingPathComponent("iptv-fixture-ready.txt"))
    }
}

typealias IPTVFixtureTransport = IPTVDemoTransport
#endif
