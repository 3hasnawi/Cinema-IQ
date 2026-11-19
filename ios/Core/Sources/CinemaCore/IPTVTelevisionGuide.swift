import Foundation

/// Presentation rules are scoped to one subscription. A browse grant lasts only for its open guide.
public struct IPTVGroupRules: Codable, Equatable, Sendable {
    public var hidden: Set<String> = []
    public var locked: Set<String> = []
    public init() {}
    public func allows(_ group: String, visit: String? = nil) -> Bool {
        !hidden.contains(group) && (!locked.contains(group) || group == visit)
    }
}

public struct IPTVGuideCategory: Identifiable, Sendable {
    public var id: String { name }
    public let name: String
    public let count: Int
}

/// Prepare once per catalog or rule change, before opening the guide. Stream URLs stay in memory.
public struct IPTVTelevisionGuide: Sendable {
    public let sourceID: String
    public let revision = UUID()
    public let unrestricted: [IPTVChannel]
    public let categories: [IPTVGuideCategory]
    public let rules: IPTVGroupRules
    private let grouped: [String: [IPTVChannel]]
    private let searchTerms: [String: String]
    public let numbers: [String: Int]

    public init(channels: [IPTVChannel], sourceID: String, rules: IPTVGroupRules = .init()) {
        self.sourceID = sourceID
        self.rules = rules
        var all: [IPTVChannel] = [], groups: [String: [IPTVChannel]] = [:]
        var order: [String] = [], terms: [String: String] = [:], ids: [String: Int] = [:]
        for channel in channels where channel.sourceID == sourceID && ids[channel.id] == nil {
            ids[channel.id] = ids.count + 1
            if groups[channel.group] == nil { order.append(channel.group) }
            groups[channel.group, default: []].append(channel)
            if rules.allows(channel.group) { all.append(channel) }
            terms[channel.id] = Self.fold(channel.name + " " + channel.group)
        }
        unrestricted = all
        grouped = groups
        categories = order.map { IPTVGuideCategory(name: $0, count: groups[$0]?.count ?? 0) }
        searchTerms = terms
        numbers = ids
    }

    public func rows(section: IPTVChannelSection, visit: String?, favorites: Set<String>, query: String = "") -> [IPTVChannel] {
        let rows: [IPTVChannel]
        switch section {
        case .all: rows = unrestricted
        case .favorites: rows = unrestricted.filter { favorites.contains($0.id) }
        case .group(let group): rows = rules.allows(group, visit: visit) ? grouped[group] ?? [] : []
        case .recent: rows = []
        }
        let term = Self.fold(query)
        guard !term.isEmpty else { return rows }
        let number = Int(term)
        return rows.filter { (number != nil && numbers[$0.id] == number) || searchTerms[$0.id]?.contains(term) == true }
    }

    private static func fold(_ text: String) -> String {
        let folded = text.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US_POSIX"))
        return folded.compactMap { c -> String? in
            if c == "ـ" { return nil }
            if "أإآ".contains(c) { return "ا" }
            if c == "ى" { return "ي" }
            if let digit = c.wholeNumberValue { return String(digit) }
            return String(c)
        }.joined().split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }
}

extension IPTVChannel {
    private static let countryTags = Set(Locale.Region.isoRegions.map(\.identifier)).union(["UK"]).subtracting(["SD"])
    public var televisionTitle: String {
        let trim = CharacterSet.whitespacesAndNewlines
        let original = name.trimmingCharacters(in: trim)
        var value = original
        for _ in 0..<4 {
            let start = value.startIndex
            guard start < value.endIndex else { break }
            let bracket = value[start] == "[" || value[start] == "("
            let end = bracket ? value.firstIndex(of: value[start] == "[" ? "]" : ")") : value.firstIndex(where: { $0 == ":" || $0 == "|" })
            guard let end else { break }
            let prefix = value[(bracket ? value.index(after: start) : start)..<end].trimmingCharacters(in: trim)
            guard !prefix.isEmpty, prefix.caseInsensitiveCompare(group.trimmingCharacters(in: trim)) == .orderedSame || Self.countryTags.contains(prefix.uppercased()) else { break }
            var rest = value[value.index(after: end)...].trimmingCharacters(in: trim)
            if bracket && (rest.first == ":" || rest.first == "|") { rest = rest.dropFirst().trimmingCharacters(in: trim) }
            guard !rest.isEmpty else { return original }
            value = rest
        }
        return value.isEmpty ? "قناة" : value
    }
}
