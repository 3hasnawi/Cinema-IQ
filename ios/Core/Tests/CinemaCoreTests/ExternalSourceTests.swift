import XCTest
@testable import CinemaCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Serves fixture files for paths, and records what was asked, instead of a real provider.
private final class MockTransport: ExternalTransport, @unchecked Sendable {
    var routes: [String: String] = [:]
    var failure: Error?
    private(set) var requests: [(String, [String: String])] = []
    func get(_ path: String, query: [String: String]) async throws -> Data {
        requests.append((path, query))
        if let failure { throw failure }
        guard let name = routes[path], let url = Bundle.module.url(forResource: name, withExtension: "json") else { throw CinemaError.http(404) }
        return try Data(contentsOf: url)
    }
}

final class ExternalSourceTests: XCTestCase {
    private let provider = try! ExternalProvider(id: "test-src", name: "اختبار", baseURL: URL(string: "https://api.example.com/v1")!)
    override func setUp() { ExternalProviders.set([ExternalProvider.demo, provider]) }
    override func tearDown() { ExternalProviders.set([]) }

    private func client(_ routes: [String: String]) -> (ExternalClient, MockTransport) {
        let transport = MockTransport(); transport.routes = routes
        return (ExternalClient(provider: provider, transport: transport), transport)
    }

    // 1. Data to Media, 2. library pages
    func testHomeMapsItemsAndSkipsInvalidOnes() async throws {
        let (client, _) = client(["home": "ext-home"])
        let shelves = try await client.home()
        XCTAssertEqual(shelves.count, 1, "empty shelves are dropped")
        let items = shelves[0].items
        XCTAssertEqual(items.map(\.id), ["ext:test-src:m1", "ext:test-src:s1"], "duplicates and unsafe ids are skipped")
        XCTAssertEqual(items[0].year, "2024"); XCTAssertEqual(items[0].rating, "7.5")
        XCTAssertEqual(items[0].poster, "https://img.example.com/m1.jpg")
        XCTAssertTrue(items[1].isSeries); XCTAssertEqual(items[1].poster, "", "plain HTTP artwork is refused")
        XCTAssertTrue(items[0].isExternal)
    }

    // 3. search, 4. pagination
    func testCatalogSendsSearchAndPageAndReadsHasMore() async throws {
        let (client, transport) = client(["catalog": "ext-catalog"])
        let page = try await client.catalog(series: false, query: " robot ", page: 2)
        XCTAssertEqual(page.items.count, 2); XCTAssertTrue(page.hasMore)
        let (path, query) = try XCTUnwrap(transport.requests.last)
        XCTAssertEqual(path, "catalog")
        XCTAssertEqual(query["type"], "movie"); XCTAssertEqual(query["q"], "robot"); XCTAssertEqual(query["page"], "2")
        do { _ = try await client.catalog(series: true, page: 0); XCTFail() } catch {}
    }

    // 5. details with seasons and episodes
    func testDetailOrdersSeasonsAndEpisodes() async throws {
        let (client, transport) = client(["items/s1": "ext-series"])
        var series = Media(id: "ext:test-src:s1", title: "مسلسل أول"); series.isSeries = true
        let (item, seasons) = try await client.detail(series)
        XCTAssertEqual(item.overview, "قصة")
        XCTAssertEqual(seasons.map(\.id), [1, 2], "sorted, duplicate season numbers skipped")
        XCTAssertEqual(seasons[0].episodes.map(\.episode), [1, 2])
        XCTAssertEqual(seasons[0].episodes[0].seriesID, "ext:test-src:s1")
        XCTAssertEqual(seasons[1].episodes[0].episodeTitle, "البداية")
        // An episode opens its series' page, so the episode queue is known.
        let episode = seasons[0].episodes[1]
        let (opened, again) = try await client.detail(episode)
        XCTAssertEqual(opened.id, episode.id); XCTAssertEqual(again.count, 2)
        XCTAssertEqual(transport.requests.last?.0, "items/s1")
    }

    // 6. authorised playback links, 7. invalid links refused
    func testPlaybackKeepsOnlyHTTPSStreamsAndSubtitles() async throws {
        let (client, _) = client(["items/m1/streams": "ext-streams"])
        let playback = try await client.playback(Media(id: "ext:test-src:m1", title: "x"))
        XCTAssertEqual(playback.streams.map(\.url.lastPathComponent), ["master.m3u8", "720.mp4"])
        XCTAssertEqual(playback.streams.map(\.quality), ["تلقائي", "720p"])
        XCTAssertEqual(playback.subtitles.map(\.language), ["ar"])
    }
    func testIdsAndProvidersAreValidated() throws {
        XCTAssertEqual(try ContentSource.owner(of: "ext:test-src:m1"), .external("test-src"))
        for id in ["ext:test-src:../x", "ext:Bad:x", "ext:test-src:", "ext::x", "ext:test-src:a/b"] {
            XCTAssertThrowsError(try ExternalKey(id), id)
        }
        XCTAssertThrowsError(try ExternalProvider(id: "x", name: "x", baseURL: URL(string: "http://insecure.example.com")!))
        XCTAssertThrowsError(try ExternalProvider(id: "x", name: "x", baseURL: URL(string: "https://u:p@example.com")!))
        XCTAssertEqual(ContentSource(rawValue: "ext:test-src"), .external("test-src"))
        XCTAssertNil(ContentSource(rawValue: "ext:missing"))
        XCTAssertEqual(ExternalProvider.slug(from: "My Media!", taken: ["my-media"]), "my-media-2")
    }

    // 8. network errors
    func testNetworkAndHTTPErrorsSurface() async {
        let (client, transport) = client([:])
        transport.failure = CinemaError.http(429)
        do { _ = try await client.categories(); XCTFail() } catch { XCTAssertEqual(error as? CinemaError, .http(429)) }
        XCTAssertNotNil(CinemaError.http(429).errorDescription)
        transport.failure = CinemaError.network(-1009)
        do { _ = try await client.playback(Media(id: "ext:test-src:m1", title: "x")); XCTFail() } catch { XCTAssertEqual(error as? CinemaError, .network(-1009)) }
        let (missing, _) = self.client([:])
        do { _ = try await missing.playback(Media(id: "ext:test-src:none", title: "x")); XCTFail() } catch { XCTAssertEqual(error as? CinemaError, .http(404)) }
    }

    // 9. the three built-in sources are untouched
    func testBuiltInSourcesKeepTheirIdentity() throws {
        XCTAssertEqual(try ContentSource.owner(of: "42"), .cinemana)
        XCTAssertEqual(try ContentSource.owner(of: "vodu:42:100"), .vodu)
        XCTAssertEqual(try ContentSource.owner(of: "daw:movie:b~42:mp4"), .dawwirli)
        XCTAssertEqual(ContentSource(rawValue: "cinemana"), .cinemana)
        XCTAssertEqual(ContentSource.builtIn.map(\.rawValue), ["cinemana", "vodu", "dawwirli"])
        XCTAssertEqual(ContentSource.allCases.count, 5)
    }

    // The demo catalogue end to end through SourceClient, as the app uses it.
    func testDemoProviderBrowsesAndResolvesLicensedStreams() async throws {
        let home = try await SourceClient().home(.external("demo"))
        XCTAssertEqual(home.map(\.id), ["films", "series", "streams"])
        let movies = try await SourceClient().catalog(.external("demo"), series: false, query: "", page: 1)
        XCTAssertEqual(movies.count, 5)
        let found = try await SourceClient().catalog(.external("demo"), series: false, query: "sintel", page: 1)
        XCTAssertEqual(found.map(\.id), ["ext:demo:sintel"])
        let series = try XCTUnwrap(home.first { $0.id == "series" }?.items.first)
        let (_, seasons) = try await SourceClient().detail(series)
        XCTAssertEqual(seasons.first?.episodes.count, 4)
        let streams = try await SourceClient().streams(try XCTUnwrap(seasons.first?.episodes.first))
        XCTAssertEqual(streams.first?.url.lastPathComponent, "BigBuckBunny.mp4")
        let hls = try await SourceClient().streams(Media(id: "ext:demo:apple-hls", title: "HLS"))
        XCTAssertEqual(hls.first?.url.pathExtension, "m3u8")
    }

    /// The licensed demo streams are really reachable: the HLS master lists variants and the MP4 answers.
    /// Skipped when the test machine is offline.
    func testDemoStreamsAreReachable() async throws {
        let hls = try await SourceClient().streams(Media(id: "ext:demo:apple-hls", title: "HLS"))
        let mp4 = try await SourceClient().streams(Media(id: "ext:demo:sintel", title: "Sintel"))
        do {
            let (manifest, response) = try await URLSession.shared.data(from: try XCTUnwrap(hls.first?.url))
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
            let text = String(decoding: manifest, as: UTF8.self)
            XCTAssertTrue(text.hasPrefix("#EXTM3U")); XCTAssertTrue(text.contains("#EXT-X-STREAM-INF"), "several qualities")
            var head = URLRequest(url: try XCTUnwrap(mp4.first?.url)); head.httpMethod = "HEAD"
            let (_, video) = try await URLSession.shared.data(for: head)
            let http = try XCTUnwrap(video as? HTTPURLResponse)
            XCTAssertEqual(http.statusCode, 200)
            XCTAssertTrue((http.value(forHTTPHeaderField: "Content-Type") ?? "").contains("video"))
        } catch let error as URLError {
            throw XCTSkip("offline: \(error.code.rawValue)")
        }
    }
}
