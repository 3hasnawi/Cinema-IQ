#if canImport(Combine) && canImport(Security)
import XCTest
@testable import CinemaCore

private actor DelayedPlaylistTransport: IPTVTransport {
    private(set) var requests = 0
    let fail: Bool
    init(fail: Bool = false) { self.fail = fail }
    func data(from url: URL) async throws -> Data {
        requests += 1
        try await Task.sleep(for: .milliseconds(200))
        if fail { throw IPTVError.http(503) }
        return Data("#EXTM3U\n#EXTINF:-1,News\nhttps://media.example/live.m3u8\n".utf8)
    }
}

private actor FixtureCatalogCache: IPTVCatalogCaching {
    private(set) var snapshot: IPTVCatalogSnapshot?
    init(_ value: IPTVCatalogSnapshot) { snapshot = value }
    func read(_ source: IPTVSource) async throws -> IPTVCatalogSnapshot? { snapshot }
    func write(_ snapshot: IPTVCatalogSnapshot, source: IPTVSource) async throws { self.snapshot = snapshot }
    func remove(sourceID: String) async { snapshot = nil }
}

final class IPTVStoreTests: XCTestCase {
    @MainActor func testFreshDiskCatalogueOpensWithoutFetchingAndRetainsLastChannel() async throws {
        try await checkDiskCatalogue(stale: false, fail: false)
    }
    @MainActor func testStaleDiskCatalogueAppearsBeforeRefreshCompletes() async throws {
        try await checkDiskCatalogue(stale: true, fail: false)
    }
    @MainActor func testFailedRefreshKeepsChannelsAndPersistsCooldown() async throws {
        try await checkDiskCatalogue(stale: true, fail: true)
    }
    @MainActor private func checkDiskCatalogue(stale: Bool, fail: Bool) async throws {
        let suite = "CinemaIPTVCacheTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite)), vault = IPTVKeychainVault(service: suite)
        defer { try? vault.write([]); defaults.removePersistentDomain(forName: suite) }
        let source = try IPTVSource(id: "fixture", name: "Fixture", kind: .playlist,
            address: XCTUnwrap(URL(string: "https://provider.example/list.m3u")))
        let channel = IPTVChannel(id: "fixture:cached", sourceID: source.id, name: "Cached News", group: "Live",
            tvgID: "news", url: XCTUnwrap(URL(string: "https://media.example/cached.m3u8")))
        let cache = FixtureCatalogCache(IPTVCatalogSnapshot(savedAt: Date().addingTimeInterval(stale ? -1200 : 0), catalog: IPTVCatalog(channels: [channel])))
        let transport = DelayedPlaylistTransport(fail: fail)
        let store = IPTVStore(service: suite, selectionKey: "selected", client: IPTVClient(transport: transport), defaults: defaults,
            catalogCache: cache, prepareStorage: { try $0.write([source]) })
        for _ in 0..<500 { if !store.storageLoading { break }; try await Task.sleep(for: .milliseconds(10)) }
        store.noteWatched(channel)
        let load = Task { await store.reload() }
        for _ in 0..<100 { if !store.catalog.channels.isEmpty { break }; try await Task.sleep(for: .milliseconds(2)) }
        XCTAssertEqual(store.catalog.channels.first?.id, channel.id)
        XCTAssertFalse(store.loading)
        XCTAssertEqual(store.channelCollection.recent.first, channel.id)
        await load.value
        let requests = await transport.requests
        XCTAssertEqual(requests, stale ? 1 : 0)
        XCTAssertEqual(store.catalog.channels.first?.name, stale && !fail ? "News" : "Cached News")
        if fail {
            let saved = await cache.snapshot
            XCTAssertNotNil(saved?.retryAfter)
            XCTAssertNotNil(store.message)
        }
        await store.reload()
        let afterReopen = await transport.requests
        XCTAssertEqual(afterReopen, requests, "Reentry must not redownload a fresh list or ignore retry cooldown")
    }
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
