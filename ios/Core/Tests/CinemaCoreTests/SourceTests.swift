import XCTest
@testable import CinemaCore

final class SourceTests: XCTestCase {
    private func fixture(_ name: String) throws -> String {
        // SwiftPM resource processing flattens ordinary folders.
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "html"))
        return try String(contentsOf: url, encoding: .utf8)
    }
    func testSourceIdentityDoesNotDependOnSelectedProvider() throws {
        XCTAssertEqual(try ContentSource.owner(of: "42"), .cinemana)
        XCTAssertEqual(try ContentSource.owner(of: "vodu:42:100"), .vodu)
        XCTAssertEqual(try ContentSource.owner(of: "daw:movie:b~42:mp4"), .dawwirli)
        for id in ["42/../1", "vodu:../1", "vodu:1:2:3", "unknown:1", "", "0"] { XCTAssertThrowsError(try ContentSource.owner(of: id)) }
    }
    func testCinemanaCatalogEncodesArabicAndUsesZeroBasedPages() throws {
        let query = "فيلم / اختبار"
        let path = try CinemanaClient.catalogPath(series: true, query: query, page: 2)
        XCTAssertTrue(path.contains("videoKind/2")); XCTAssertTrue(path.contains("pageNumber/1/"))
        let encoded = path.components(separatedBy: "video_title_search/")[1].components(separatedBy: "/pageNumber")[0]
        XCTAssertEqual(String(data: try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(encoded.removingPercentEncoding))), encoding: .utf8), query)
        XCTAssertThrowsError(try CinemanaClient.catalogPath(series: false, query: "", page: 0))
    }
    func testCinemanaEnvelopesAndEpisodesKeepParentIdentity() throws {
        let json = #"{"data":{"2":[{"nb":22,"episodeNummer":2},{"nb":21,"episodeNummer":1}],"1":[{"nb":11,"episodeNummer":1}]}}"#
        let seasons = CinemanaClient.seasons(try JSONSerialization.jsonObject(with: Data(json.utf8)), parent: "99")
        XCTAssertEqual(seasons.map(\.id), [1, 2])
        XCTAssertEqual(seasons[1].episodes.map(\.id), ["21", "22"])
        XCTAssertTrue(seasons.flatMap(\.episodes).allSatisfy { $0.seriesID == "99" && $0.recentKey == "recent/99" })
        let items = CinemanaClient.items(["data": [["nb": 42, "ar_title": "اختبار", "imgMediumThumbObjUrl": "posters/42.jpg", "kind": 2]], "page": 1])
        XCTAssertEqual(items.first?.poster, "https://cnth2.shabakaty.cc/posters/42.jpg")
        XCTAssertTrue(items.first?.isSeries == true)
    }
    func testStreamsRejectInsecureURLsAndDeduplicate() {
        let streams = CinemanaClient.parseStreams([["videoUrl": "https://cdn.example/a.mp4", "resolution": "720p"], ["url": "https://cdn.example/a.mp4"], ["url": "http://cdn.example/a.mp4"], ["url": "file:///a"], ["url": "https://user:secret@cdn.example/a.mp4"]])
        XCTAssertEqual(streams.count, 1); XCTAssertEqual(streams.first?.quality, "720p")
    }
    func testVoduMovieMetadataAndQualities() throws {
        let html = try fixture("movie")
        let result = try VoduParser.detail(html, media: Media(id: "vodu:98489", title: ""))
        XCTAssertEqual(result.0.title, "The Wild Robot"); XCTAssertEqual(result.0.year, "2024")
        XCTAssertFalse(result.0.overview.isEmpty); XCTAssertFalse(result.0.isSeries)
        XCTAssertEqual(try VoduParser.streams(html, id: "vodu:98489").map(\.quality), ["720p", "1080p", "2160p", "360p"])
    }
    func testVoduSeriesSelectsExactEpisode() throws {
        let html = try fixture("series")
        let result = try VoduParser.detail(html, media: Media(id: "vodu:85752", title: ""))
        XCTAssertEqual(result.1.map(\.id), [1]); XCTAssertEqual(result.1[0].episodes.count, 10)
        let first = result.1[0].episodes[0]
        XCTAssertEqual(first.id, "vodu:85752:14205032"); XCTAssertEqual(first.episode, 1)
        XCTAssertEqual(first.seriesID, "vodu:85752")
        XCTAssertEqual(try VoduParser.streams(html, id: first.id).count, 3)
        XCTAssertTrue(try VoduParser.streams(html, id: "vodu:85752:999999999").isEmpty)
        XCTAssertEqual(try VoduParser.detail(html, media: first).0.id, first.id)
    }
    func testVoduCatalogAndBlockedResponse() throws {
        let items = try VoduParser.catalog(fixture("catalog"), series: false)
        XCTAssertFalse(items.isEmpty)
        XCTAssertTrue(items.allSatisfy { $0.id.hasPrefix("vodu:") && $0.poster.hasPrefix("https://movie.vodu.me/") })
        XCTAssertThrowsError(try VoduParser.catalog("<html>Blocked</html>", series: false))
        XCTAssertThrowsError(try VoduParser.detail("<html>Blocked</html>", media: Media(id: "vodu:1", title: "")))
        let url = VoduClient.pageURL(["title": "فيلم & test", "do": "list"])
        XCTAssertEqual(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "title" })?.value, "فيلم & test")
    }
    func testResponsesMayComeFromTheSourceNetworkOnly() {
        XCTAssertTrue(SourceNetwork.belongs("cinemana.shabakaty.cc", to: "shabakaty.cc"))
        XCTAssertTrue(SourceNetwork.belongs("CNTH2.Shabakaty.cc", to: "shabakaty.cc"))
        XCTAssertTrue(SourceNetwork.belongs("movie.vodu.me", to: "vodu.me"))
        XCTAssertFalse(SourceNetwork.belongs("evilshabakaty.cc", to: "shabakaty.cc"))
        XCTAssertFalse(SourceNetwork.belongs("shabakaty.cc.evil.com", to: "shabakaty.cc"))
        XCTAssertFalse(SourceNetwork.belongs(nil, to: "vodu.me"))
    }
    func testNetworkErrorsExplainTheCauseAndKeepTheCode() {
        XCTAssertTrue(CinemaError.network(-1003).localizedDescription.contains("-1003"))
        XCTAssertTrue(CinemaError.network(-1200).localizedDescription.contains("آمن"))
        XCTAssertTrue(CinemaError.http(503).localizedDescription.contains("503"))
        XCTAssertTrue(SourceNetwork.retryable(CinemaError.http(502)))
        XCTAssertFalse(SourceNetwork.retryable(CinemaError.http(404)))
    }
}
