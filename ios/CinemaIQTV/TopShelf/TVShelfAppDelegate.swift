import SwiftUI
import UIKit
import CinemaCore

/// tvOS may deliver a Top Shelf URL through the application delegate or its SwiftUI scene.
/// Keep one validated pending request until the active profile and window are ready.
@MainActor final class TVShelfAppDelegate: NSObject, UIApplicationDelegate, ObservableObject {
    @Published private(set) var pendingURL: URL?
#if DEBUG
    @Published private(set) var audit = "awaiting-system-url"
#endif
    private var lastURL: URL?
    private var lastAt = Date.distantPast

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        if let url = options?[.url] as? URL { _ = receive(url) }
        return true
    }
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        receive(url)
    }
    @discardableResult func receive(_ url: URL) -> Bool {
        guard let route = TopShelfRoute(url: url) else { return false }
        // A single OS event can reach both supported entry points. Avoid restarting playback twice.
        if lastURL == url, Date().timeIntervalSince(lastAt) < 1 { return true }
        lastURL = url; lastAt = Date()
#if DEBUG
        audit = "received-" + route.action.rawValue
#endif
        pendingURL = url
        return true
    }
    func consume() -> URL? {
        let url = pendingURL; pendingURL = nil
        return url
    }
}
