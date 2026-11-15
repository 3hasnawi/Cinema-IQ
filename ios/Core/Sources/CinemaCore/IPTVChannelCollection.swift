import Foundation

public enum IPTVChannelSection: Hashable, Sendable {
    case all, recent, favorites, group(String)
    public var title: String { switch self { case .all: return "كل القنوات"; case .recent: return "الأخيرة"; case .favorites: return "المفضلة"; case .group(let name): return name } }
    public var group: String? { if case .group(let name) = self { return name }; return nil }
}

/// Device-local channel IDs only: subscription URLs and credentials never enter this record.
public struct IPTVChannelCollection: Codable, Equatable, Sendable {
    public private(set) var favorites: Set<String> = []
    public private(set) var recent: [String] = []
    public init() {}
    private enum CodingKeys: String, CodingKey { case favorites, recent }
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let savedFavorites = try container.decodeIfPresent([String].self, forKey: .favorites) ?? []
        favorites = Set(savedFavorites.filter { !$0.isEmpty && $0.count <= 512 }.prefix(2000))
        let savedRecent = try container.decodeIfPresent([String].self, forKey: .recent) ?? []
        var seen = Set<String>()
        recent = Array(savedRecent.filter { !$0.isEmpty && $0.count <= 512 && seen.insert($0).inserted }.prefix(100))
    }
    public mutating func watched(_ id: String) {
        guard !id.isEmpty, id.count <= 512 else { return }
        recent.removeAll { $0 == id }; recent.insert(id, at: 0)
        if recent.count > 100 { recent.removeLast(recent.count - 100) }
    }
    public mutating func toggle(_ id: String) {
        guard !id.isEmpty, id.count <= 512 else { return }
        if favorites.remove(id) == nil, favorites.count < 2000 { favorites.insert(id) }
    }
    public func channels(_ values: [IPTVChannel], sourceID: String, section: IPTVChannelSection, query: String = "") -> [IPTVChannel] {
        let values = IPTVNavigation.channels(values, sourceID: sourceID)
        let selected: [IPTVChannel]
        switch section {
        case .all: selected = values
        case .group(let group): selected = values.filter { $0.group == group }
        case .favorites: selected = values.filter { favorites.contains($0.id) }
        case .recent:
            let index = Dictionary(values.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            selected = recent.compactMap { index[$0] }
        }
        return IPTVNavigation.channels(selected, sourceID: sourceID, query: query)
    }
}
