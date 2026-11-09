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
    @State private var loadID = UUID()
    @State private var belowFold = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var router: TVRouter
    @FocusState private var featuredFocus: String?
    @State private var heroInfo: OMDbInfo?
    @State private var initialFocusAssigned = false
    @State private var scrollPosition = ScrollPosition(edge: .top)

    private var source: ContentSource { SourcePreference.source(sourceName) }
    private var featured: [Media] {
        var seen = Set<String>()
        let all = shelves.flatMap(\.items).filter { seen.insert($0.id).inserted }
        let wide = all.filter { !$0.backdrop.isEmpty }
        let illustrated = all.filter { !$0.poster.isEmpty || !$0.backdrop.isEmpty }
        return Array((wide.count >= 3 ? wide : (illustrated.isEmpty ? all : illustrated)).prefix(8))
    }
    private var hero: Media? { featured.indices.contains(heroIndex) ? featured[heroIndex] : featured.first }
    private var recent: [Media] { library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source } }

    var body: some View {
        GeometryReader { proxy in
            let showcase = max(440, proxy.size.height * 0.88)
            ZStack {
                ShowcaseBackground(url: hero.map { $0.backdrop.isEmpty ? $0.poster : $0.backdrop } ?? "", belowFold: belowFold)
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 56) {
                        TVFeaturedHero(media: hero, info: heroInfo, featured: featured,
                                       focused: $featuredFocus, loading: loading, error: shelves.isEmpty ? error : nil,
                                       retry: { Task { await load() } }, open: { router.open($0) })
                            .frame(height: showcase, alignment: .bottomLeading)
                            .onScrollVisibilityChange(threshold: 0.5) { visible in
                                if reduceMotion { belowFold = !visible }
                                else { withAnimation(.easeOut(duration: 0.25)) { belowFold = !visible } }
                            }
                        // At least a screen tall, so the page can always scroll to the fold.
                        VStack(alignment: .leading, spacing: 56) {
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
                        .frame(maxWidth: .infinity, minHeight: proxy.size.height - 56, alignment: .topLeading)
                    }
                    .padding(.bottom, 60)
                }
                .scrollClipDisabled()
                .scrollIndicators(.hidden)
                .scrollPosition($scrollPosition)
                .onExitCommand(perform: belowFold ? {
                    // Menu first restores the featured area; the next Menu belongs to the native sidebar.
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                        scrollPosition.scrollTo(edge: .top)
                        featuredFocus = hero?.id
                    }
                } : nil)
            }
        }
        .defaultFocus($featuredFocus, featured.first?.id)
        .task(id: featured.first?.id) {
            guard !initialFocusAssigned, let first = featured.first else { return }
            do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            guard !Task.isCancelled, router.tab == .home, featured.first?.id == first.id else { return }
            featuredFocus = first.id
            initialFocusAssigned = true
        }
        .onChange(of: featuredFocus) { _, id in
            guard let id, let index = featured.firstIndex(where: { $0.id == id }), index != heroIndex else { return }
            if reduceMotion { heroIndex = index }
            else { withAnimation(.easeInOut(duration: 0.35)) { heroIndex = index } }
        }
        .task(id: sourceName) {
            guard loadedSource != sourceName || shelves.isEmpty else { return }
            if loadedSource != sourceName { shelves = []; heroIndex = 0; featuredFocus = nil; initialFocusAssigned = false }
            await load()
        }
        .task(id: hero?.id) {
            heroInfo = nil
            guard let hero else { return }
            let info = await OMDb.shared?.info(for: hero)
            guard !Task.isCancelled else { return }
            heroInfo = info
        }
    }

    private func load() async {
        let request = UUID()
        let requestedSource = sourceName
        loadID = request; loading = true; error = nil
        defer { if loadID == request { loading = false } }
        do {
            let result = try await SourceClient().home(SourcePreference.source(requestedSource))
            try Task.checkCancellation()
            guard loadID == request, sourceName == requestedSource else { return }
            shelves = result
            loadedSource = requestedSource
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled, loadID == request, sourceName == requestedSource { self.error = error.localizedDescription }
        }
    }
}
