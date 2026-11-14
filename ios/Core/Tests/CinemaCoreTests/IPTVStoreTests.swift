#if canImport(Combine) && canImport(Security)
import XCTest
@testable import CinemaCore

private actor DelayedPlaylistTransport: IPTVTransport {
    private(set) var requests = 0
    func data(from url: URL) async throws -> Data {
        requests += 1
        try await Task.sleep(for: .milliseconds(200))
        return Data("#EXTM3U\n#EXTINF:-1,News\nhttps://media.example/live.m3u8\n".utf8)
    }
}

final class IPTVStoreTests: XCTestCase {
    @MainActor func testCancelledViewLoadCanBeJoinedWithoutDroppingCatalogueOrDuplicatingRequest() async throws {
        let suite = "CinemaIPTVStoreTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let vault = IPTVKeychainVault(service: suite)
        defer { try? vault.write([]); defaults.removePersistentDomain(forName: suite) }
        let source = try IPTVSource(id: "fixture", name: "Fixture", kind: .playlist,
                                    address: XCTUnwrap(URL(string: "https://provider.example/list.m3u")))
        let transport = DelayedPlaylistTransport()
        let store = IPTVStore(service: suite, selectionKey: "selected", client: IPTVClient(transport: transport),
                              defaults: defaults, prepareStorage: { try $0.write([source]) })
        for _ in 0..<500 {
            if !store.storageLoading { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(store.storageAvailable)
        XCTAssertEqual(store.selectedID, source.id)
        let first = Task { await store.reload() }
        for _ in 0..<100 {
            if await transport.requests > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        first.cancel()
        await store.reload()
        await first.value
        XCTAssertEqual(store.catalog.channels.map(\.name), ["News"])
        XCTAssertFalse(store.loading)
        let count = await transport.requests
        XCTAssertEqual(count, 1)
        await store.reload()
        let cachedCount = await transport.requests
        XCTAssertEqual(cachedCount, 1)
    }
}
#endif
