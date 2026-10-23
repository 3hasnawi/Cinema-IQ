import SwiftUI
import CinemaCore

/// Home: the featured title over most of the screen (it changes every 8 s while focus is not on it), continue
/// watching under it, then the source's shelves.
struct TVHomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var shelves: [Shelf] = []
    @State private var loading = false
    @State private var error: String?
    @State private var heroIndex = 0
    @State private var loadedSource: String?
    @FocusState private var heroFocused: Bool
    private var source: ContentSource { SourcePreference.source(sourceName) }
    private var featured: [Media] {
        var seen = Set<String>()
        let all = shelves.flatMap(\.items).filter { seen.insert($0.id).inserted }
        return Array(all.filter { !$0.backdrop.isEmpty || !$0.poster.isEmpty }.prefix(8))
    }
    private var recent: [Media] { library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source } }

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 56) {
                if let hero = featured.indices.contains(heroIndex) ? featured[heroIndex] : featured.first {
                    TVHero(media: hero, count: featured.count, index: heroIndex, focused: $heroFocused)
                } else if loading {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 700)
                }
                if !recent.isEmpty {
                    TVShelf(title: "تابع المشاهدة") {
                        ForEach(recent) { media in ContinueCard(media: media) }
                    }
                }
                ForEach(shelves) { shelf in
                    TVShelf(title: shelf.title) {
                        ForEach(shelf.items) { media in PosterCard(media: media) }
                    }
                }
                if let error, shelves.isEmpty { ErrorState(message: error) { Task { await load() } } }
            }
            .padding(.bottom, 80)
        }
        .ignoresSafeArea(edges: .top)
        .background(TVTheme.ink.ignoresSafeArea())
        .task(id: sourceName) {
            guard loadedSource != sourceName || shelves.isEmpty else { return }
            if loadedSource != sourceName { shelves = []; heroIndex = 0 }
            await load()
        }
        // Rotation pauses while the viewer is on the featured title.
        .task(id: "\(heroIndex)-\(heroFocused)-\(featured.count)") {
            guard featured.count > 1, !heroFocused else { return }
            try? await Task.sleep(for: .seconds(8))
            guard !Task.isCancelled, !heroFocused else { return }
            withAnimation(.easeInOut(duration: 0.6)) { heroIndex = (heroIndex + 1) % featured.count }
        }
    }

    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let result = try await SourceClient().home(source)
            try Task.checkCancellation()
            shelves = result
            loadedSource = sourceName
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled { self.error = error.localizedDescription }
        }
    }
}

/// The featured title: full-width artwork, title and facts on the right, Play / Details / My List.
struct TVHero: View {
    let media: Media
    let count: Int
    let index: Int
    var focused: FocusState<Bool>.Binding
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Artwork(url: media.backdrop.isEmpty ? media.poster : media.backdrop)
                .frame(height: 860)
                .id(media.id)
                .transition(.opacity)
            LinearGradient(colors: [TVTheme.ink.opacity(0.92), TVTheme.ink.opacity(0.55), .clear],
                           startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.clear, .clear, TVTheme.ink], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 22) {
                Text(media.isSeries ? "مسلسل" : "فيلم")
                    .font(.caption.bold()).foregroundStyle(.black)
                    .padding(.horizontal, 14).padding(.vertical, 4)
                    .background(TVTheme.accent, in: RoundedRectangle(cornerRadius: 8))
                Text(media.title).font(.system(size: 76, weight: .heavy)).lineLimit(2)
                Text(DisplayFormat.facts(media).joined(separator: "  •  ")).font(.headline).foregroundStyle(.white.opacity(0.85))
                if !media.overview.isEmpty {
                    Text(media.overview).font(.body).foregroundStyle(.white.opacity(0.85)).lineLimit(2).frame(maxWidth: 900, alignment: .leading)
                }
                HStack(spacing: 24) {
                    if media.isSeries {
                        NavigationLink(value: media) { Label("الحلقات", systemImage: "play.fill") }
                            .focused(focused)
                    } else {
                        Button { player.play(media) } label: {
                            Label(library.fraction(media) > 0 ? "استئناف" : "شاهد الآن", systemImage: "play.fill")
                        }
                        .focused(focused)
                        NavigationLink(value: media) { Label("التفاصيل", systemImage: "info.circle") }
                    }
                    Button { library.toggle(media) } label: {
                        Label("قائمتي", systemImage: library.isFavorite(media) ? "checkmark" : "plus")
                    }
                }
                .padding(.top, 10)
                if count > 1 {
                    HStack(spacing: 8) {
                        ForEach(0..<count, id: \.self) { dot in
                            Capsule().fill(dot == index ? Color.white : Color.white.opacity(0.35))
                                .frame(width: dot == index ? 34 : 8, height: 8)
                        }
                    }
                    .padding(.top, 8)
                }
            }
            .padding(.leading, 90).padding(.bottom, 40)
            .frame(maxWidth: 1000, alignment: .leading)
        }
        .frame(height: 860)
        .focusSection()
    }
}

/// Titled horizontal row of cards.
struct TVShelf<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(title).font(.title3.bold()).padding(.horizontal, 90)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 44) { content }
                    .padding(.horizontal, 90).padding(.vertical, 30)
            }
            .scrollClipDisabled()
        }
        .focusSection()
    }
}

/// 2:3 poster that opens the title's page; the system card style gives the tvOS lift and parallax.
struct PosterCard: View {
    let media: Media
    var width: CGFloat = 250
    var body: some View {
        NavigationLink(value: media) {
            Artwork(url: media.poster.isEmpty ? media.backdrop : media.poster)
                .frame(width: width, height: width * 1.5)
        }
        .buttonStyle(.card)
        .overlay(alignment: .bottom) {
            Text(media.title).font(.caption).lineLimit(1).foregroundStyle(TVTheme.muted)
                .frame(width: width).offset(y: 44)
        }
        .padding(.bottom, 44)
    }
}

/// 16:9 card of continue watching: plays straight away; long press removes it.
struct ContinueCard: View {
    let media: Media
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    var body: some View {
        Button { player.play(media) } label: {
            Artwork(url: media.backdrop.isEmpty ? media.poster : media.backdrop)
                .frame(width: 440, height: 248)
                .overlay(alignment: .bottom) { ProgressStrip(fraction: library.fraction(media)) }
        }
        .buttonStyle(.card)
        .contextMenu {
            Button("إزالة من تابع المشاهدة", role: .destructive) { library.remove(media) }
        }
        .overlay(alignment: .bottom) {
            Text(media.episode > 0 ? "\(media.title) · ح \(media.episode)" : media.title)
                .font(.caption).lineLimit(1).foregroundStyle(TVTheme.muted)
                .frame(width: 440).offset(y: 44)
        }
        .padding(.bottom, 44)
    }
}
