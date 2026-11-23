import SwiftUI
import CinemaCore

/// Featured titles first (they change by themselves every six seconds), then continue watching, then the
/// source's shelves.
struct HomeView: View {
    let isActive: Bool
    let openSettings: () -> Void
    let openLibrary: () -> Void
    @Environment(\.contentLayout) private var layout
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
    @State private var featured: [Media] = []

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 26) {
                if featured.isEmpty {
                    Rectangle().fill(Theme.panel.opacity(loading ? 1 : 0))
                        .frame(height: loading ? layout.heroHeight : 110)
                        .overlay { if loading { ProgressView() } }
                } else {
                    HeroCarousel(items: featured, index: $heroIndex, isActive: isActive)
                        .clipShape(RoundedRectangle(cornerRadius: layout.isTablet ? 20 : 0, style: .continuous))
                        .padding(.horizontal, layout.isTablet ? layout.sideInset : 0)
                }
                ContinueWatchingShelf(source: source, openLibrary: openLibrary)
                ForEach(shelves) { shelf in ShelfSection(shelf: shelf) }
                if let error, shelves.isEmpty {
                    ErrorState(message: error) { Task { await load() } }
                }
            }
            .padding(.bottom, 24)
        }
        .background(Theme.canvas)
        .accessibilityIdentifier("ios.home")
        .safeAreaInset(edge: .top, spacing: 0) {
            HStack {
                if layout.isTablet {
                    Text("الرئيسية").font(.headline)
                } else {
                    CinemaWordmark()
                }
                Spacer()
                Button(action: openSettings) {
                    Group {
                        if let initial = cloud.activeProfile?.name.first {
                            Text(String(initial)).font(.headline)
                        } else {
                            Image(systemName: "person.crop.circle").font(.title3)
                        }
                    }
                    .frame(width: 44, height: 44)
                    .glass(in: Circle(), interactive: true)
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("الملف الشخصي والإعدادات")
                .accessibilityIdentifier("ios.home.profile")
            }
            .padding(.horizontal, layout.sideInset)
            .padding(.vertical, 5)
            .background(Theme.ink)
        }
        .toolbar(.hidden, for: .navigationBar)
        .task(id: sourceName) {
            guard loadedSource != sourceName || shelves.isEmpty else { return }
            if loadedSource != sourceName { shelves = []; featured = []; heroIndex = 0 }
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
            let selected = FeaturedMovies.select(from: result, limit: 6)
            heroIndex = min(heroIndex, max(0, selected.count - 1))
            featured = selected
            shelves = result
            loadedSource = requestedSource
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled, loadID == request, sourceName == requestedSource { self.error = error.localizedDescription }
        }
    }
}

private struct ContinueWatchingShelf: View {
    let source: ContentSource
    let openLibrary: () -> Void
    @Environment(\.contentLayout) private var layout
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter

    var body: some View {
        let recent = library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source }
        if !recent.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("متابعة المشاهدة").font(.title3.bold())
                    Spacer()
                    Button("مكتبتي", action: openLibrary).font(.subheadline.weight(.semibold))
                        .frame(minHeight: 44)
                }
                .padding(.horizontal, layout.sideInset)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(recent) { media in
                            ContinueCard(media: media, progress: library.progress[media.id],
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
    }
}

struct HeroCarousel: View {
    @Environment(\.contentLayout) private var layout
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var player: PlayerPresenter
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var visible = false
    let items: [Media]
    @Binding var index: Int
    let isActive: Bool
    private var canRotate: Bool {
#if DEBUG
        // A pixel/navigation fixture must stay still while XCTest waits for the app to idle.
        if HeroArtwork.fixtureEnabled || ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-static-carousel") { return false }
#endif
        return isActive && visible && phase == .active && !reduceMotion && !voiceOver && player.selection == nil && items.count > 1
    }
    private var rotationKey: String { "\(canRotate)|\(index)|" + items.map(\.id).joined(separator: ",") }
    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(items.enumerated()), id: \.element.id) { offset, media in
                HeroPage(media: media, routeKey: "hero-\(media.id)").tag(offset)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .frame(height: textSize.isAccessibilitySize ? max(layout.heroHeight, 410) : layout.heroHeight)
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
        ZStack(alignment: .bottomLeading) {
            HeroArtwork(urls: artworkCandidates)
            LinearGradient(stops: [
                .init(color: .clear, location: 0.18),
                .init(color: .black.opacity(0.45), location: 0.58),
                .init(color: Theme.ink.opacity(0.96), location: 1)
            ], startPoint: .top, endPoint: .bottom)
            .allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 6) {
                if !media.enTitle.isEmpty && media.title.caseInsensitiveCompare(media.enTitle) != .orderedSame {
                    Text(media.enTitle.uppercased())
                        .font(.caption.weight(.semibold)).tracking(1.4)
                        .foregroundStyle(Theme.accent).lineLimit(1)
                        .environment(\.layoutDirection, .leftToRight)
                }
                Text(media.title)
                    .font(.largeTitle.bold()).lineLimit(2)
                    .minimumScaleFactor(0.8)
                MetaLine(media: media, imdb: currentOMDb?.imdbRating)
                HStack(spacing: 10) {
                    NavigationLink(value: DetailRoute(media: media, key: routeKey)) {
                        Label("عرض التفاصيل", systemImage: "film")
                            .font(.subheadline.bold()).foregroundStyle(Theme.ink)
                            .padding(.horizontal, 16).frame(minHeight: 44)
                            .background(.white, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("ios.home.detail.\(media.id)")
                    .contextMenu {
                        if !media.isSeries {
                            Button { player.play(media) } label: { Label("تشغيل", systemImage: "play.fill") }
                        }
                    }
                    Button { library.toggle(media) } label: {
                        Image(systemName: library.isFavorite(media) ? "checkmark" : "plus")
                            .font(.body.weight(.semibold)).frame(width: 44, height: 44)
                            .glass(in: Circle(), interactive: true).contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(library.isFavorite(media) ? "إزالة من قائمتي" : "إضافة إلى قائمتي")
                    .accessibilityIdentifier("ios.home.favorite.\(media.id)")
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, layout.sideInset)
            .padding(.bottom, 32)
        }
        .zoomSource(routeKey, in: zoom)
        .task(id: media.id) {
#if DEBUG
            guard !HeroArtwork.fixtureEnabled else { return }
#endif
            let requested = media
            guard omdbID != requested.id || omdb == nil else { return }
            let found = await OMDb.shared?.info(for: requested)
            guard !Task.isCancelled, media.id == requested.id else { return }
            omdb = found; omdbID = requested.id
        }
        // Artwork does not depend on OMDb availability or its request completing first.
        .task(id: media.id) {
#if DEBUG
            guard !HeroArtwork.fixtureEnabled else { return }
#endif
            let requested = media
            guard tmdbID != requested.id || tmdb == nil else { return }
            let found = await TMDB.shared?.work(for: requested)
            guard !Task.isCancelled, media.id == requested.id else { return }
            tmdb = found; tmdbID = requested.id
        }
    }
    private var currentOMDb: OMDbInfo? { omdbID == media.id ? omdb : nil }
    private var artworkCandidates: [String] {
        let format: FeaturedArtworkFormat = layout.usesPortraitHero ? .poster : .backdrop
        return format.candidates(for: media, tmdb: tmdbID == media.id ? tmdb : nil, fallbackPoster: currentOMDb?.largePoster ?? "")
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
