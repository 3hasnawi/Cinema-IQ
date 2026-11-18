import Foundation
import CinemaCore

@MainActor private enum IOSIPTVConfiguration {
    static let catalogCache = makeCache(testing: false)
    static let testCatalogCache = makeCache(testing: true)
#if DEBUG
    static var preparedTestStore = false
#endif
    private static func makeCache(testing: Bool) -> IPTVDiskCatalogCache {
        let name = testing ? "ios-iptv-catalog-uitests-v1" : "ios-iptv-catalog-v1"
        let directory = (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent(name, isDirectory: true)
        return IPTVDiskCatalogCache(directory: directory, service: "com.cinemaplus.rebuilt.ios." + name)
    }
}

/// iPhone/iPad configuration of the shared Apple-platform IPTV state and local vault.
extension IPTVStore {
    convenience init() {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let testing = arguments.contains("--cinema-ui-testing-iptv")
        let firstTestingStore = testing && !IOSIPTVConfiguration.preparedTestStore
        if testing { IOSIPTVConfiguration.preparedTestStore = true }
        let reset = firstTestingStore && arguments.contains("--cinema-ui-testing-iptv-reset")
        let snapshot = firstTestingStore && IPTVFixtureSupport.snapshot
        let selectionKey = testing ? "ios.iptv.uitests.selected.v1" : "ios.iptv.selected.v1"
        if reset { UserDefaults.standard.removeObject(forKey: selectionKey) }
        self.init(service: testing ? "com.cinemaplus.rebuilt.ios.iptv.uitests" : "com.cinemaplus.rebuilt.ios.iptv",
                  selectionKey: selectionKey,
                  client: testing ? IPTVClient(transport: IPTVFixtureTransport()) : IPTVClient(),
                  catalogCache: testing ? IOSIPTVConfiguration.testCatalogCache : IOSIPTVConfiguration.catalogCache,
                  resetCollection: reset, prepareStorage: { vault in
                      if reset { try vault.write([]) }
                      if snapshot {
                          let source = try IPTVSource(id: "iptv-snapshot-playlist", name: "قنوات تجريبية", kind: .playlist,
                                                     address: URL(string: "https://iptv.example/channels.m3u")!)
                          try vault.write([source])
                      }
                  })
#else
        self.init(service: "com.cinemaplus.rebuilt.ios.iptv", selectionKey: "ios.iptv.selected.v1", catalogCache: IOSIPTVConfiguration.catalogCache)
#endif
    }
}
