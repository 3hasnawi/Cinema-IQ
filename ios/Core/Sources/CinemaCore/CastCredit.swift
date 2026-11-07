import Foundation

/// A displayed credit keeps provider-specific identities separate even when two providers use the same ID.
public struct CastCredit: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let photo: String
    public let usesTVMaze: Bool

    public init(id: String, name: String, photo: String = "", usesTVMaze: Bool = false) {
        self.id = id
        self.name = name
        self.photo = photo
        self.usesTVMaze = usesTVMaze
    }
}

public enum CastCredits {
    /// Two title matchers can disagree. Never enrich one work with another work's cast, and never use the
    /// television catalog for a movie. A malformed or missing identity cannot authorize a lookup by itself.
    public static func tvMazeLookupID(isSeries: Bool, tmdbID: String, omdbID: String) -> String? {
        guard isSeries else { return nil }
        let tmdb = TVMazeParser.isValidIMDbID(tmdbID) ? tmdbID : nil
        let omdb = TVMazeParser.isValidIMDbID(omdbID) ? omdbID : nil
        if let tmdb, let omdb, tmdb != omdb { return nil }
        return tmdb ?? omdb
    }

    /// Enrichment is limited to the verified series' own cast and an unambiguous, normalized full name.
    /// Existing order and portraits win. Unmatched credits keep their placeholders instead of disappearing.
    public static func merge(tmdb: [TMDBPerson], omdbActors: String, tvmaze: [TVMazePerson]) -> [CastCredit] {
        let primary: [CastCredit]
        if !tmdb.isEmpty {
            primary = tmdb.map { CastCredit(id: "tmdb:\($0.id)", name: $0.name, photo: $0.photo) }
        } else {
            primary = omdbActors.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty && $0 != "N/A" }.enumerated()
                .map { CastCredit(id: "omdb:\($0.offset)", name: $0.element) }
        }
        let primaryNames = Dictionary(grouping: primary, by: { identityName($0.name) })
        let fallbackNames = Dictionary(grouping: tvmaze, by: { identityName($0.name) })
        let enriched = primary.map { credit -> CastCredit in
            let name = identityName(credit.name)
            guard credit.photo.isEmpty, !name.isEmpty, primaryNames[name]?.count == 1,
                  let candidates = fallbackNames[name], candidates.count == 1,
                  let match = candidates.first, !match.photo.isEmpty else { return credit }
            return CastCredit(id: credit.id, name: credit.name, photo: match.photo, usesTVMaze: true)
        }
        var seen = Set<Int>()
        let additional = tvmaze.compactMap { person -> CastCredit? in
            let name = identityName(person.name)
            guard !name.isEmpty, primaryNames[name] == nil, seen.insert(person.id).inserted else { return nil }
            return CastCredit(id: "tvmaze:\(person.id)", name: person.name, photo: person.photo, usesTVMaze: true)
        }
        return enriched + additional
    }

    /// Case, accents, spaces and punctuation can differ between metadata providers. No fuzzy, reordered,
    /// substring or transliterated matching is used: those could put another person's photo on a credit.
    private static func identityName(_ name: String) -> String {
        name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(String.init).joined()
    }
}
