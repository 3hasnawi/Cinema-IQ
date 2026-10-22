import Foundation
import Combine
import CinemaCore

struct WatchProgress: Codable {
    var media: Media
    var seconds: Double
    var duration: Double
    var completed: Bool
    var updatedAt: Date
}

/// Device-local list, progress and search history. Keys are unchanged from the first preview.
@MainActor final class LibraryStore: ObservableObject {
    @Published private(set) var favorites: [Media] = []
    @Published private(set) var progress: [String: WatchProgress] = [:]
    @Published private(set) var searches: [String] = []
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: "ios.local.favorites.v1"), let value = try? JSONDecoder().decode([Media].self, from: data) { favorites = value }
        if let data = defaults.data(forKey: "ios.local.progress.v1"), let value = try? JSONDecoder().decode([String: WatchProgress].self, from: data) { progress = value }
        searches = defaults.stringArray(forKey: "ios.local.searches.v1") ?? []
    }
    var recent: [Media] {
        var groups = Set<String>()
        return progress.values.sorted { $0.updatedAt > $1.updatedAt }.filter { !$0.completed && $0.seconds > 0 }.compactMap {
            groups.insert($0.media.recentKey).inserted ? $0.media : nil
        }
    }
    var watched: [Media] { progress.values.filter(\.completed).sorted { $0.updatedAt > $1.updatedAt }.map(\.media) }
    func isFavorite(_ media: Media) -> Bool { favorites.contains { $0.id == media.id } }
    func fraction(_ media: Media) -> Double {
        guard let entry = progress[media.id], entry.duration > 0 else { return 0 }
        return entry.completed ? 1 : entry.seconds / entry.duration
    }
    func toggle(_ media: Media) {
        if isFavorite(media) { favorites.removeAll { $0.id == media.id } }
        else { favorites.insert(media, at: 0) }
        defaults.set(try? JSONEncoder().encode(favorites), forKey: "ios.local.favorites.v1")
    }
    func save(_ media: Media, seconds: Double, duration: Double, completed: Bool = false) {
        guard seconds.isFinite, duration.isFinite, seconds >= 0, duration > 0 else { return }
        progress[media.id] = WatchProgress(media: media, seconds: min(seconds, duration), duration: duration, completed: completed, updatedAt: Date())
        persistProgress()
    }
    /// Takes a title (all its unfinished episodes) off continue watching; finished history stays.
    func remove(_ media: Media) {
        let key = media.recentKey
        for (id, entry) in progress where entry.media.recentKey == key && !entry.completed { progress[id] = nil }
        persistProgress()
    }
    func remember(search: String) {
        let text = search.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= 2 else { return }
        searches.removeAll { $0 == text }
        searches.insert(text, at: 0)
        searches = Array(searches.prefix(8))
        defaults.set(searches, forKey: "ios.local.searches.v1")
    }
    func clearSearches() {
        searches = []
        defaults.removeObject(forKey: "ios.local.searches.v1")
    }
    private func persistProgress() {
        defaults.set(try? JSONEncoder().encode(progress), forKey: "ios.local.progress.v1")
    }
}
