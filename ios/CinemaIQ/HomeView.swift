import SwiftUI
import CinemaCore

/// Featured titles first (they change by themselves every six seconds), then continue watching, then the
/// source's shelves.
struct HomeView: View {
    @Environment(\.contentLayout) private var layout
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @EnvironmentObject private var cloud: CloudSync
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var shelves: [Shelf] = []
    @State private var loading = false
    @State private var error: String?
    @State private var heroIndex = 0
    @State private var loadID = UUID()
    /// The source whose shelves are on screen. Coming back from a title page re-runs `.task`; the page must
    /// then stay as it was (same rows, same scroll position) instead of reloading from the top.
    @State private var loadedSource: String?
    private var source: ContentSource { SourcePreference.source(sourceName) }
    private var featured: [Media] { FeaturedMovies.select(from: shelves, limit: 6) }
    private var recent: [Media] { library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source } }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 26) {
                if featured.isEmpty {
                    Rectangle().fill(Theme.panel.opacity(loading ? 1 : 0))
                        .frame(height: loading ? layout.heroHeight : 110)
                        .overlay { if loading { ProgressView() } }
                } else {
                    HeroCarousel(items: featured, index: $heroIndex)
                }
                if !recent.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "تابع المشاهدة")
                        ScrollView(.horizontal) {
                            LazyHStack(spacing: 12) {
                                ForEach(recent) { media in
                                    ContinueCard(media: media, fraction: library.fraction(media),
                                                 action: { player.play(media) }, remove: { library.remove(media) })
                                }
                            }
                            .scrollTargetLayout()
                            .padding(.horizontal, layout.sideInset)
                        }
                        .scrollTargetBehavior(.viewAligned)
                        .scrollIndicators(.hidden)
                    }
                }
                ForEach(shelves) { shelf in ShelfSection(shelf: shelf) }
                if let error, shelves.isEmpty {
                    ErrorState(message: error) { Task { await load() } }
                }
            }
            .padding(.bottom, 24)
        }
        .background(Theme.canvas)
        .accessibilityIdentifier("ios.home")
        .ignoresSafeArea(edges: .top)
        // No bar over the featured artwork; the source is chosen in Settings (and in Browse and Search).
        .toolbar(.hidden, for: .navigationBar)
        .task(id: sourceName) {
            guard loadedSource != sourceName || shelves.isEmpty else { return }
            if loadedSource != sourceName { shelves = []; heroIndex = 0 }
            await load()
        }
        .refreshable { await cloud.pull(); await load() }
    }

    private func load() async {
        let request = UUID(), requestedSource = sourceName
        loadID = request; loading = true; error = nil
        defer { if loadID == request { loading = false } }
        do {
            let result = try await SourceClient().home(SourcePreference.source(requestedSource), prioritizeFeaturedMovies: true)
            try Task.checkCancellation()
            guard loadID == request, sourceName == requestedSource else { return }
            heroIndex = min(heroIndex, max(0, FeaturedMovies.select(from: result, limit: 6).count - 1))
            shelves = result
            loadedSource = requestedSource
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled, loadID == request, sourceName == requestedSource { self.error = error.localizedDescription }
        }
    }
}

struct HeroCarousel: View {
    @Environment(\.contentLayout) private var layout
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var player: PlayerPresenter
    @State private var visible = false
    let items: [Media]
    @Binding var index: Int
    private var canRotate: Bool { visible && phase == .active && !reduceMotion && player.selection == nil && items.count > 1 }
    private var rotationKey: String { "\(canRotate)|\(index)|" + items.map(\.id).joined(separator: ",") }
    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(items.enumerated()), id: \.element.id) { offset, media in
                HeroPage(media: media, routeKey: "hero-\(media.id)").tag(offset)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .frame(height: layout.heroHeight)
        .accessibilityIdentifier("ios.home.hero")
        .overlay(alignment: .bottom) {
            if items.count > 1 { PageDots(count: items.count, index: index).padding(.bottom, 2) }
        }
        .onAppear { visible = true }
        .onDisappear { visible = false }
        .onScrollVisibilityChange(threshold: 0.5) { visible = $0 }
        // A manual swipe restarts the full interval. Hidden pages do not advance behind playback or navigation.
        .task(id: rotationKey) {
            guard canRotate else { return }
            let selected = index, identities = items.map(\.id)
            do { try await Task.sleep(for: .seconds(6)) } catch { return }
            guard !Task.isCancelled, canRotate, index == selected, items.map(\.id) == identities else { return }
            withAnimation(.smooth(duration: 0.6)) { index = (index + 1) % items.count }
        }
    }
}

struct HeroPage: View {
    @Environment(\.contentLayout) private var layout
    let media: Media
    let routeKey: String
    @State private var omdb: OMDbInfo?
    @State private var tmdb: TMDBWork?
    @State private var omdbID = ""
    @State private var tmdbID = ""
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @Environment(\.zoomNamespace) private var zoom
    var body: some View {
        ZStack(alignment: .bottom) {
            HeroArtwork(url: heroImage, fallbackURL: TVArtworkSelection.providerFallback(for: media, excluding: heroImage))
            LinearGradient(stops: [
                .init(color: .clear, location: 0.3),
                .init(color: Theme.ink.opacity(0.7), location: 0.68),
                .init(color: Theme.ink, location: 1)
            ], startPoint: .top, endPoint: .bottom)
            VStack(spacing: 10) {
                Text(media.isSeries ? "مسلسل" : "فيلم")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .glass(in: Capsule())
                Text(media.title)
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                MetaLine(media: media, imdb: currentOMDb?.imdbRating)
                // Compact single-line actions: the play button carries the text, the others are icons.
                HStack(spacing: 12) {
                    if media.isSeries {
                        NavigationLink(value: DetailRoute(media: media, key: routeKey)) { playLabel }
                            .primaryAction()
                    } else {
                        Button { player.play(media) } label: { playLabel }
                            .primaryAction()
                    }
                    NavigationLink(value: DetailRoute(media: media, key: routeKey)) {
                        Image(systemName: "info").font(.body.weight(.semibold)).frame(width: 20, height: 20)
                    }
                    .secondaryAction()
                    .buttonBorderShape(.circle)
                    .accessibilityLabel("التفاصيل")
                    .accessibilityIdentifier("ios.home.detail.\(media.id)")
                    Button { library.toggle(media) } label: {
                        Image(systemName: library.isFavorite(media) ? "checkmark" : "plus")
                            .font(.body.weight(.semibold)).frame(width: 20, height: 20)
                    }
                    .secondaryAction()
                    .buttonBorderShape(.circle)
                    .accessibilityLabel(library.isFavorite(media) ? "إزالة من قائمتي" : "إضافة إلى قائمتي")
                }
                .controlSize(.regular)
            }
            .frame(maxWidth: layout.readableWidth)
            .padding(.horizontal, 20)
            .padding(.bottom, 36)
        }
        .zoomSource(routeKey, in: zoom)
        .task(id: media.id) {
            let requested = media
            let found = await OMDb.shared?.info(for: requested)
            guard !Task.isCancelled, media.id == requested.id else { return }
            omdb = found; omdbID = requested.id
        }
        // Artwork does not depend on OMDb availability or its request completing first.
        .task(id: media.id) {
            let requested = media
            let found = await TMDB.shared?.work(for: requested)
            guard !Task.isCancelled, media.id == requested.id else { return }
            tmdb = found; tmdbID = requested.id
        }
    }
    private var currentOMDb: OMDbInfo? { omdbID == media.id ? omdb : nil }
    private var heroImage: String {
        TVArtworkSelection.url(for: media, tmdb: tmdbID == media.id ? tmdb : nil, fallbackPoster: currentOMDb?.largePoster ?? "")
    }
}

private extension HeroPage {
    var playLabel: some View {
        Label("شاهد", systemImage: "play.fill")
            .font(.subheadline.weight(.bold))
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 6)
    }
}

struct ShelfSection: View {
    @Environment(\.contentLayout) private var layout
    let shelf: Shelf
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: shelf.title)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(shelf.items) { media in
                        PosterCard(media: media, routeKey: "\(shelf.id)-\(media.id)", width: layout.posterWidth)
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, layout.sideInset)
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
        }
    }
}
