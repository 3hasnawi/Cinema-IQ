import XCTest
@testable import CinemaCore

final class IPTVChannelCollectionTests: XCTestCase {
    private func channel(_ id: String, source: String = "one", group: String = "News") -> IPTVChannel {
        IPTVChannel(id: id, sourceID: source, name: id, group: group, url: URL(string: "https://media.example/live.m3u8?token=private")!)
    }
    func testRecentOrderDeduplicatesAndFiltersWithinSourceWithoutDeletingMissingIds() {
        var library = IPTVChannelCollection()
        ["A", "B", "Foreign", "A", "removed"].forEach { library.watched($0) }
        let catalog = [channel("A"), channel("B"), channel("Foreign", source: "two")]
        XCTAssertEqual(library.recent, ["removed", "A", "Foreign", "B"])
        XCTAssertEqual(library.channels(catalog, sourceID: "one", section: .recent).map(\.id), ["A", "B"])
        XCTAssertEqual(library.channels(catalog, sourceID: "two", section: .recent).map(\.id), ["Foreign"])
        XCTAssertEqual(library.recent.first, "removed")
    }
    func testFavoritesUseCatalogOrderAndSupportSearchAndToggle() {
        var library = IPTVChannelCollection(); library.toggle("B"); library.toggle("A")
        let catalog = [channel("A"), channel("B", group: "Sport"), channel("C")]
        XCTAssertEqual(library.channels(catalog, sourceID: "one", section: .favorites).map(\.id), ["A", "B"])
        XCTAssertEqual(library.channels(catalog, sourceID: "one", section: .favorites, query: "sport").map(\.id), ["B"])
        library.toggle("A")
        XCTAssertEqual(library.channels(catalog, sourceID: "one", section: .favorites).map(\.id), ["B"])
        XCTAssertEqual(library.channels(catalog, sourceID: "one", section: .group("News")).map(\.id), ["A", "C"])
    }
    func testPersistenceContainsIdsOnlyAndBoundsRecentAndFavorites() throws {
        var library = IPTVChannelCollection()
        for i in 0..<2100 { library.watched(String(i)); library.toggle(String(i)) }
        library.watched(""); library.toggle(String(repeating: "X", count: 513))
        XCTAssertEqual(library.recent.count, 100); XCTAssertEqual(library.recent.first, "2099")
        XCTAssertEqual(library.favorites.count, 2000)
        let data = try JSONEncoder().encode(library)
        XCTAssertEqual(try JSONDecoder().decode(IPTVChannelCollection.self, from: data), library)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("https://"))
        let malformed = Data("{\"recent\":[\"A\",\"A\",\"\",\"B\"],\"favorites\":[\"A\",\"A\",\"\"]}".utf8)
        let restored = try JSONDecoder().decode(IPTVChannelCollection.self, from: malformed)
        XCTAssertEqual(restored.recent, ["A", "B"]); XCTAssertEqual(restored.favorites, ["A"])
    }
}
