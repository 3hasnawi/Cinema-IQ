import XCTest
@testable import CinemaCore

final class CastCreditTests: XCTestCase {
    func testTVMazeLookupRejectsMoviesAndConflictingWorkIdentities() {
        XCTAssertNil(CastCredits.tvMazeLookupID(isSeries: false, tmdbID: "tt26450613", omdbID: "tt26450613"))
        XCTAssertNil(CastCredits.tvMazeLookupID(isSeries: true, tmdbID: "tt26450613", omdbID: "tt0000001"))
    }

    func testTVMazeLookupChoosesOnlyAValidConsistentIdentity() {
        for identities in [("tt26450613", "tt26450613"), ("tt26450613", ""), ("", "tt26450613"), ("invalid", "tt26450613")] {
            XCTAssertEqual(CastCredits.tvMazeLookupID(isSeries: true, tmdbID: identities.0, omdbID: identities.1), "tt26450613")
        }
        XCTAssertNil(CastCredits.tvMazeLookupID(isSeries: true, tmdbID: "tt26450613\n", omdbID: "N/A"))
    }

    func testEnrichesVerifiedFullNameAndPreservesUnmatchedActor() {
        let merged = CastCredits.merge(tmdb: [], omdbActors: "Lee Dong-wook, Kim Hye Joon, Unknown Actor", tvmaze: [
            TVMazePerson(id: 1, name: "Lee Dong Wook", photo: "lee.jpg"),
            TVMazePerson(id: 2, name: "Kim Hye Joon", photo: "kim.jpg")
        ])
        XCTAssertEqual(merged.map(\.name), ["Lee Dong-wook", "Kim Hye Joon", "Unknown Actor"])
        XCTAssertEqual(merged.map(\.photo), ["lee.jpg", "kim.jpg", ""])
        XCTAssertEqual(merged.map(\.usesTVMaze), [true, true, false])
    }

    func testExistingTMDBPhotoWinsAndDoesNotNeedTVMazeAttribution() {
        let merged = CastCredits.merge(tmdb: [TMDBPerson(id: 1, name: "Actor", photo: "tmdb.jpg")],
                                       omdbActors: "Other", tvmaze: [TVMazePerson(id: 1, name: "Actor", photo: "tvmaze.jpg")])
        XCTAssertEqual(merged, [CastCredit(id: "tmdb:1", name: "Actor", photo: "tmdb.jpg")])
    }

    func testNamespacedIDsAndAdditionalSeriesCast() {
        let merged = CastCredits.merge(tmdb: [TMDBPerson(id: 1, name: "First", photo: "")], omdbActors: "", tvmaze: [
            TVMazePerson(id: 1, name: "Second", photo: "second.jpg"),
            TVMazePerson(id: 1, name: "Second", photo: "second.jpg")
        ])
        XCTAssertEqual(merged.map(\.id), ["tmdb:1", "tvmaze:1"])
        XCTAssertEqual(merged.map(\.photo), ["", "second.jpg"])
    }

    func testNoFuzzyReorderedOrSubstringMatching() {
        let merged = CastCredits.merge(tmdb: [], omdbActors: "Lee Dong Wook, Park, John Doe", tvmaze: [
            TVMazePerson(id: 1, name: "Lee Dong Won", photo: "wrong.jpg"),
            TVMazePerson(id: 2, name: "Park Ji Sung", photo: "wrong.jpg"),
            TVMazePerson(id: 3, name: "Doe John", photo: "wrong.jpg")
        ])
        XCTAssertEqual(Array(merged.prefix(3)).map(\.photo), ["", "", ""])
        XCTAssertEqual(merged.count, 6, "Unmatched real series cast can be appended with their own names")
    }

    func testAmbiguousSameNameNeverEnrichesAPrimaryCredit() {
        let merged = CastCredits.merge(tmdb: [], omdbActors: "Alex Lee", tvmaze: [
            TVMazePerson(id: 1, name: "Alex Lee", photo: "one.jpg"),
            TVMazePerson(id: 2, name: "Alex Lee", photo: "two.jpg")
        ])
        XCTAssertEqual(merged, [CastCredit(id: "omdb:0", name: "Alex Lee")])
    }

    func testAmbiguousPrimaryNamesKeepPlaceholders() {
        let merged = CastCredits.merge(tmdb: [TMDBPerson(id: 1, name: "Alex Lee", photo: ""),
                                             TMDBPerson(id: 2, name: "Alex Lee", photo: "")],
                                       omdbActors: "", tvmaze: [TVMazePerson(id: 9, name: "Alex Lee", photo: "ambiguous.jpg")])
        XCTAssertEqual(merged.map(\.photo), ["", ""])
        XCTAssertFalse(merged.contains(where: \.usesTVMaze))
    }

    func testEmptyAndMissingMetadataStillProduceHonestPlaceholders() {
        XCTAssertTrue(CastCredits.merge(tmdb: [], omdbActors: " N/A,  ", tvmaze: []).isEmpty)
        let merged = CastCredits.merge(tmdb: [], omdbActors: "Actor", tvmaze: [TVMazePerson(id: 1, name: "Actor", photo: "")])
        XCTAssertEqual(merged, [CastCredit(id: "omdb:0", name: "Actor")])
    }

    func testCaseDiacriticsAndPunctuationAreNormalized() {
        let merged = CastCredits.merge(tmdb: [], omdbActors: "Élodie O’Connor", tvmaze: [
            TVMazePerson(id: 1, name: "elodie o'connor", photo: "portrait.jpg")
        ])
        XCTAssertEqual(merged.first?.photo, "portrait.jpg")
        XCTAssertEqual(merged.first?.name, "Élodie O’Connor")
    }
}
