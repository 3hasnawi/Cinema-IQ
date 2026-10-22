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

@MainActor final class LibraryStore: ObservableObject {
    @Published private(set) var favorites: [Media] = []
    @Published private(set) var progress: [String: WatchProgress] = [:]
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: "ios.local.favorites.v1"), let value = try? JSONDecoder().decode([Media].self, from: data) { favorites = value }
        if let data = defaults.data(forKey: "ios.local.progress.v1"), let value = try? JSONDecoder().decode([String: WatchProgress].self, from: data) { progress = value }
    }
    var recent: [Media] {
        var groups = Set<String>()
        return progress.values.sorted { $0.updatedAt > $1.updatedAt }.filter { !$0.completed && $0.seconds > 0 }.compactMap {
            groups.insert($0.media.recentKey).inserted ? $0.media : nil
        }
    }
    var watched: [Media] { progress.values.filter(\.completed).sorted { $0.updatedAt > $1.updatedAt }.map(\.media) }
    func toggle(_ media: Media) {
        if favorites.contains(where: { $0.id == media.id }) { favorites.removeAll { $0.id == media.id } }
        else { favorites.insert(media, at: 0) }
        defaults.set(try? JSONEncoder().encode(favorites), forKey: "ios.local.favorites.v1")
    }
    func save(_ media: Media, seconds: Double, duration: Double, completed: Bool = false) {
        guard seconds.isFinite, duration.isFinite, seconds >= 0, duration > 0 else { return }
        progress[media.id] = WatchProgress(media: media, seconds: min(seconds, duration), duration: duration, completed: completed, updatedAt: Date())
        defaults.set(try? JSONEncoder().encode(progress), forKey: "ios.local.progress.v1")
    }
}
