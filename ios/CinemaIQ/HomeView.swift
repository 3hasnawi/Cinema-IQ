import SwiftUI
import CinemaCore

/// Featured titles first (they change by themselves every six seconds), then continue watching, then the
/// source's shelves.
struct HomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var shelves: [Shelf] = []
    @State private var loading = false
    @State private var error: String?
    @State private var heroIndex = 0
    private var source: ContentSource { SourcePreference.source(sourceName) }
    private var featured: [Media] {
        var seen = Set<String>()
        let all = shelves.flatMap(\.items).filter { seen.insert($0.id).inserted }
        return Array(all.filter { !$0.poster.isEmpty || !$0.backdrop.isEmpty }.prefix(6))
    }
    private var recent: [Media] { library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source } }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 26) {
                if featured.isEmpty {
                    Rectangle().fill(Theme.panel.opacity(loading ? 1 : 0))
                        .frame(height: loading ? 560 : 110)
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
                            .padding(.horizontal, 18)
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
        .background(Theme.ink)
        .ignoresSafeArea(edges: .top)
        // No bar over the featured artwork; the source is chosen in Settings (and in Browse and Search).
        .toolbar(.hidden, for: .navigationBar)
        .task(id: sourceName) {
            shelves = []; heroIndex = 0
            await load()
        }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let result = try await SourceClient().home(source)
            try Task.checkCancellation()
            shelves = result
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled { self.error = error.localizedDescription }
        }
    }
}

struct HeroCarousel: View {
    let items: [Media]
    @Binding var index: Int
    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(items.enumerated()), id: \.element.id) { offset, media in
                HeroPage(media: media, routeKey: "hero-\(media.id)").tag(offset)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .frame(height: 560)
        .overlay(alignment: .bottom) {
            if items.count > 1 { PageDots(count: items.count, index: index).padding(.bottom, 2) }
        }
        // Restarted by every change, so a manual swipe also gets its full six seconds.
        .task(id: index) {
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled, items.count > 1 else { return }
            withAnimation(.smooth(duration: 0.6)) { index = (index + 1) % items.count }
        }
    }
}

struct HeroPage: View {
    let media: Media
    let routeKey: String
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @Environment(\.zoomNamespace) private var zoom
    var body: some View {
        ZStack(alignment: .bottom) {
            Color.clear
                .overlay { Artwork(url: media.backdrop.isEmpty ? media.poster : media.backdrop) }
                .clipped()
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
                MetaLine(media: media)
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
            .padding(.horizontal, 20)
            .padding(.bottom, 36)
        }
        .zoomSource(routeKey, in: zoom)
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
    let shelf: Shelf
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: shelf.title)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(shelf.items) { media in
                        PosterCard(media: media, routeKey: "\(shelf.id)-\(media.id)")
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, 18)
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
        }
    }
}
