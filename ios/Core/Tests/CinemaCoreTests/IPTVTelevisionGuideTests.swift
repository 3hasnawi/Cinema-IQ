import XCTest
@testable import CinemaCore

final class IPTVTelevisionGuideTests: XCTestCase {
    private func channel(_ n: Int, _ group: String = "رياضة", _ title: String = "IQ: قناة", source: String = "test") -> IPTVChannel {
        IPTVChannel(id: String(n), sourceID: source, name: title, group: group, url: URL(fileURLWithPath: "/fixture"))
    }
    func testCanonicalNumbersAndUnmodifiedTitles() {
        let channels = (1...11962).map { channel($0) }
        let index = IPTVTelevisionGuide(channels: channels + [channels[0]], sourceID: "test")
        XCTAssertEqual(index.unrestricted.count, 11962)
        XCTAssertEqual(index.rows(section: .all, visit: nil, favorites: [], query: "٥٩٨١").map(\.id), ["5981"])
        XCTAssertEqual(channels[0].televisionTitle, "قناة")
        XCTAssertEqual(channel(1, "Sport", "[Sport] | AE: Dubai").televisionTitle, "Dubai")
        XCTAssertEqual(channel(1, "Sport", "SD: Classic").televisionTitle, "SD: Classic")
        XCTAssertEqual(channel(1, "Sport", "BBC: One").televisionTitle, "BBC: One")
        XCTAssertEqual(channel(1, "Sport", "IQ:").televisionTitle, "IQ:")
    }
    func testVisitDoesNotExposeHiddenOrLockedChannelsToOtherRoutes() {
        var rules = IPTVGroupRules()
        rules.locked.insert("محمي"); rules.hidden.insert("محذوف")
        let index = IPTVTelevisionGuide(channels: [channel(1), channel(2,"محمي"), channel(3,"محذوف"), channel(4,source: "other")], sourceID: "test", rules: rules)
        XCTAssertEqual(index.unrestricted.map(\.id), ["1"])
        XCTAssertEqual(index.rows(section: .group("محمي"), visit: "محمي", favorites: []).map(\.id), ["2"])
        XCTAssertTrue(index.rows(section: .group("محمي"), visit: nil, favorites: []).isEmpty)
        XCTAssertTrue(index.rows(section: .group("محذوف"), visit: "محذوف", favorites: []).isEmpty)
        XCTAssertTrue(index.rows(section: .all, visit: "محمي", favorites: [], query: "٢").isEmpty)
        XCTAssertTrue(index.rows(section: .favorites, visit: "محمي", favorites: ["2","3"]).isEmpty)
        XCTAssertEqual(index.numbers["3"], 3)
    }
}
