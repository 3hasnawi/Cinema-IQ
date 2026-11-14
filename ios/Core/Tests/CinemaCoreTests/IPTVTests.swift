import XCTest
@testable import CinemaCore

private actor IPTVMock: IPTVTransport {
    var requested: [URL] = []
    var failure: IPTVError?
    init(failure: IPTVError? = nil) { self.failure = failure }
    func data(from url: URL) async throws -> Data {
        requested.append(url)
        if let failure { throw failure }
        let action = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "action" }?.value
        switch action {
        case "get_live_categories": return Data(#"[{"category_id":"1","category_name":"أخبار"}]"#.utf8)
        case "get_live_streams": return Data(#"[{"stream_id":101,"name":"News","category_id":"1"},{"stream_id":"101","name":"Duplicate"},{"stream_id":"../bad","name":"Bad"}]"#.utf8)
        default: return Data(#"{"user_info":{"auth":1,"status":"Active"}}"#.utf8)
        }
    }
}
final class IPTVTests: XCTestCase {
    private func source(kind: IPTVSource.Kind = .playlist, address: String = "https://provider.example/lists/channels.m3u", allowHTTP: Bool = false) throws -> IPTVSource {
        try IPTVSource(id: "source-one", name: "اختبار", kind: kind, address: XCTUnwrap(URL(string: address)),
                       username: kind == .xtream ? "demo" : "", password: kind == .xtream ? "fixture" : "", allowHTTP: allowHTTP)
    }
    func testPlaylistAttributesRelativeLinksArabicGroupsAndInvalidSchemes() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "channels", withExtension: "m3u"))
        let catalog = try IPTVParser.playlist(Data(contentsOf: url), source: source())
        XCTAssertEqual(catalog.channels.count, 3)
        XCTAssertEqual(catalog.channels[0].name, "قناة الأخبار")
        XCTAssertEqual(catalog.channels[1].name, "Sports, HD")
        XCTAssertEqual(catalog.channels[1].group, "Sports, live")
        XCTAssertEqual(catalog.channels[1].url.absoluteString, "https://provider.example/sports/live.m3u8?token=fixture-token")
        XCTAssertEqual(catalog.channels[0].logo?.host, "images.example")
        XCTAssertEqual(Set(catalog.channels.map(\.id)).count, 3)
        XCTAssertTrue(catalog.channels.allSatisfy { !$0.id.contains("fixture-token") && $0.sourceID == "source-one" })
    }
    func testPlaylistIdentitySurvivesReorderingAndNamespacesSources() throws {
        let a = "#EXTINF:-1 tvg-id=one group-title=News,One\nhttps://media.example/one.m3u8\n"
        let b = "#EXTINF:-1 tvg-id=two group-title=News,Two\nhttps://media.example/two.m3u8\n"
        let first = try IPTVParser.playlist(Data(("#EXTM3U\n" + a + b).utf8), source: source())
        let second = try IPTVParser.playlist(Data(("#EXTM3U\n" + b + a).utf8), source: source())
        XCTAssertEqual(first.channels[0].id, second.channels[1].id)
        let other = try IPTVSource(id: "source-two", name: "Other", kind: .playlist, address: URL(string: "https://provider.example/a")!)
        XCTAssertNotEqual(try IPTVParser.playlist(Data(("#EXTM3U\n" + a).utf8), source: other).channels[0].id, first.channels[0].id)
    }
    func testHLSManifestIsOneChannelInsteadOfSegments() throws {
        let data = Data("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n".utf8)
        let result = try IPTVParser.playlist(data, source: source())
        XCTAssertEqual(result.channels.count, 1)
        XCTAssertEqual(result.channels[0].url, try source().address)
    }
    func testRejectInvalidPlaylistsAndUserInfoAndRequireHTTPConsent() throws {
        XCTAssertThrowsError(try IPTVParser.playlist(Data("<html>Denied</html>".utf8), source: source()))
        XCTAssertThrowsError(try IPTVParser.playlist(Data("#EXTM3U\nfile:///bad".utf8), source: source()))
        for raw in ["file:///tmp/a", "https://user:fixture@host.example/a", "ftp://host.example/a", "https://host.example/a#fragment"] {
            XCTAssertThrowsError(try source(address: raw))
        }
        XCTAssertThrowsError(try source(address: "http://provider.example/a")) { XCTAssertEqual($0 as? IPTVError, .insecureConnection) }
        XCTAssertNoThrow(try source(address: "http://provider.example/a", allowHTTP: true))
    }
    func testXtreamAuthenticationActiveExpiredAndWrongAccount() throws {
        XCTAssertNoThrow(try IPTVParser.xtreamAuthentication(Data(#"{"user_info":{"auth":"1","status":"Active"}}"#.utf8)))
        XCTAssertThrowsError(try IPTVParser.xtreamAuthentication(Data(#"{"user_info":{"auth":0}}"#.utf8))) { XCTAssertEqual($0 as? IPTVError, .invalidCredentials) }
        XCTAssertThrowsError(try IPTVParser.xtreamAuthentication(Data(#"{"user_info":{"auth":1,"status":"Expired"}}"#.utf8))) { XCTAssertEqual($0 as? IPTVError, .inactiveSubscription) }
        XCTAssertThrowsError(try IPTVParser.xtreamAuthentication(Data(#"{"user_info":{"auth":1,"status":"Active","exp_date":"1"}}"#.utf8)))
    }
    func testXtreamCredentialsAreEncodedInQueriesAndPathSegments() throws {
        let value = try IPTVSource(name: "Test", kind: .xtream, address: URL(string: "https://provider.example/base")!, username: "a/b?", password: "p&/?#")
        let api = try IPTVClient.apiURL(source: value, action: "get_live_streams")
        let query = URLComponents(url: api, resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(query?.first { $0.name == "password" }?.value, "p&/?#")
        XCTAssertEqual(URLComponents(url: try IPTVClient.liveURL(source: value, streamID: "42"), resolvingAgainstBaseURL: false)?.percentEncodedPath,
                       "/base/live/a%2Fb%3F/p%26%2F%3F%23/42.m3u8")
        XCTAssertFalse(value.description.contains("p&"))
        XCTAssertThrowsError(try IPTVClient.liveURL(source: value, streamID: "../42"))
        let plus = try IPTVSource(name: "Plus", kind: .xtream, address: URL(string: "https://provider.example")!,
                                  username: "user+name", password: "p+word")
        let encoded = try XCTUnwrap(URLComponents(url: IPTVClient.apiURL(source: plus), resolvingAgainstBaseURL: false)?.percentEncodedQuery)
        XCTAssertEqual(encoded, "username=user%2Bname&password=p%2Bword")
    }
    func testXtreamClientUsesAuthCategoriesAndLiveChannels() async throws {
        let mock = IPTVMock()
        let value = try source(kind: .xtream, address: "https://provider.example")
        let catalog = try await IPTVClient(transport: mock).load(value)
        XCTAssertEqual(catalog.channels.count, 1)
        XCTAssertEqual(catalog.channels[0].group, "أخبار")
        XCTAssertEqual(catalog.channels[0].url.path, "/live/demo/fixture/101.m3u8")
        let requested = await mock.requested
        XCTAssertEqual(requested.count, 3)
    }
    func testNetworkErrorsRemainTypedWithoutRevealingURL() async throws {
        let mock = IPTVMock(failure: .http(429))
        do { _ = try await IPTVClient(transport: mock).load(source(kind: .xtream, address: "https://provider.example")); XCTFail("Expected failure") }
        catch { XCTAssertEqual(error as? IPTVError, .http(429)); XCTAssertFalse(error.localizedDescription.contains("fixture")) }
    }
    func testCatalogueRedirectsCannotLeakCredentialsAcrossOrigins() throws {
        let origin = URL(string: "https://provider.example/player_api.php?password=fixture")!
        XCTAssertTrue(IPTVURLPolicy.sameOrigin(origin, URL(string: "https://provider.example/another")!))
        XCTAssertFalse(IPTVURLPolicy.sameOrigin(origin, URL(string: "https://other.example/another")!))
        XCTAssertFalse(IPTVURLPolicy.sameOrigin(origin, URL(string: "http://provider.example/another")!))
    }
}
