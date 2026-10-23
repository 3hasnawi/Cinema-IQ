import SwiftUI
import CinemaCore

/// Home in the Apple TV app's style: the featured title fills the screen above the fold (its artwork is the page's
/// background), continue watching peeks at the bottom, and scrolling down snaps to the shelves over the blurred art.
struct TVHomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var shelves: [Shelf] = []
    @State private var loading = false
    @State private var error: String?
    @State private var heroIndex = 0
    @State private var loadedSource: String?
    @State private var belowFold = false
    @FocusState private var heroFocus: HeroButton?

    private var source: ContentSource { SourcePreference.source(sourceName) }
    private var featured: [Media] {
        var seen = Set<String>()
        let all = shelves.flatMap(\.items).filter { seen.insert($0.id).inserted }
        let wide = all.filter { !$0.backdrop.isEmpty }
        return Array((wide.count >= 3 ? wide : all.filter { !$0.poster.isEmpty }).prefix(8))
    }
    private var hero: Media? { featured.indices.contains(heroIndex) ? featured[heroIndex] : featured.first }
    private var recent: [Media] { library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source } }

    var body: some View {
        GeometryReader { proxy in
            let showcase = max(400, proxy.size.height * 0.8)
            ZStack {
                ShowcaseBackground(url: hero.map { $0.backdrop.isEmpty ? $0.poster : $0.backdrop } ?? "", belowFold: belowFold)
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 56) {
                        TVHeroHeader(media: hero, count: featured.count, index: heroIndex, loading: loading, error: shelves.isEmpty ? error : nil,
                                     focus: $heroFocus, next: { advance(1) }, retry: { Task { await load() } })
                            .frame(height: showcase, alignment: .bottomLeading)
                            .onScrollVisibilityChange(threshold: 0.5) { visible in
                                withAnimation { belowFold = !visible }
                            }
                        if !recent.isEmpty {
                            TVShelf(title: "تابع المشاهدة") {
                                ForEach(recent) { media in ContinueLockup(media: media) }
                            }
                        }
                        ForEach(shelves) { shelf in
                            TVShelf(title: shelf.title) {
                                ForEach(shelf.items) { media in PosterLockup(media: media) }
                            }
                        }
                    }
                    .padding(.bottom, 60)
                }
                .scrollClipDisabled()
                .scrollIndicators(.hidden)
                .scrollTargetBehavior(FoldSnapping(aboveFold: !belowFold, showcaseHeight: showcase + 56))
            }
        }
        .task(id: sourceName) {
            guard loadedSource != sourceName || shelves.isEmpty else { return }
            if loadedSource != sourceName { shelves = []; heroIndex = 0 }
            await load()
        }
        // The featured title changes every 9 s while the viewer is not on its buttons and is above the fold.
        .task(id: "\(heroIndex)-\(heroFocus == nil)-\(belowFold)-\(featured.count)") {
            guard featured.count > 1, heroFocus == nil, !belowFold else { return }
            try? await Task.sleep(for: .seconds(9))
            guard !Task.isCancelled else { return }
            advance(1)
        }
    }

    private func advance(_ step: Int) {
        guard featured.count > 1 else { return }
        withAnimation(.easeInOut(duration: 0.6)) { heroIndex = (heroIndex + step + featured.count) % featured.count }
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

enum HeroButton: Hashable { case play, info, list }

/// The featured title's text and buttons at the bottom of the showcase. The buttons stay the same views while the
/// title changes, so focus never drops; Left from the last button shows the next title.
struct TVHeroHeader: View {
    let media: Media?
    let count: Int
    let index: Int
    let loading: Bool
    let error: String?
    var focus: FocusState<HeroButton?>.Binding
    let next: () -> Void
    let retry: () -> Void
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @EnvironmentObject private var router: TVRouter
    @State private var starting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Spacer(minLength: 0)
            if let media {
                VStack(alignment: .leading, spacing: 16) {
                    Text(media.isSeries ? "مسلسل" : "فيلم")
                        .font(.caption.weight(.bold)).foregroundStyle(.white.opacity(0.7))
                    Text(media.title)
                        .font(.system(size: 70, weight: .bold))
                        .lineLimit(2).minimumScaleFactor(0.7)
                        .frame(maxWidth: 1150, alignment: .leading)
                    FactsLine(facts: DisplayFormat.facts(media))
                    Text(media.overview.isEmpty ? " " : media.overview)
                        .font(.callout).foregroundStyle(.white.opacity(0.75))
                        .lineLimit(2).frame(maxWidth: 950, alignment: .leading)
                }
                .id(media.id)
                .transition(.opacity)
                HStack(spacing: 22) {
                    Button { play(media) } label: {
                        Label(starting ? "جارٍ التحضير…" : playTitle(media), systemImage: "play.fill")
                            .font(.callout.weight(.semibold))
                    }
                    .focused(focus, equals: .play)
                    Button { router.open(media) } label: {
                        Label(media.isSeries ? "الحلقات" : "التفاصيل", systemImage: media.isSeries ? "list.bullet" : "info.circle")
                            .font(.callout.weight(.semibold))
                    }
                    .focused(focus, equals: .info)
                    Button { library.toggle(media) } label: {
                        Image(systemName: library.isFavorite(media) ? "checkmark" : "plus").font(.callout.weight(.semibold))
                    }
                    .focused(focus, equals: .list)
                    .onMoveCommand { direction in if direction == .left { next() } }
                }
                .padding(.top, 8)
                if count > 1 {
                    HStack(spacing: 8) {
                        ForEach(0..<count, id: \.self) { dot in
                            Capsule().fill(dot == index ? Color.white : Color.white.opacity(0.3))
                                .frame(width: dot == index ? 26 : 8, height: 8)
                        }
                    }
                    .animation(.easeInOut, value: index)
                    .padding(.top, 6)
                }
            } else if loading {
                ProgressView().frame(maxWidth: .infinity)
                Spacer(minLength: 0)
            } else if let error {
                ErrorState(message: error, retry: retry)
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }

    private func playTitle(_ media: Media) -> String {
        if media.isSeries { return "تشغيل" }
        return library.fraction(media) > 0 ? "تابع المشاهدة" : "تشغيل"
    }

    /// A movie plays at once; a series loads its seasons and plays the episode the viewer is on.
    private func play(_ media: Media) {
        guard media.isSeries else { player.play(media); return }
        guard !starting else { return }
        starting = true
        Task {
            defer { starting = false }
            guard let found = try? await SourceClient().detail(media),
                  let target = PlayTarget.pick(found.1, library: library) else {
                router.open(media)
                return
            }
            player.play(target, queue: found.1.flatMap(\.episodes))
        }
    }
}
