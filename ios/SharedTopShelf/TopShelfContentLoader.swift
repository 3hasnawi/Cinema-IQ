import Foundation
import TVServices
import CinemaCore

enum TVShelfContentLoader {
    static func load() async -> (any TVTopShelfContent)? {
        if let snapshot = TVShelfStorage.read(TVShelfStorage.sharedURL), !snapshot.entries.isEmpty {
            return TVShelfRenderer.content(snapshot)
        }
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("public-featured-v1.json")
        if let snapshot = TVShelfStorage.read(cache), Date().timeIntervalSince(snapshot.updatedAt) < 900 {
            return TVShelfRenderer.content(snapshot)
        }
        let result = await withTaskGroup(of: (Bool, TopShelfSnapshot?).self) { tasks in
            for source in ContentSource.builtIn {
                tasks.addTask {
                    guard let shelves = try? await SourceClient().home(source, prioritizeFeaturedMovies: true) else { return (false, nil) }
                    let items = FeaturedMovies.select(from: shelves).compactMap { TopShelfEntry(media: $0) }
                    guard !items.isEmpty else { return (false, nil) }
                    return (false, TopShelfSnapshot(context: UUID(), source: source.rawValue, continuing: [], featured: items))
                }
            }
            tasks.addTask { try? await Task.sleep(for: .seconds(8)); return (true, nil) }
            // Cancellation is propagated to URLSession. Take the first usable source or the eight-second deadline.
            for await (deadline, value) in tasks {
                if let value { tasks.cancelAll(); return value }
                if deadline { tasks.cancelAll(); return nil }
            }
            return nil
        }
        if let result {
            if let cache { try? TVShelfStorage.write(result, to: cache) }
            return TVShelfRenderer.content(result)
        }
        return TVShelfStorage.read(cache).flatMap(TVShelfRenderer.content)
    }
}
