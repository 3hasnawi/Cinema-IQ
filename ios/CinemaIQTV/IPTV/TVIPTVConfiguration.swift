import Foundation
import CinemaCore

/// Platform-specific storage names; all loading, parsing and source management use the shared Core store.
@MainActor enum TVIPTVConfiguration {
    static func makeStore() -> IPTVStore {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let testing = arguments.contains("--cinema-tv-ui-testing-iptv")
        let reset = testing && arguments.contains("--cinema-tv-ui-testing-iptv-reset")
        let seed = testing && arguments.contains("--cinema-tv-ui-testing-iptv-seed")
        let key = testing ? "tv.iptv.uitests.selected.v1" : "tv.iptv.selected.v1"
        if reset { UserDefaults.standard.removeObject(forKey: key) }
        return IPTVStore(service: testing ? "com.cinemaplus.rebuilt.tv.iptv.uitests" : "com.cinemaplus.rebuilt.tv.iptv",
                         selectionKey: key, client: testing ? IPTVClient(transport: IPTVDemoTransport()) : IPTVClient(),
                         prepareStorage: { vault in
                             if reset { try vault.write([]) }
                             if seed {
                                 try vault.write([
                                     IPTVSource(id: "tv-fixture-m3u", name: "M3U Test", kind: .playlist,
                                                address: URL(string: "https://iptv.example/channels.m3u")!),
                                     IPTVSource(id: "tv-fixture-xtream", name: "Xtream Test", kind: .xtream,
                                                address: URL(string: "https://xtream.example")!, username: "demo", password: "demo")
                                 ])
                             }
                         })
#else
        return IPTVStore(service: "com.cinemaplus.rebuilt.tv.iptv", selectionKey: "tv.iptv.selected.v1")
#endif
    }
}
