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

/// The viewer's list, continue watching, progress and search history.
///
/// Signed out, everything stays on this iPhone under the original `ios.local.*.v1` keys. Signed in with a profile,
/// the same data lives under that account and profile and every change is handed to the cloud sync as Android
/// records; the device's own data is kept apart and comes back after signing out.
@MainActor final class LibraryStore: ObservableObject {
    @Published private(set) var favorites: [Media] = []
    @Published private(set) var progress: [String: WatchProgress] = [:]
    @Published private(set) var recent: [Media] = []
    @Published private(set) var searches: [String] = []
    /// Receives changed cloud records (key → value, empty = removed) while an account profile is active.
    var recordSink: (([String: String]) -> Void)?
    private let defaults: UserDefaults
    private var prefix = "ios.local"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        searches = defaults.stringArray(forKey: "ios.local.searches.v1") ?? []
        load()
    }

    /// nil = this device's library; otherwise "<uid>.<profile>".
    func switchTo(account: String?) {
        let next = account.map { "ios.cloud.\($0)" } ?? "ios.local"
        guard next != prefix else { return }
        prefix = next
        load()
    }

    var watched: [Media] {
        progress.values.filter { $0.completed && !$0.media.title.isEmpty }.sorted { $0.updatedAt > $1.updatedAt }.map(\.media)
    }
    func isFavorite(_ media: Media) -> Bool { favorites.contains { $0.id == media.id } }
    func fraction(_ media: Media) -> Double {
        guard let entry = progress[media.id], entry.duration > 0 else { return 0 }
        return entry.completed ? 1 : entry.seconds / entry.duration
    }

    func toggle(_ media: Media) {
        let adding = !isFavorite(media)
        if adding { favorites.insert(media, at: 0) } else { favorites.removeAll { $0.id == media.id } }
        persist()
        recordSink?(["favorite/\(media.id)": adding ? CloudLibraryCodec.filmJSON(media) : ""])
    }

    /// Saves the position. A finished episode hands continue watching to [next] (the following episode), or
    /// takes the series off it when there is none, as on Android.
    func save(_ media: Media, seconds: Double, duration: Double, completed: Bool = false, next: Media? = nil) {
        guard seconds.isFinite, duration.isFinite, seconds >= 0, duration > 0 else { return }
        progress[media.id] = WatchProgress(media: media, seconds: min(seconds, duration), duration: duration, completed: completed, updatedAt: Date())
        var records = ["playback/\(media.id)": CloudLibraryCodec.playbackJSON(
            .init(seconds: min(seconds, duration), duration: duration, completed: completed, watched: completed ? media : nil))]
        let key = media.recentKey
        if !completed && seconds > 0 {
            // The newest episode of a series replaces the older one, as on Android.
            recent.removeAll { $0.recentKey == key }
            recent.insert(media, at: 0)
            records[CloudLibraryCodec.recentKey(media)] = CloudLibraryCodec.filmJSON(media)
        } else if completed, media.episode > 0, var following = next {
            if following.seriesID.isEmpty { following.seriesID = media.seriesID }
            recent.removeAll { $0.recentKey == key || $0.recentKey == following.recentKey }
            recent.insert(following, at: 0)
            records[CloudLibraryCodec.recentKey(following)] = CloudLibraryCodec.filmJSON(following)
            if following.recentKey != key { records[CloudLibraryCodec.recentKey(media)] = "" }
        } else if completed {
            recent.removeAll { $0.recentKey == key }
            records[CloudLibraryCodec.recentKey(media)] = ""
        }
        persist()
        recordSink?(records)
    }

    /// Takes a title (and its series) off continue watching; finished history stays.
    func remove(_ media: Media) {
        recent.removeAll { $0.recentKey == media.recentKey }
        persist()
        recordSink?([CloudLibraryCodec.recentKey(media): ""])
    }

    /// Replaces the active profile's library with the account's records (local unsent edits already applied).
    func applyRemote(_ records: [CloudRecord]) {
        // External providers' items are not synced (Android does not know them); they are kept from this iPhone.
        let localFavorites = favorites.filter(\.isExternal), localRecent = recent.filter(\.isExternal)
        let localProgress = progress.filter { $0.key.hasPrefix("ext:") }
        let ordered = records.sorted { ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }
        favorites = ordered.filter { $0.key.hasPrefix("favorite/") }.compactMap { CloudLibraryCodec.film($0.value) } + localFavorites
        recent = ordered.filter { $0.key.hasPrefix("recent/") }.compactMap { CloudLibraryCodec.film($0.value) } + localRecent
        var known: [String: Media] = [:]
        for media in favorites + recent { known[media.id] = media }
        var next: [String: WatchProgress] = [:]
        for record in ordered where record.key.hasPrefix("playback/") {
            guard let playback = CloudLibraryCodec.playback(record.value), playback.duration > 0 else { continue }
            let id = String(record.key.dropFirst("playback/".count))
            let media = playback.watched ?? known[id] ?? progress[id]?.media ?? Media(id: id, title: "")
            next[id] = WatchProgress(media: media, seconds: playback.seconds, duration: playback.duration,
                                     completed: playback.completed, updatedAt: record.updatedAt ?? Date())
        }
        progress = next.merging(localProgress) { remote, _ in remote }
        // Continue watching in order of the last watch, local and synced items together.
        if !localRecent.isEmpty { recent.sort { (progress[$0.id]?.updatedAt ?? .distantPast) > (progress[$1.id]?.updatedAt ?? .distantPast) } }
        persist()
    }

    /// Everything of the active profile as cloud records, for the first upload of a device library.
    func allRecords() -> [String: String] {
        var records: [String: String] = [:]
        for media in favorites { records["favorite/\(media.id)"] = CloudLibraryCodec.filmJSON(media) }
        for media in recent { records[CloudLibraryCodec.recentKey(media)] = CloudLibraryCodec.filmJSON(media) }
        for (id, entry) in progress {
            records["playback/\(id)"] = CloudLibraryCodec.playbackJSON(.init(seconds: entry.seconds, duration: entry.duration,
                                                                           completed: entry.completed, watched: entry.completed ? entry.media : nil))
        }
        return records
    }

    /// The device library (signed out), for copying into an account.
    func deviceRecords() -> [String: String] {
        let current = prefix
        prefix = "ios.local"; let saved = (favorites, progress, recent); load()
        let records = allRecords()
        prefix = current; (favorites, progress, recent) = saved
        return records
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

    private func load() {
        favorites = (defaults.data(forKey: "\(prefix).favorites.v1")).flatMap { try? JSONDecoder().decode([Media].self, from: $0) } ?? []
        progress = (defaults.data(forKey: "\(prefix).progress.v1")).flatMap { try? JSONDecoder().decode([String: WatchProgress].self, from: $0) } ?? [:]
        if let data = defaults.data(forKey: "\(prefix).recent.v1"), let list = try? JSONDecoder().decode([Media].self, from: data) {
            recent = list
        } else {
            // First run after the update: continue watching used to be worked out from progress.
            var groups = Set<String>()
            recent = progress.values.sorted { $0.updatedAt > $1.updatedAt }.filter { !$0.completed && $0.seconds > 0 }
                .compactMap { groups.insert($0.media.recentKey).inserted ? $0.media : nil }
        }
    }
    private func persist() {
        defaults.set(try? JSONEncoder().encode(favorites), forKey: "\(prefix).favorites.v1")
        defaults.set(try? JSONEncoder().encode(progress), forKey: "\(prefix).progress.v1")
        defaults.set(try? JSONEncoder().encode(recent), forKey: "\(prefix).recent.v1")
    }
}
