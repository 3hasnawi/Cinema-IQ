import XCTest
@testable import CinemaCore

final class IPTVNavigationTests: XCTestCase {
    private func channel(_ id: String, _ group: String = "News", source: String = "one") -> IPTVChannel {
        IPTVChannel(id: id, sourceID: source, name: id, group: group, url: URL(string: "https://media.example/live.m3u8")!)
    }
    func testCategoryQueueIsSourceScopedAndKeepsProviderOrder() {
        let values = [channel("A"), channel("B", "Sport"), channel("Other", source: "two"), channel("C")]
        XCTAssertEqual(IPTVNavigation.channels(values, sourceID: "one", group: "News").map(\.id), ["A", "C"])
        XCTAssertEqual(IPTVNavigation.channels(values, sourceID: "one", query: "sport").map(\.id), ["B"])
    }
    func testSurfingWrapsBothEndsAndHandlesMissingSingleOrEmptyQueues() {
        let values = [channel("A"), channel("B"), channel("C")]
        XCTAssertEqual(IPTVNavigation.neighbor(of: "A", step: -1, in: values)?.id, "C")
        XCTAssertEqual(IPTVNavigation.neighbor(of: "C", step: 1, in: values)?.id, "A")
        XCTAssertEqual(IPTVNavigation.neighbor(of: "B", step: 1, in: values)?.id, "C")
        XCTAssertEqual(IPTVNavigation.neighbor(of: "deleted", step: 1, in: values)?.id, "A")
        XCTAssertEqual(IPTVNavigation.neighbor(of: "A", step: -1, in: [values[0]])?.id, "A")
        XCTAssertNil(IPTVNavigation.neighbor(of: "A", step: 1, in: []))
    }
    func testCatalogNumbersStayStableAcrossFiltersWithoutCrossSourceOrDuplicateIds() {
        let values = [channel("A"), channel("Foreign", source: "two"), channel("B", "Sport"), channel("A"), channel("C")]
        let numbers = IPTVNavigation.numbers(values, sourceID: "one")
        XCTAssertEqual(numbers, ["A": 1, "B": 2, "C": 3])
        let filtered = IPTVNavigation.channels(values, sourceID: "one", group: "Sport")
        XCTAssertEqual(filtered.compactMap { numbers[$0.id] }, [2])
        let groups = IPTVNavigation.categories(values, sourceID: "one")
        XCTAssertEqual(groups.map(\.name), ["News", "Sport"])
        XCTAssertEqual(groups.map(\.count), [3, 1])
        XCTAssertEqual(values.map(\.id), ["A", "Foreign", "B", "A", "C"])
    }
    func testGuideEntryRestoresPlayingChannelOrFirstResult() {
        let values = [channel("A"), channel("B")]
        XCTAssertEqual(IPTVNavigation.entry(currentID: "B", in: values), "B")
        XCTAssertEqual(IPTVNavigation.entry(currentID: "missing", in: values), "A")
        XCTAssertNil(IPTVNavigation.entry(currentID: "B", in: []))
    }
    func testQualitiesRequireValidManifestVariantsAndDeduplicateHeights() {
        let master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1,mp4a"
        720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
        better720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        https://cdn.example/1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160
        file:///bad
        #EXT-X-STREAM-INF:BANDWIDTH=7000000,RESOLUTION=2560x1440
        http://cdn.example/1440.m3u8
        """
        let url = URL(string: "https://provider.example/master.m3u8?token=fixture")!
        let values = IPTVStreamQualities.parse(Data(master.utf8), url: url, allowHTTP: false)
        XCTAssertEqual(values.map(\.height), [1080, 720]); XCTAssertEqual(values.last?.bandwidth, 2000000)
        XCTAssertEqual(IPTVStreamQualities.parse(Data(master.utf8), url: url, allowHTTP: true).map(\.height), [1440, 1080, 720])
        XCTAssertTrue(IPTVStreamQualities.parse(Data("<html>Denied</html>".utf8), url: url, allowHTTP: false).isEmpty)
        XCTAssertTrue(IPTVStreamQualities.parse(Data("#EXTM3U\n#EXT-X-TARGETDURATION:6\nsegment.ts".utf8), url: url, allowHTTP: false).isEmpty)
    }
    func testHighestAdvertisedQualityIsNotCappedAtFullHDOr4K() {
        let master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        fullhd.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=16000000,RESOLUTION=3840x2160
        uhd.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=40000000,RESOLUTION=7680x4320
        eightk.m3u8
        """
        let values = IPTVStreamQualities.parse(Data(master.utf8), url: URL(string: "https://provider.example/master.m3u8")!, allowHTTP: false)
        XCTAssertEqual(values.map(\.height), [4320, 2160, 1080])
        XCTAssertEqual(values.first?.bandwidth, 40000000)
    }
}
