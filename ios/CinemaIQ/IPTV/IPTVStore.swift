import Foundation
import CinemaCore

/// iPhone/iPad configuration of the shared Apple-platform IPTV state and local vault.
extension IPTVStore {
    convenience init() {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let testing = arguments.contains("--cinema-ui-testing-iptv")
        let reset = testing && arguments.contains("--cinema-ui-testing-iptv-reset")
        let snapshot = IPTVFixtureSupport.snapshot
        self.init(service: testing ? "com.cinemaplus.rebuilt.ios.iptv.uitests" : "com.cinemaplus.rebuilt.ios.iptv",
                  selectionKey: testing ? "ios.iptv.uitests.selected.v1" : "ios.iptv.selected.v1",
                  client: testing ? IPTVClient(transport: IPTVFixtureTransport()) : IPTVClient(),
                  prepareStorage: { vault in
                      if reset { try vault.write([]) }
                      if snapshot {
                          let source = try IPTVSource(id: "iptv-snapshot-playlist", name: "قنوات تجريبية", kind: .playlist,
                                                     address: URL(string: "https://iptv.example/channels.m3u")!)
                          try vault.write([source])
                      }
                  })
#else
        self.init(service: "com.cinemaplus.rebuilt.ios.iptv", selectionKey: "ios.iptv.selected.v1")
#endif
    }
}
