import Foundation
import CinemaCore

/// Platform-specific storage names; all loading, parsing and source management use the shared Core store.
@MainActor enum TVIPTVConfiguration {
    private static let catalogCache = makeCatalogCache(testing: false)
    private static let testCatalogCache = makeCatalogCache(testing: true)
#if DEBUG
    private static var preparedTestStore = false
#endif
    private static func makeCatalogCache(testing: Bool) -> IPTVDiskCatalogCache {
        let name = testing ? "tv-iptv-catalog-uitests-v1" : "tv-iptv-catalog-v1"
        let directory = (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent(name, isDirectory: true)
        return IPTVDiskCatalogCache(directory: directory, service: "com.cinemaplus.rebuilt.tv." + name)
    }
    static func makeStore() -> IPTVStore {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let testing = arguments.contains("--cinema-tv-ui-testing-iptv")
        // TabView can recreate its state object. Fixtures reset once per app launch, never on tab reentry.
        let firstTestingStore = testing && !preparedTestStore
        if testing { preparedTestStore = true }
        let reset = firstTestingStore && arguments.contains("--cinema-tv-ui-testing-iptv-reset")
        let seed = firstTestingStore && arguments.contains("--cinema-tv-ui-testing-iptv-seed")
        let key = testing ? "tv.iptv.uitests.selected.v1" : "tv.iptv.selected.v1"
        if reset { UserDefaults.standard.removeObject(forKey: key) }
        return IPTVStore(service: testing ? "com.cinemaplus.rebuilt.tv.iptv.uitests" : "com.cinemaplus.rebuilt.tv.iptv",
                         selectionKey: key, client: testing ? IPTVClient(transport: IPTVDemoTransport()) : IPTVClient(),
                         catalogCache: testing ? testCatalogCache : catalogCache,
                         resetCollection: reset, prepareStorage: { vault in
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
        return IPTVStore(service: "com.cinemaplus.rebuilt.tv.iptv", selectionKey: "tv.iptv.selected.v1", catalogCache: catalogCache)
#endif
    }
}
