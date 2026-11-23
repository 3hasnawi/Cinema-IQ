import XCTest
@testable import CinemaCore

final class ContinueWatchingTests: XCTestCase {
    private func episode(_ number: Int, season: Int = 1) -> Media {
        var media = Media(id: "s\(season)e\(number)", title: "Series")
        media.episode = number; media.season = season; media.seriesID = "series"
        return media
    }

    func testRemainingQueueAcrossSeasonsAndOutOfOrder() {
        let first = episode(1), second = episode(2), finale = episode(1, season: 2)
        let queue = [first, second, finale]
        XCTAssertEqual(ContinueWatching.remainingEpisode(after: first, in: queue) { $0 == second.id }, finale)
        XCTAssertEqual(ContinueWatching.remainingEpisode(after: finale, in: queue) { $0 == first.id }, second)
        XCTAssertNil(ContinueWatching.remainingEpisode(after: finale, in: queue) { _ in true })
    }

    func testRankLatestActivityIncludingUnstartedNextEpisodeAndExcludeStaleCompletedCards() {
        let film = Media(id: "film", title: "Film"), finished = Media(id: "done", title: "Finished")
        let next = episode(2)
        let items = [next, finished, film, next]
        let times = [film.recentKey: Date(timeIntervalSince1970: 20), next.recentKey: Date(timeIntervalSince1970: 5)]
        XCTAssertEqual(ContinueWatching.ordered(items, activity: times) { $0 == finished.id }, [film, next])
        XCTAssertEqual(ContinueWatching.ordered(items, activity: [:]) { $0 == finished.id }, [next, film])
    }

    func testIntroDBBoundariesAndNoTimingFallback() {
        let timings = SkipSegments(introStart: 6, introEnd: 105, creditsStart: 3252)
        XCTAssertNil(timings.introTarget(at: 2, duration: 3320))
        XCTAssertEqual(timings.introTarget(at: 6, duration: 3320), 105)
        XCTAssertNil(timings.introTarget(at: 6, duration: 70))
        XCTAssertFalse(timings.inCredits(3251, duration: 3320))
        XCTAssertTrue(timings.inCredits(3252, duration: 3320))
        XCTAssertTrue(timings.offersNext(at: 3252, duration: 3320))
        let late = SkipSegments(creditsStart: 3310)
        XCTAssertFalse(late.offersNext(at: 3301, duration: 3320))
        for fallback in [SkipSegments(), SkipSegments(creditsStart: 5), SkipSegments(creditsStart: 4000)] {
            XCTAssertTrue(fallback.offersNext(at: 3301, duration: 3320))
            XCTAssertFalse(fallback.inCredits(3301, duration: 3320))
        }
    }
}
