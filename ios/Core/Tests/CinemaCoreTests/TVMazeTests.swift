import XCTest
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import CinemaCore

final class TVMazeTests: XCTestCase {
    private let imdb = "tt26450613"
    private let photo = "https://static.tvmaze.com/uploads/images/medium_portrait/18/45462.jpg"
    private var show: [String: Any] { ["id": 73612, "externals": ["imdb": imdb]] }
    private var rows: [[String: Any]] {
        [["person": ["id": 84383, "name": "Lee Dong Wook", "image": ["medium": photo]]],
         ["person": ["id": 137184, "name": "Kim Hye Joon", "image": NSNull()]]]
    }
    private func json(_ object: Any) throws -> Data { try JSONSerialization.data(withJSONObject: object) }
    private func success() throws -> [TVMazeStub.Reply] { [.init(body: try json(show)), .init(body: try json(rows))] }

    private func searchShow(_ name: String = "A Shop for Killers", year: String = "2024", id: Int = 73612) -> [String: Any] {
        ["show": ["id": id, "name": name, "premiered": year + "-01-17"]]
    }
    private var sourceSeries: Media {
        var media = Media(id: "fixture", title: "متجر القتلة")
        media.isSeries = true; media.enTitle = "A Shop for Killers"; media.year = "2024"
        return media
    }

    func testTitleIdentityRequiresExactLatinNameAndPremiereYear() {
        XCTAssertNotNil(TVMazeParser.exactTitleShow([searchShow()], title: "  A SHOP  for Killers  ", year: "2024"))
        for title in ["A Shop for Killer", "Shop for Killers", "Killers for A Shop", "متجر القتلة", "", "123"] {
            XCTAssertNil(TVMazeParser.exactTitleShow([searchShow()], title: title, year: "2024"), title)
        }
        for year in ["", "2023", "2024–2025", "2024 ", "2024\n", "24"] {
            XCTAssertNil(TVMazeParser.exactTitleShow([searchShow()], title: "A Shop for Killers", year: year), year)
        }
        XCTAssertNil(TVMazeParser.exactTitleShow([["show": ["id": 1, "name": "A Shop for Killers"]]],
                                               title: "A Shop for Killers", year: "2024"))
        XCTAssertNil(TVMazeParser.exactTitleShow([searchShow(id: -1)], title: "A Shop for Killers", year: "2024"))
    }

    func testTitleIdentityRejectsAmbiguityRemakesAndPunctuationChanges() {
        for candidates in [[searchShow(), searchShow(id: 2)],
                           [searchShow(), searchShow(year: "1999", id: 2)],
                           [searchShow("A-Shop for Killers")]] {
            XCTAssertNil(TVMazeParser.exactTitleShow(candidates, title: "A Shop for Killers", year: "2024"))
        }
        XCTAssertNotNil(TVMazeParser.exactTitleShow([searchShow(), searchShow("Other Show", id: 2)],
                                                  title: "A Shop for Killers", year: "2024"))
    }

    func testKeylessClientSearchesSourceEnglishTitleThenOnlyMatchedShowCast() async throws {
        let stub = TVMazeStub([.init(body: try json([searchShow()])), .init(body: try json(rows))])
        defer { stub.close() }
        let result = await TVMazeClient(session: stub.session).cast(for: sourceSeries, tmdbID: "", omdbID: "")
        XCTAssertEqual(result?.showID, 73612)
        XCTAssertEqual(result?.cast.count, 2)
        XCTAssertEqual(result?.imdbID, "", "An IMDb ID is not fabricated for a title match")
        XCTAssertEqual(stub.requests.map { $0.url?.path }, ["/search/shows", "/shows/73612/cast"])
        let query = stub.requests.first?.url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?.queryItems
        XCTAssertEqual(query, [URLQueryItem(name: "q", value: "a shop for killers")])
        XCTAssertTrue(stub.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == nil && !$0.httpShouldHandleCookies })
    }

    func testKeylessClientRejectsUnsafeInputsAndMetadataConflictsWithoutNetwork() async {
        var inputs: [Media] = []
        var movie = sourceSeries; movie.isSeries = false; inputs.append(movie)
        var episode = sourceSeries; episode.episode = 1; inputs.append(episode)
        var noYear = sourceSeries; noYear.year = ""; inputs.append(noYear)
        var arabic = sourceSeries; arabic.enTitle = ""; inputs.append(arabic)
        let stub = TVMazeStub([]); defer { stub.close() }
        let client = TVMazeClient(session: stub.session)
        for media in inputs {
            let result = await client.cast(for: media, tmdbID: "", omdbID: "")
            XCTAssertNil(result)
        }
        let conflict = await client.cast(for: sourceSeries, tmdbID: imdb, omdbID: "tt0000001")
        XCTAssertNil(conflict)
        XCTAssertTrue(stub.requests.isEmpty)
    }

    func testKeylessClientAcceptsLatinDisplayTitleWhenEnglishTitleMissing() async throws {
        var media = sourceSeries; media.enTitle = ""; media.title = "A Shop for Killers"
        let stub = TVMazeStub([.init(body: try json([searchShow()])), .init(body: try json(rows))]); defer { stub.close() }
        let result = await TVMazeClient(session: stub.session).cast(for: media, tmdbID: "", omdbID: "")
        XCTAssertEqual(result?.showID, 73612)
    }

    func testKeylessClientDoesNotFetchCastForFuzzyAmbiguousOrWrongYearSearch() async throws {
        for candidates in [[], [searchShow("A Shop for Killer")], [searchShow(year: "2023")],
                           [searchShow(), searchShow(year: "1999", id: 2)]] {
            let stub = TVMazeStub([.init(body: try json(candidates))]); defer { stub.close() }
            let result = await TVMazeClient(session: stub.session).cast(for: sourceSeries, tmdbID: "", omdbID: "")
            XCTAssertNil(result)
            XCTAssertEqual(stub.requests.count, 1)
        }
    }

    func testSourceLookupKeepsIMDbPathAndNeverSearchesAfterIMDbMiss() async throws {
        let stub = TVMazeStub(try success()); defer { stub.close() }
        let result = await TVMazeClient(session: stub.session).cast(for: sourceSeries, tmdbID: imdb, omdbID: "")
        XCTAssertEqual(result?.cast.count, 2)
        XCTAssertEqual(stub.requests.first?.url?.path, "/lookup/shows")
        let miss = TVMazeStub([.init(status: 404)]); defer { miss.close() }
        let missing = await TVMazeClient(session: miss.session).cast(for: sourceSeries, tmdbID: "", omdbID: imdb)
        XCTAssertNil(missing)
        XCTAssertEqual(miss.requests.count, 1)
        XCTAssertEqual(miss.requests.first?.url?.path, "/lookup/shows")
    }

    func testIMDbValidationRejectsQueriesWhitespaceAndWrongIdentifiers() {
        XCTAssertTrue(TVMazeParser.isValidIMDbID(imdb))
        XCTAssertTrue(TVMazeParser.isValidIMDbID("tt0000001"))
        for bad in ["", "tt123", "nm26450613", "TT26450613", " tt26450613", "tt26450613\n",
                    "tt26450613&foo=1", "tt１２３４５６７", "tt1234567890123"] {
            XCTAssertFalse(TVMazeParser.isValidIMDbID(bad), bad)
        }
    }

    func testParserVerifiesShowIdentityAndPreservesActorWithoutPhoto() throws {
        let result = try XCTUnwrap(TVMazeParser.cast(show: show, rows: rows, imdbID: imdb))
        XCTAssertEqual(result.showID, 73612)
        XCTAssertEqual(result.imdbID, imdb)
        XCTAssertEqual(result.sourceURL, "https://www.tvmaze.com/shows/73612")
        XCTAssertEqual(result.cast.map(\.stableID), ["tvmaze:84383", "tvmaze:137184"])
        XCTAssertEqual(result.cast.map(\.photo), [photo, ""])
        XCTAssertNil(TVMazeParser.cast(show: show, rows: rows, imdbID: "tt0000001"))
        XCTAssertNil(TVMazeParser.cast(show: ["id": 73612], rows: rows, imdbID: imdb))
        XCTAssertNil(TVMazeParser.cast(show: ["id": -1, "externals": ["imdb": imdb]], rows: rows, imdbID: imdb))
    }

    func testPortraitsAcceptOnlyCredentialFreeTVmazeHTTPSImageURLs() {
        XCTAssertEqual(TVMazeParser.portraitURL(photo), photo)
        for bad in ["http://static.tvmaze.com/uploads/images/a.jpg", "https://static.tvmaze.com.evil.test/uploads/images/a.jpg",
                    "https://evil.test/uploads/images/a.jpg", "https://user:password@static.tvmaze.com/uploads/images/a.jpg",
                    "https://static.tvmaze.com:8080/uploads/images/a.jpg", "https://static.tvmaze.com/other/a.jpg",
                    "https://static.tvmaze.com/uploads/images/../a.jpg", "https://static.tvmaze.com/uploads/images/%2e%2e/a.jpg",
                    photo + "?token=value", photo + "#fragment", "/uploads/images/a.jpg", "file:///a.jpg"] {
            XCTAssertEqual(TVMazeParser.portraitURL(bad), "", bad)
        }
    }

    func testParserDeduplicatesPeopleNotNamesAndNeverUsesCharacterPortraits() throws {
        let input: [[String: Any]] = [
            ["person": ["id": 1, "name": "  Same Name \n", "image": ["original": photo]]],
            ["person": ["id": 1, "name": "Duplicate"]],
            ["person": ["id": 2, "name": "Same Name"], "character": ["image": ["medium": photo]]],
            ["person": ["id": 3, "name": "No portrait", "image": ["medium": "https://evil.test/a.jpg"]]],
            ["person": ["id": 0, "name": "Invalid"]], ["person": ["id": 4, "name": "  "]]
        ]
        let people = try XCTUnwrap(TVMazeParser.cast(show: show, rows: input, imdbID: imdb)).cast
        XCTAssertEqual(people.map(\.id), [1, 2, 3])
        XCTAssertEqual(people.map(\.name), ["Same Name", "Same Name", "No portrait"])
        XCTAssertEqual(people.map(\.photo), [photo, "", ""])
        let large: [[String: Any]] = (1...50).map { ["person": ["id": $0, "name": "Actor \($0)"]] }
        XCTAssertEqual(TVMazeParser.cast(show: show, rows: large, imdbID: imdb)?.cast.count, 30)
    }

    func testClientUsesOnlyExactLookupThenCastAndCachesSuccess() async throws {
        let stub = TVMazeStub(try success()); defer { stub.close() }
        let client = TVMazeClient(session: stub.session)
        let first = await client.cast(imdbID: imdb)
        let second = await client.cast(imdbID: imdb)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first?.cast.count, 2)
        let requests = stub.requests
        XCTAssertEqual(requests.map { $0.url?.absoluteString }, [
            "https://api.tvmaze.com/lookup/shows?imdb=tt26450613", "https://api.tvmaze.com/shows/73612/cast"
        ])
        XCTAssertTrue(requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == nil &&
            $0.value(forHTTPHeaderField: "Cookie") == nil && !$0.httpShouldHandleCookies })
    }

    func testInvalidIdentityDoesNotMakeAnyRequest() async {
        let stub = TVMazeStub([]); defer { stub.close() }
        let client = TVMazeClient(session: stub.session)
        let value = await client.cast(imdbID: imdb + "&api_key=anything")
        XCTAssertNil(value)
        XCTAssertTrue(stub.requests.isEmpty)
    }

    func testIdentityMismatchStopsBeforeCastAndDoesNotCacheFailure() async throws {
        let wrong = ["id": 73612, "externals": ["imdb": "tt0000001"]] as [String: Any]
        let stub = TVMazeStub([.init(body: try json(wrong))] + (try success())); defer { stub.close() }
        let client = TVMazeClient(session: stub.session)
        let first = await client.cast(imdbID: imdb)
        let second = await client.cast(imdbID: imdb)
        XCTAssertNil(first)
        XCTAssertEqual(second?.showID, 73612)
        XCTAssertEqual(stub.requests.count, 3)
    }

    func testServerErrorsRateLimitsMalformedJSONAndNetworkErrorsAreNotNegativeCached() async throws {
        let failures: [TVMazeStub.Reply] = [.init(status: 503), .init(status: 429),
            .init(body: Data("not JSON".utf8)), .init(failure: URLError(.timedOut))]
        for failure in failures {
            let stub = TVMazeStub([failure] + (try success())); defer { stub.close() }
            let client = TVMazeClient(session: stub.session)
            let first = await client.cast(imdbID: imdb)
            let second = await client.cast(imdbID: imdb)
            XCTAssertNil(first)
            XCTAssertEqual(second?.cast.count, 2)
            XCTAssertEqual(stub.requests.count, 3)
        }
    }

    func testCastFailureDoesNotCacheASuccessfulShowLookup() async throws {
        let stub = TVMazeStub([.init(body: try json(show)), .init(status: 404)] + (try success()))
        defer { stub.close() }
        let client = TVMazeClient(session: stub.session)
        let first = await client.cast(imdbID: imdb)
        let second = await client.cast(imdbID: imdb)
        XCTAssertNil(first)
        XCTAssertEqual(second?.cast.count, 2)
        XCTAssertEqual(stub.requests.count, 4)
    }

    func testForeignResponseURLIsRejected() async throws {
        let stub = TVMazeStub([.init(body: try json(show), responseURL: URL(string: "https://evil.test/show"))])
        defer { stub.close() }
        let value = await TVMazeClient(session: stub.session).cast(imdbID: imdb)
        XCTAssertNil(value)
        XCTAssertEqual(stub.requests.count, 1)
    }

    func testLookupMissExpiresAfterOneDay() async throws {
        let clock = TVMazeClock()
        let stub = TVMazeStub([.init(status: 404)] + (try success())); defer { stub.close() }
        let client = TVMazeClient(session: stub.session, now: { clock.now })
        let first = await client.cast(imdbID: imdb)
        let cached = await client.cast(imdbID: imdb)
        XCTAssertNil(first); XCTAssertNil(cached)
        XCTAssertEqual(stub.requests.count, 1)
        clock.advance(86_400)
        let refreshed = await client.cast(imdbID: imdb)
        XCTAssertEqual(refreshed?.cast.count, 2)
        XCTAssertEqual(stub.requests.count, 3)
    }

    func testSuccessfulCastExpiresAfterOneWeekAndEmptyCastAfterOneHour() async throws {
        for empty in [false, true] {
            let clock = TVMazeClock()
            let initial: [TVMazeStub.Reply] = [.init(body: try json(show)), .init(body: try json(empty ? [] : rows))]
            let stub = TVMazeStub(initial + (try success())); defer { stub.close() }
            let client = TVMazeClient(session: stub.session, now: { clock.now })
            let first = await client.cast(imdbID: imdb)
            XCTAssertEqual(first?.cast.count, empty ? 0 : 2)
            let ttl: TimeInterval = empty ? 3600 : 7 * 86_400
            clock.advance(ttl - 1)
            _ = await client.cast(imdbID: imdb)
            XCTAssertEqual(stub.requests.count, 2)
            clock.advance(1)
            let refreshed = await client.cast(imdbID: imdb)
            XCTAssertEqual(refreshed?.cast.count, 2)
            XCTAssertEqual(stub.requests.count, 4)
        }
    }

    func testDiskCacheRoundTripAndUnsafeCachedImageIsDiscarded() async throws {
        let cache = FileManager.default.temporaryDirectory.appendingPathComponent("tvmaze-test-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: cache) }
        let stub = TVMazeStub(try success()); defer { stub.close() }
        let first = await TVMazeClient(cacheURL: cache, session: stub.session).cast(imdbID: imdb)
        XCTAssertNotNil(first)
        let offline = TVMazeStub([]); defer { offline.close() }
        let cached = await TVMazeClient(cacheURL: cache, session: offline.session).cast(imdbID: imdb)
        XCTAssertEqual(cached, first)
        XCTAssertTrue(offline.requests.isEmpty)
        let original = try String(contentsOf: cache, encoding: .utf8)
        // JSONEncoder may escape slash characters; replacing only the hostname handles either representation.
        try original.replacingOccurrences(of: "static.tvmaze.com", with: "evil.test").write(to: cache, atomically: true, encoding: .utf8)
        let retry = TVMazeStub(try success()); defer { retry.close() }
        let refreshed = await TVMazeClient(cacheURL: cache, session: retry.session).cast(imdbID: imdb)
        XCTAssertEqual(refreshed, first)
        XCTAssertEqual(retry.requests.count, 2)
    }

    func testConcurrentRequestsShareOneLookupAndOneCastRequest() async throws {
        var replies = try success()
        replies[0].delay = 0.05
        let stub = TVMazeStub(replies); defer { stub.close() }
        let client = TVMazeClient(session: stub.session)
        let identity = imdb
        let results = await withTaskGroup(of: TVMazeCast?.self, returning: [TVMazeCast?].self) { group in
            for _ in 0..<8 { group.addTask { await client.cast(imdbID: identity) } }
            var values: [TVMazeCast?] = []
            for await value in group { values.append(value) }
            return values
        }
        XCTAssertEqual(results.count, 8)
        XCTAssertTrue(results.allSatisfy { $0?.showID == 73612 })
        XCTAssertEqual(stub.requests.count, 2)
    }
}

private final class TVMazeClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value = Date(timeIntervalSince1970: 1_800_000_000)
    var now: Date { lock.lock(); defer { lock.unlock() }; return value }
    func advance(_ interval: TimeInterval) { lock.lock(); defer { lock.unlock() }; value.addTimeInterval(interval) }
}

/// Every session has an isolated response queue. Unregistered or unexpected requests fail instead of using the network.
private final class TVMazeStub: @unchecked Sendable {
    struct Reply: Sendable {
        var status = 200
        var body = Data()
        var failure: URLError? = nil
        var responseURL: URL? = nil
        var delay: TimeInterval = 0
    }
    private let lock = NSLock()
    private var replies: [Reply]
    private var recorded: [URLRequest] = []
    private let token: String
    let session: URLSession
    var requests: [URLRequest] { lock.lock(); defer { lock.unlock() }; return recorded }

    init(_ replies: [Reply]) {
        self.replies = replies
        let token = UUID().uuidString
        self.token = token
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [TVMazeURLProtocol.self]
        configuration.httpAdditionalHeaders = ["X-TVmaze-Test": token]
        session = URLSession(configuration: configuration)
        TVMazeURLProtocol.register(self, token: token)
    }

    func next(_ request: URLRequest) -> Reply {
        lock.lock(); defer { lock.unlock() }
        recorded.append(request)
        return replies.isEmpty ? Reply(failure: URLError(.resourceUnavailable)) : replies.removeFirst()
    }

    func close() { session.invalidateAndCancel(); TVMazeURLProtocol.unregister(token) }
}

private final class TVMazeURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var stubs: [String: TVMazeStub] = [:]
    static func register(_ stub: TVMazeStub, token: String) { lock.lock(); defer { lock.unlock() }; stubs[token] = stub }
    static func unregister(_ token: String) { lock.lock(); defer { lock.unlock() }; stubs[token] = nil }
    private static func stub(for request: URLRequest) -> TVMazeStub? {
        lock.lock(); defer { lock.unlock() }
        return request.value(forHTTPHeaderField: "X-TVmaze-Test").flatMap { stubs[$0] }
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let stub = Self.stub(for: request) else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL)); return
        }
        let reply = stub.next(request)
        let finish: @Sendable () -> Void = { [self] in
            if let error = reply.failure { client?.urlProtocol(self, didFailWithError: error); return }
            guard let url = reply.responseURL ?? request.url,
                  let response = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                                 headerFields: ["Content-Type": "application/json"]) else {
                client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: reply.body)
            client?.urlProtocolDidFinishLoading(self)
        }
        if reply.delay > 0 { DispatchQueue.global().asyncAfter(deadline: .now() + reply.delay, execute: finish) }
        else { finish() }
    }
    override func stopLoading() {}
}
