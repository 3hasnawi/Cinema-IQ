import XCTest
@testable import CinemaCore

final class PlaybackPolicyTests: XCTestCase {
    func testNextStartsAtCreditsEvenBeforeLastTwentySeconds() {
        XCTAssertFalse(PlaybackPolicy.offersNext(hasNext: true, ended: false, position: 2499,
                                               duration: 2700, creditsStart: 2500))
        XCTAssertTrue(PlaybackPolicy.offersNext(hasNext: true, ended: false, position: 2500,
                                              duration: 2700, creditsStart: 2500))
    }

    func testNextFallbackAndMissingEpisode() {
        XCTAssertFalse(PlaybackPolicy.offersNext(hasNext: true, ended: false, position: 2679,
                                               duration: 2700, creditsStart: nil))
        XCTAssertTrue(PlaybackPolicy.offersNext(hasNext: true, ended: false, position: 2680,
                                              duration: 2700, creditsStart: nil))
        XCTAssertTrue(PlaybackPolicy.offersNext(hasNext: true, ended: true, position: 0,
                                              duration: 0, creditsStart: nil))
        XCTAssertFalse(PlaybackPolicy.offersNext(hasNext: false, ended: true, position: 2700,
                                               duration: 2700, creditsStart: 2500))
    }

    func testInvalidDurationAndCreditsDoNotOfferNextEarly() {
        for duration in [0, -1, Double.nan, .infinity] {
            XCTAssertFalse(PlaybackPolicy.offersNext(hasNext: true, ended: false, position: 40,
                                                   duration: duration, creditsStart: 30))
        }
        for credits in [-1, 2700, Double.nan, .infinity] {
            XCTAssertFalse(PlaybackPolicy.offersNext(hasNext: true, ended: false, position: 40,
                                                   duration: 2700, creditsStart: credits))
        }
    }

    func testNextCompletionSurvivesPeriodicAndCloseSaves() {
        var completion = PlaybackCompletion()
        XCTAssertFalse(completion.record(completed: false))
        XCTAssertTrue(completion.record(completed: true))
        XCTAssertTrue(completion.record(completed: false))
        XCTAssertTrue(completion.record(completed: false))
        XCTAssertFalse(PlaybackCompletion().completed, "An intentional replay starts a new session")
    }

    func testQualityChangeFindsSameAudioAfterTrackReordering() {
        let arabic = PlaybackTrackIdentity(language: "ar_IQ", title: "Arabic", characteristics: ["main"])
        let english = PlaybackTrackIdentity(language: "en", title: "English", characteristics: ["main"])
        XCTAssertEqual(PlaybackPolicy.matchingTrack(arabic, in: [english, arabic]), 1)
        XCTAssertEqual(arabic.language, "ar-iq")
    }

    func testAudioLanguageFallbackDoesNotSelectCommentaryOrAmbiguousTracks() {
        let selected = PlaybackTrackIdentity(language: "ar", title: "Arabic Stereo", characteristics: ["main"])
        let surround = PlaybackTrackIdentity(language: "ar", title: "Arabic Surround", characteristics: ["main"])
        let commentary = PlaybackTrackIdentity(language: "ar", title: "Commentary", characteristics: ["auxiliary"])
        XCTAssertEqual(PlaybackPolicy.matchingTrack(selected, in: [commentary, surround]), 1)
        XCTAssertNil(PlaybackPolicy.matchingTrack(selected, in: [commentary]))
        XCTAssertNil(PlaybackPolicy.matchingTrack(selected, in: [surround, surround]))
        XCTAssertNil(PlaybackPolicy.matchingTrack(selected, in: []))
    }

    func testUnlabelledAudioUsesTitleAndNotOldIndex() {
        let chosen = PlaybackTrackIdentity(language: nil, title: "Original")
        let other = PlaybackTrackIdentity(language: nil, title: "Dubbed")
        XCTAssertEqual(PlaybackPolicy.matchingTrack(chosen, in: [other, chosen]), 1)
        XCTAssertNil(PlaybackPolicy.matchingTrack(chosen, in: [other]))
    }

    func testIntroBoundariesRemainIndependentOfNext() {
        let segments = SkipSegments(introStart: 10, introEnd: 90, creditsStart: 2500)
        XCTAssertFalse(segments.inIntro(9))
        XCTAssertTrue(segments.inIntro(10))
        XCTAssertTrue(segments.inIntro(87))
        XCTAssertFalse(segments.inIntro(88))
        XCTAssertFalse(segments.inIntro(2500))
    }
}
