import Foundation

/// Source-curated movie highlights, in source order, for the Apple TV showcase.
public enum FeaturedMovies {
    public static func select(from shelves: [Shelf], limit: Int = 8) -> [Media] {
        guard limit > 0 else { return [] }
        let eligible = shelves.filter {
            !isSeriesShelf(id: $0.id, title: $0.title) && $0.items.contains(where: isMovie)
        }
        let best = eligible.map { priority(id: $0.id, title: $0.title) }.max() ?? 0
        // Providers without a featured group fall back to their movie rows, never series/episodes.
        let chosen = best > 0 ? eligible.filter { priority(id: $0.id, title: $0.title) == best } : eligible
        var seen = Set<String>()
        let movies = chosen.flatMap(\.items).filter { isMovie($0) && seen.insert($0.id).inserted }
        let illustrated = movies.filter { !$0.backdrop.isEmpty || !$0.poster.isEmpty }
        // Artwork never displaces curated movies. Only an uncurated fallback skips blank cards
        // when illustrated movies are available (for example, the demo's unillustrated HLS probe).
        return Array((best > 0 || illustrated.isEmpty ? movies : illustrated).prefix(limit))
    }

    /// Prefer an explicit featured-movies group to mixed highlights. Numeric group IDs stay opaque.
    static func priority(id: String, title: String) -> Int {
        guard !isSeriesShelf(id: id, title: title) else { return 0 }
        let text = normalized(id + " " + title)
        let words = Set(text.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty })
        let featured = text.contains("مميز") || text.contains("مختار") ||
            !words.isDisjoint(with: ["featured", "highlights", "spotlight"])
        guard featured else { return 0 }
        let movies = text.contains("افلام") || !words.isDisjoint(with: ["movie", "movies", "film", "films"])
        return movies ? 2 : 1
    }

    private static func isMovie(_ media: Media) -> Bool { !media.isSeries && media.episode == 0 }

    private static func isSeriesShelf(id: String, title: String) -> Bool {
        let text = normalized(id + " " + title)
        let words = Set(text.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty })
        return text.contains("مسلسل") || text.contains("حلقات") ||
            !words.isDisjoint(with: ["series", "shows", "episodes", "tv"])
    }

    private static func normalized(_ text: String) -> String {
        let folded = text.replacingOccurrences(of: "([a-z0-9])([A-Z])", with: "$1 $2", options: .regularExpression)
            .folding(options: [.diacriticInsensitive, .widthInsensitive], locale: Locale(identifier: "ar"))
            .decomposedStringWithCanonicalMapping
        // Foundation's diacritic folding differs across platforms for Arabic vowel marks.
        return String(String.UnicodeScalarView(folded.unicodeScalars.filter { !CharacterSet.nonBaseCharacters.contains($0) }))
            .lowercased()
            .replacingOccurrences(of: "ـ", with: "")
            .replacingOccurrences(of: "[أإآٱ]", with: "ا", options: .regularExpression)
    }
}
