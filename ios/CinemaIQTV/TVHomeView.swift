import SwiftUI
import CinemaCore

/// The reference's cinematic composition: a floating top bar, clear edge-to-edge artwork, a compact feature,
/// centered carousel dots and the first landscape shelf peeking into the initial viewport.
struct TVHomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @EnvironmentObject private var router: TVRouter
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var shelves: [Shelf] = []
    @State private var loading = false
    @State private var error: String?
    @State private var heroIndex = 0
    @State private var loadedSource: String?
    @State private var belowFold = false
    @State private var paginationFocused = false
    @State private var metadata: TVHeroMetadata?
    @FocusState private var heroFocused: Bool

    private var source: ContentSource { SourcePreference.source(sourceName) }
    private var featured: [Media] {
        var seen = Set<String>()
        let all = shelves.flatMap(\.items).filter { seen.insert($0.id).inserted }
        let wide = all.filter { !$0.backdrop.isEmpty }
        let posters = all.filter { !$0.poster.isEmpty }
        return Array((wide.count >= 3 ? wide : (posters.isEmpty ? all : posters)).prefix(8))
    }
    private var hero: Media? { featured.indices.contains(heroIndex) ? featured[heroIndex] : featured.first }
    private var heroMetadata: TVHeroMetadata? { metadata?.id == hero?.id ? metadata : nil }
    private var recent: [Media] { library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source } }
    private var artwork: String {
        if let value = hero?.backdrop, !value.isEmpty { return value }
        if let value = heroMetadata?.tmdb?.backdrop, !value.isEmpty { return value }
        return hero?.poster ?? ""
    }
    private var canRotate: Bool {
        !heroFocused && !paginationFocused && !belowFold && !reduceMotion && phase == .active &&
        router.tab == .home && router.popup == nil && player.selection == nil && router.paths[.home, default: []].isEmpty
    }

    var body: some View {
        GeometryReader { proxy in
            let showcase = max(440, proxy.size.height * 0.78)
            ZStack {
                TVHomeBackdrop(url: artwork, belowFold: belowFold)
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 28) {
                        TVHeroHeader(media: hero, metadata: heroMetadata, count: featured.count, index: heroIndex,
                                     loading: loading, error: featured.isEmpty ? error : nil,
                                     contentWidth: min(720, proxy.size.width * 0.46), focus: $heroFocused,
                                     select: selectFeature, paginationFocusChanged: { paginationFocused = $0 },
                                     retry: { Task { await load() } })
                            .frame(height: showcase)
                            .onScrollVisibilityChange(threshold: 0.5) { visible in
                                belowFold = !visible
                            }
                        VStack(alignment: .leading, spacing: 46) {
                            if !recent.isEmpty {
                                TVHomeShelf(title: "تابع المشاهدة") {
                                    ForEach(recent) { media in ContinueLockup(media: media) }
                                }
                            }
                            ForEach(shelves) { shelf in
                                TVHomeShelf(title: shelf.title) {
                                    ForEach(shelf.items) { media in PosterLockup(media: media) }
                                }
                            }
                            catalogShortcuts
                            if !shelves.isEmpty, let error {
                                ErrorState(message: error) { Task { await load() } }
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: proxy.size.height - 28, alignment: .topLeading)
                    }
                    .padding(.bottom, 60)
                }
                .scrollClipDisabled()
                .scrollIndicators(.hidden)
                .scrollTargetBehavior(FoldSnapping(aboveFold: !belowFold, showcaseHeight: showcase + 28))
            }
        }
        .defaultFocus($heroFocused, true)
        .task(id: sourceName) {
            guard loadedSource != sourceName || shelves.isEmpty else { return }
            if loadedSource != sourceName { shelves = []; heroIndex = 0; metadata = nil }
            await load()
        }
        .task(id: hero?.id) {
            metadata = nil
            guard let media = hero else { return }
            async let tmdb = TMDB.shared?.work(for: media)
            async let omdb = OMDb.shared?.info(for: media)
            let result = await TVHeroMetadata(id: media.id, tmdb: tmdb, omdb: omdb)
            guard !Task.isCancelled, hero?.id == media.id else { return }
            metadata = result
        }
        .task(id: "\(heroIndex)-\(canRotate)-\(featured.count)") {
            guard featured.count > 1, canRotate else { return }
            do { try await Task.sleep(for: .seconds(9)) } catch { return }
            guard !Task.isCancelled, canRotate else { return }
            selectFeature((heroIndex + 1) % featured.count)
        }
    }

    /// Movies and Series remain reachable without adding more destinations to the reference's four-tab bar.
    private var catalogShortcuts: some View {
        HStack(spacing: 24) {
            Button { router.tab = .movies } label: { Label("كل الأفلام", systemImage: "film") }
                .accessibilityIdentifier("tv.catalog.movies")
            Button { router.tab = .series } label: { Label("كل المسلسلات", systemImage: "tv") }
                .accessibilityIdentifier("tv.catalog.series")
        }
        .buttonStyle(TVHeroActionStyle())
        .focusSection()
    }

    private func selectFeature(_ index: Int) {
        guard featured.indices.contains(index) else { return }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.5)) { heroIndex = index }
    }

    private func load() async {
        let requestedSource = sourceName
        loading = true; error = nil
        defer { if requestedSource == sourceName { loading = false } }
        do {
            let result = try await SourceClient().home(SourcePreference.source(requestedSource))
            try Task.checkCancellation()
            guard requestedSource == sourceName else { return }
            shelves = result
            loadedSource = requestedSource
            heroIndex = 0
            if result.allSatisfy({ $0.items.isEmpty }) { error = "لا توجد أعمال حالياً في هذا المصدر. جرّب مجدداً أو اختر مصدراً آخر من الإعدادات." }
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled, requestedSource == sourceName { self.error = error.localizedDescription }
        }
    }
}

struct TVHeroMetadata {
    let id: String
    let tmdb: TMDBWork?
    let omdb: OMDbInfo?
    var people: [TMDBPerson] {
        if let cast = tmdb?.cast, !cast.isEmpty { return Array(cast.prefix(3)) }
        return (omdb?.actors ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && $0 != "N/A" }.prefix(3).enumerated()
            .map { TMDBPerson(id: -1 - $0.offset, name: $0.element, photo: "") }
    }
}

struct TVHeroHeader: View {
    let media: Media?
    let metadata: TVHeroMetadata?
    let count: Int
    let index: Int
    let loading: Bool
    let error: String?
    let contentWidth: CGFloat
    var focus: FocusState<Bool>.Binding
    let select: (Int) -> Void
    let paginationFocusChanged: (Bool) -> Void
    let retry: () -> Void
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @EnvironmentObject private var router: TVRouter
    @State private var starting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 36)
            if let media {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 18) {
                        Text(media.title)
                            .font(.system(size: 56, weight: .heavy))
                            .lineLimit(2).minimumScaleFactor(0.7)
                        facts(media)
                        let overview = media.overview.isEmpty ? (metadata?.tmdb?.overview ?? "") : media.overview
                        if !overview.isEmpty {
                            Text(overview)
                                .font(.system(size: 25, weight: .medium))
                                .foregroundStyle(.white.opacity(0.9))
                                .lineLimit(2).multilineTextAlignment(.leading)
                        }
                        if let people = metadata?.people, !people.isEmpty { TVHeroCast(people: people) }
                    }
                    .id(media.id)
                    .transition(.opacity)
                    Button { router.open(media) } label: {
                        Text(starting ? "جارٍ التحضير…" : (media.isSeries ? "إلى المسلسل" : "إلى الفيلم"))
                    }
                    .buttonStyle(TVHeroActionStyle())
                    .focused(focus)
                    .accessibilityIdentifier("tv.hero.details")
                    .accessibilityHint("افتح التفاصيل، أو اضغط زر التشغيل للمشاهدة مباشرة")
                    .onPlayPauseCommand { play(media) }
                    .contextMenu {
                        Button("تشغيل", systemImage: "play.fill") { play(media) }
                        Button(library.isFavorite(media) ? "إزالة من قائمتي" : "أضف إلى قائمتي",
                               systemImage: library.isFavorite(media) ? "checkmark" : "plus") { library.toggle(media) }
                    }
                    .padding(.top, 4)
                }
                .frame(width: contentWidth, alignment: .leading)
                .shadow(color: .black.opacity(0.25), radius: 6, y: 2)
                // Extend this target section across the empty cinematic space so Down from the centered
                // navigation reaches the primary action before the centered pagination row below it.
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
                Spacer(minLength: 32)
                if count > 1 {
                    TVHeroPagination(count: count, index: index, select: select, focusChanged: paginationFocusChanged)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .focusSection()
                }
            } else if loading {
                ProgressView("جارٍ تحميل الأعمال…").frame(maxWidth: .infinity)
                Spacer(minLength: 0)
            } else if let error {
                ErrorState(message: error, retry: retry)
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }

    private func facts(_ media: Media) -> some View {
        let rating = metadata?.omdb?.imdbRating ?? media.rating
        let labels = [media.year, metadata?.omdb?.genre.components(separatedBy: ",").first ?? "",
                      metadata?.omdb?.rated ?? "", media.quality]
            .filter { !$0.isEmpty && $0 != "N/A" }
        return HStack(spacing: 12) {
            if let number = Double(rating), number > 0 {
                Text("★ \(rating)").foregroundStyle(Color(red: 0.45, green: 0.92, blue: 0.65))
                    .accessibilityLabel("التقييم \(rating)")
            }
            Text(labels.joined(separator: " · ")).foregroundStyle(.white.opacity(0.9)).lineLimit(1)
        }
        .font(.system(size: 21, weight: .semibold))
    }

    /// Direct remote playback and the long-press menu preserve the previous home shortcuts.
    private func play(_ media: Media) {
        guard !starting else { return }
        guard media.isSeries else { player.play(media); return }
        starting = true
        Task { @MainActor in
            defer { starting = false }
            let result = try? await SourceClient().detail(media)
            // A slow source must not navigate or start a title after the viewer leaves Home.
            guard !Task.isCancelled, router.tab == .home, router.paths[.home, default: []].isEmpty,
                  player.selection == nil else { return }
            guard let found = result, let target = PlayTarget.pick(found.1, library: library) else {
                router.open(media)
                return
            }
            player.play(target, queue: found.1.flatMap(\.episodes), series: found.0)
        }
    }
}
