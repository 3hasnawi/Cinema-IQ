import SwiftUI
import CinemaCore

/// Movies or Series of the chosen source as a paged poster grid.
struct TVCatalogView: View {
    let series: Bool
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    var body: some View {
        TVGrid(source: SourcePreference.source(sourceName), series: series, query: "")
            .id(sourceName)
    }
}

/// Native tvOS search (system keyboard and dictation), with movies and series results.
struct TVSearchView: View {
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var query = ""
    @State private var series = false
    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    var body: some View {
        VStack(spacing: 0) {
            if trimmed.isEmpty {
                ContentUnavailableView("ابحث في \(SourcePreference.source(sourceName).title)", systemImage: "magnifyingglass",
                                       description: Text("اكتب اسم الفيلم أو المسلسل."))
            } else {
                Picker("النوع", selection: $series) {
                    Text("أفلام").tag(false)
                    Text("مسلسلات").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 600)
                .padding(.top, 20)
                TVGrid(source: SourcePreference.source(sourceName), series: series, query: trimmed)
                    .id("\(sourceName)/\(series)/\(trimmed)")
            }
        }
        .searchable(text: $query, prompt: "اسم الفيلم أو المسلسل")
        .background(TVTheme.ink.ignoresSafeArea())
    }
}

/// Paged grid: the next page loads when the last card comes on screen.
struct TVGrid: View {
    let source: ContentSource
    let series: Bool
    let query: String
    @State private var items: [Media] = []
    @State private var page = 0
    @State private var more = true
    @State private var loading = false
    @State private var message: String?
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 48), count: 6)

    var body: some View {
        ScrollView(.vertical) {
            LazyVGrid(columns: columns, spacing: 60) {
                ForEach(items) { media in
                    PosterCard(media: media, width: 240)
                        .onAppear { if media.id == items.last?.id { Task { await load() } } }
                }
            }
            .padding(.horizontal, 90).padding(.vertical, 40)
            if loading { ProgressView().padding(40) }
            else if let message { ErrorState(message: message) { Task { await load() } } }
            else if items.isEmpty && !more {
                ContentUnavailableView("لا توجد نتائج", systemImage: "magnifyingglass", description: Text("جرّب كلمة أخرى أو مصدراً آخر."))
            }
        }
        .background(TVTheme.ink.ignoresSafeArea())
        .task {
            guard items.isEmpty else { return }
            // Typing restarts this view, so a search runs once the viewer pauses.
            if !query.isEmpty {
                try? await Task.sleep(for: .milliseconds(450))
                if Task.isCancelled { return }
            }
            await load()
        }
    }

    private func load() async {
        guard !loading, more else { return }
        loading = true; message = nil
        defer { loading = false }
        do {
            let result = try await SourceClient().catalog(source, series: series, query: query, page: page + 1)
            guard !Task.isCancelled else { return }
            let existing = Set(items.map(\.id))
            let additions = result.filter { !existing.contains($0.id) }
            items.append(contentsOf: additions)
            page += 1
            more = !additions.isEmpty && source != .dawwirli
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled { message = error.localizedDescription }
        }
    }
}

/// Continue watching, My List and finished titles.
struct TVLibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 50) {
                if library.recent.isEmpty && library.favorites.isEmpty && library.watched.isEmpty {
                    ContentUnavailableView("مكتبتك فارغة", systemImage: "rectangle.stack",
                                           description: Text("أضف أعمالاً إلى «قائمتي» من صفحة العمل، وتظهر هنا ما تشاهده."))
                        .padding(.top, 200)
                }
                if !library.recent.isEmpty {
                    TVShelf(title: "تابع المشاهدة") { ForEach(library.recent) { ContinueCard(media: $0) } }
                }
                if !library.favorites.isEmpty {
                    TVShelf(title: "قائمتي") { ForEach(library.favorites) { PosterCard(media: $0) } }
                }
                if !library.watched.isEmpty {
                    TVShelf(title: "شوهد") { ForEach(library.watched.prefix(40)) { PosterCard(media: $0) } }
                }
            }
            .padding(.vertical, 40)
        }
        .background(TVTheme.ink.ignoresSafeArea())
    }
}
