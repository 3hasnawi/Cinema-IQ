import Foundation
import XCTest
@testable import CinemaCore

private actor GuideFixtureTransport: IPTVTransport {
    var requests = 0
    let body: Data
    let failure: IPTVError?
    init(body: Data, failure: IPTVError? = nil) { self.body = body; self.failure = failure }
    func data(from url: URL) async throws -> Data {
        requests += 1
        try await Task.sleep(for: .milliseconds(20))
        if let failure { throw failure }
        return body
    }
}
final class IPTVGuideTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)
    private func data(_ text: String) -> Data { Data(text.utf8) }
    func testXtreamArabicTitlesAndCurrentNextEpochSelection() throws {
        let title = data("الأخبار").base64EncodedString()
        let values = try IPTVGuideParser.xtream(data("""
        {"epg_listings":[{"title":"\(title)","start_timestamp":"1699999000","stop_timestamp":"1700001000"},
        {"title":"Next programme","start_timestamp":"1700001000","stop_timestamp":"1700002000"}]}
        """))
        let result = IPTVGuideParser.nowNext(values, now: now)
        XCTAssertEqual(result.current?.title, "الأخبار")
        XCTAssertEqual(result.next?.title, "Next programme")
    }
    func testMissingAndInvalidSchedulesDoNotInventProgrammes() throws {
        XCTAssertEqual(IPTVGuideParser.nowNext([], now: now), IPTVNowNext())
        XCTAssertTrue(try IPTVGuideParser.xtream(data("{}" )).isEmpty)
        XCTAssertTrue(try IPTVGuideParser.xtream(data("""{"epg_listings":[{"title":"broken","start_timestamp":4,"stop_timestamp":3}]}""")).isEmpty)
    }
    func testXMLTVTimeZonesAndArabicPreference() throws {
        let xml = data("""
        <tv><programme channel="news" start="20231115010000 +0300" stop="20231115020000 +0300"><title>News</title><title lang="ar">نشرة الأخبار</title></programme>
        <programme channel="news" start="20231115020000 +0300" stop="20231115030000 +0300"><title>Next</title></programme></tv>
        """)
        let index = try IPTVGuideParser.xmltv(xml, now: now)
        let result = IPTVGuideParser.nowNext(index["news"] ?? [], now: now)
        XCTAssertEqual(result.current?.title, "نشرة الأخبار")
        XCTAssertEqual(result.next?.title, "Next")
    }
    func testXMLTVRejectsDTDAndExternalEntities() {
        XCTAssertThrowsError(try IPTVGuideParser.xmltv(data("<!DOCTYPE tv [<!ENTITY external SYSTEM 'file:///does-not-exist'>]><tv>&external;</tv>"), now: now))
    }
    func testPlaylistDiscoversOnlyConsentedGuideURLs() throws {
        let source = try IPTVSource(id: "guide", name: "TV", kind: .playlist, address: XCTUnwrap(URL(string: "https://provider.example/list.m3u")))
        let catalog = try IPTVParser.playlist(data("#EXTM3U x-tvg-url=\"/guide.xml\"\n#EXTINF:-1 tvg-id=\"news\",News\nhttps://media.example/live.m3u8\n"), source: source)
        XCTAssertEqual(catalog.epgURL?.absoluteString, "https://provider.example/guide.xml")
        let blocked = try IPTVParser.playlist(data("#EXTM3U x-tvg-url=\"http://provider.example/guide.xml\"\nhttps://media.example/live.m3u8\n"), source: source)
        XCTAssertNil(blocked.epgURL)
    }
    func testConcurrentMetadataRequestsShareWorkAndForeignChannelsAreIgnored() async throws {
        let transport = GuideFixtureTransport(body: data("{\"epg_listings\":[]}"))
        let client = IPTVGuideClient(transport: transport)
        let source = try IPTVSource(id: "one", name: "TV", kind: .xtream, address: XCTUnwrap(URL(string: "https://provider.example")), username: "fixture", password: "fixture")
        let channel = IPTVChannel(id: "one:7", sourceID: "one", name: "News", group: "News", url: try IPTVClient.liveURL(source: source, streamID: "7"))
        async let a = client.load(source: source, channel: channel, epgURL: nil, now: now)
        async let b = client.load(source: source, channel: channel, epgURL: nil, now: now)
        let values = try await [a,b]; XCTAssertEqual(values, [IPTVNowNext(),IPTVNowNext()])
        let count = await transport.requests; XCTAssertEqual(count, 1)
        let other = IPTVChannel(id: "other:7", sourceID: "other", name: "Other", group: "News", url: channel.url)
        let result = try await client.load(source: source, channel: other, epgURL: nil, now: now)
        XCTAssertEqual(result, IPTVNowNext())
        let unchanged = await transport.requests; XCTAssertEqual(unchanged, 1)
    }
    func testRateLimitedMetadataIsOptionalAndBacksOffAcrossChannels() async throws {
        let transport = GuideFixtureTransport(body: Data(), failure: .http(429))
        let client = IPTVGuideClient(transport: transport)
        let source = try IPTVSource(id: "one", name: "TV", kind: .xtream, address: XCTUnwrap(URL(string: "https://provider.example")), username: "fixture", password: "fixture")
        for id in ["7","8"] {
            let channel = IPTVChannel(id: "one:" + id, sourceID: "one", name: "News", group: "News", url: try IPTVClient.liveURL(source: source, streamID: id))
            let value = try await client.load(source: source, channel: channel, epgURL: nil, now: now)
            XCTAssertEqual(value, IPTVNowNext())
        }
        let count = await transport.requests; XCTAssertEqual(count, 1)
    }
}
