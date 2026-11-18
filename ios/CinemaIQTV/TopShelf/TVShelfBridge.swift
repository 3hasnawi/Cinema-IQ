import SwiftUI
import TVServices
import CinemaCore

/// Owns the main-app side only. Providers' tokens and playable URLs never enter the extension snapshot.
@MainActor final class TVShelfBridge: ObservableObject {
    @Published var opening = false
    @Published var problem: String?
    @Published var audit = ""
    private weak var library: LibraryStore?
    private let writer = TVShelfWriter()
    private var scope = ""
    private var context = UUID()
    private var source = SourcePreference.fallback
    private var featured: [Media] = []
    private var artwork: [String: String] = [:]
    private var sequence = 0
    private var refreshAt = Date.distantPast
    private var routeTask: Task<Void, Never>?
    private var request = UUID()
    var attached: Bool { library != nil }

    func attach(_ library: LibraryStore, source: ContentSource) {
        self.library = library; self.source = source
        publish()
    }
    func publish() {
        guard let library else { return }
        if scope != library.topShelfScope { scope = library.topShelfScope; context = UUID(); artwork = [:] }
        let recent = library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source }
        let entries = recent.compactMap { media -> TopShelfEntry? in
            if library.progress[media.id]?.completed == true { return nil }
            return entry(media, progress: library.fraction(media))
        }
        let snapshot = TopShelfSnapshot(context: context, source: source.rawValue,
                                        continuing: Array(entries.prefix(8)), featured: Array(featured.compactMap { entry($0) }.prefix(8)))
        sequence += 1
        let current = sequence
        Task {
            if await writer.write(snapshot, sequence: current), sequence == current {
                TVTopShelfContentProvider.topShelfContentDidChange()
            }
        }
    }
    private func entry(_ media: Media, progress: Double = 0) -> TopShelfEntry? {
        let backdrop = artwork[media.id] ?? ""
        return TopShelfEntry(media: media, seconds: progress, duration: 1, landscapeURL: backdrop)
    }
    func refresh(_ selected: ContentSource) async {
        guard TVShelfStorage.sharedURL != nil else { return }
        if source != selected { source = selected; featured = []; artwork = [:]; refreshAt = .distantPast; publish() }
        guard Date().timeIntervalSince(refreshAt) > 900 else { return }
        let requestedScope = scope, requestedContext = context
        refreshAt = Date()
        do {
            let shelves = try await SourceClient().home(selected, prioritizeFeaturedMovies: true)
            guard !Task.isCancelled, source == selected, scope == requestedScope, context == requestedContext else { return }
            featured = FeaturedMovies.select(from: shelves)
            publish()
            // Enrich the small visible set through the existing cached TMDB client.
            for media in Array((library?.recent ?? []) + featured).prefix(12) {
                guard !Task.isCancelled, source == selected, context == requestedContext else { return }
                if let work = await TMDB.shared?.work(for: media), let image = TopShelfEntry.publicImage(work.backdrop) {
                    guard !Task.isCancelled, source == selected, context == requestedContext else { return }
                    artwork[media.id] = image.absoluteString
                }
            }
            publish()
        } catch {
            if !Task.isCancelled { refreshAt = Date().addingTimeInterval(-840) }
        }
    }

    func open(_ url: URL, router: TVRouter, player: PlayerPresenter) {
        guard let route = TopShelfRoute(url: url), let library else { return }
        routeTask?.cancel()
        let token = UUID(); request = token; opening = true; problem = nil
        routeTask = Task { @MainActor in
            defer { if request == token { opening = false } }
            do {
                _ = try ContentSource.owner(of: route.mediaID)
                let known = library.recent.first { $0.id == route.mediaID } ?? library.favorites.first { $0.id == route.mediaID } ??
                    library.progress[route.mediaID]?.media ?? Media(id: route.mediaID, title: "")
                let (detail, seasons) = try await SourceClient().detail(known)
                try Task.checkCancellation()
                guard request == token else { return }
                router.popup = nil
                // A Home-screen link can arrive while a suspended movie cover is still presented.
                // Finish its dismissal before presenting another selection or displaying new details.
                if player.selection != nil {
                    player.selection = nil
                    try await Task.sleep(for: .milliseconds(600))
                    try Task.checkCancellation()
                    guard request == token else { return }
                }
                router.tab = .home
                router.paths[.home] = [detail]
                guard route.action == .play else { return }
                if detail.isSeries {
                    guard let target = PlayTarget.pick(seasons, library: library) else { return }
                    player.play(target, queue: seasons.flatMap(\.episodes), series: detail)
                } else if detail.episode > 0, !detail.seriesID.isEmpty {
                    let series = try await SourceClient().detail(Media(id: detail.seriesID, title: detail.title))
                    try Task.checkCancellation()
                    guard request == token else { return }
                    player.play(detail, queue: series.1.flatMap(\.episodes), series: series.0)
                } else { player.play(detail) }
            } catch {
                if !Task.isCancelled, request == token { problem = error.localizedDescription }
            }
        }
    }
    func cancelOpen() { request = UUID(); routeTask?.cancel(); routeTask = nil; opening = false }

#if DEBUG
    func seedAudit() async {
        guard ProcessInfo.processInfo.arguments.contains("--cinema-top-shelf-testing"), let library else { return }
        guard let shelves = try? await SourceClient().home(.external("demo")),
              let media = FeaturedMovies.select(from: shelves).first else { return }
        library.save(media, seconds: 25, duration: 600)
        featured = FeaturedMovies.select(from: shelves); publish()
        // Call the same loader/TVServices renderer used by the extension, not a drawn mock Home screen.
        try? await Task.sleep(for: .milliseconds(400))
        let snapshot = TVShelfStorage.read(TVShelfStorage.sharedURL)
        let content = snapshot == nil ? nil : await TVShelfContentLoader.load()
        audit = snapshot?.continuing.first?.mediaID == media.id && content != nil ? "shared-ready" : "shared-unavailable"
    }
#endif
}
