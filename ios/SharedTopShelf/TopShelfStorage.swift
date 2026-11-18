import Foundation
import CinemaCore

/// The extension sees one small, atomic snapshot of the active profile, not the app's private preferences.
enum TVShelfStorage {
    static let fileName = "top-shelf-v1.json"
    static var sharedURL: URL? {
        guard let group = TVShelfConfiguration.groupIdentifier else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent(fileName)
    }
    static func read(_ url: URL?) -> TopShelfSnapshot? {
        guard let url, let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 128 * 1024,
              let data = try? Data(contentsOf: url), let value = try? JSONDecoder().decode(TopShelfSnapshot.self, from: data),
              value.usable() else { return nil }
        return value
    }
    static func write(_ value: TopShelfSnapshot, to url: URL) throws {
        let data = try JSONEncoder().encode(value)
        guard data.count <= 128 * 1024 else { return }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        var file = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? file.setResourceValues(values)
    }
}

actor TVShelfWriter {
    private var lastSequence = 0
    func write(_ value: TopShelfSnapshot, sequence: Int) -> Bool {
        guard sequence >= lastSequence, let url = TVShelfStorage.sharedURL else { return false }
        lastSequence = sequence
        do { try TVShelfStorage.write(value, to: url); return true } catch { return false }
    }
}
