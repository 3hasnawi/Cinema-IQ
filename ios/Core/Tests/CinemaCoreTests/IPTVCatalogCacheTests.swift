import Foundation
import XCTest
@testable import CinemaCore
#if canImport(CryptoKit) && canImport(Security)
import CryptoKit
#endif

final class IPTVCatalogCacheTests: XCTestCase {
    private func source(_ id: String = "fixture") throws -> IPTVSource {
        try IPTVSource(id: id, name: "قنوات", kind: .playlist, address: XCTUnwrap(URL(string: "https://provider.example/list.m3u")))
    }
    private func snapshot(_ id: String = "fixture") -> IPTVCatalogSnapshot {
        IPTVCatalogSnapshot(savedAt: Date(), catalog: IPTVCatalog(channels: [IPTVChannel(id: id + ":one", sourceID: id,
            name: "الأخبار", group: "مباشر", tvgID: "news", url: URL(string: "https://media.example/live/fixture-user/fixture-password/1.m3u8")!)],
            epgURL: URL(string: "https://provider.example/guide.xml")))
    }
    func testSnapshotFreshnessAndClockRollback() {
        let time = Date(timeIntervalSince1970: 1000)
        let saved = IPTVCatalogSnapshot(savedAt: time, catalog: snapshot().catalog)
        XCTAssertTrue(saved.fresh(at: time.addingTimeInterval(899)))
        XCTAssertFalse(saved.fresh(at: time.addingTimeInterval(901)))
        XCTAssertFalse(saved.fresh(at: time.addingTimeInterval(-1)))
        let backedOff = IPTVCatalogSnapshot(savedAt: time, catalog: saved.catalog, retryAfter: time.addingTimeInterval(1200))
        XCTAssertTrue(backedOff.fresh(at: time.addingTimeInterval(1000)))
        XCTAssertFalse(backedOff.fresh(at: time.addingTimeInterval(1201)))
    }
    func testCatalogueRoundTripPreservesGuideAndChannelIdentity() throws {
        let saved = snapshot()
        let restored = try JSONDecoder().decode(IPTVCatalogSnapshot.self, from: JSONEncoder().encode(saved))
        XCTAssertEqual(restored.catalog, saved.catalog)
    }
#if canImport(CryptoKit) && canImport(Security)
    func testEncryptedDiskRestoreAcrossInstancesWithoutPlaintextSubscriptionURLs() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let key = SymmetricKey(size: .bits256), source = try source(), saved = snapshot()
        let first = IPTVDiskCatalogCache(directory: folder, key: key)
        try await first.write(saved, source: source)
        let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        XCTAssertEqual(files.count, 1)
        let encoded = try Data(contentsOf: XCTUnwrap(files.first))
        XCTAssertNil(encoded.range(of: Data("fixture-password".utf8)))
        let second = IPTVDiskCatalogCache(directory: folder, key: key)
        let value = try await second.read(source)
        XCTAssertEqual(value?.catalog, saved.catalog)
        var changed = source; changed.allowHTTP = true
        let invalidated = try await second.read(changed); XCTAssertNil(invalidated)
    }
    func testDiskCacheBoundsAndWrongKeyAreOptionalMisses() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let first = IPTVDiskCatalogCache(directory: folder, key: SymmetricKey(size: .bits256))
        for id in ["one","two","three","four"] { try await first.write(snapshot(id), source: source(id)) }
        let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        XCTAssertLessThanOrEqual(files.count, 3)
        let second = IPTVDiskCatalogCache(directory: folder, key: SymmetricKey(size: .bits256))
        let wrongKey = try await second.read(source("four")); XCTAssertNil(wrongKey)
        await first.remove(sourceID: "one")
    }
#endif
}
