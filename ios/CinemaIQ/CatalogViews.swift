import SwiftUI
import CinemaCore

/// Paged poster grid of one source, movies or series, optionally filtered by a search query.
struct CatalogGrid: View {
    let source: ContentSource
    let query: String
    @State private var series = false
    @State private var items: [Media] = []
    @State private var page = 0
    @State private var more = true
    @State private var loading = false
    @State private var message: String?
    private let columns = [GridItem(.adaptive(minimum: 104, maximum: 160), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Picker("نوع العمل", selection: $series) {
                    Text("أفلام").tag(false)
                    Text("مسلسلات").tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 18)
                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(items) { media in
                        PosterCard(media: media, routeKey: "grid-\(media.id)", width: nil)
                            .onAppear { if media.id == items.last?.id { Task { await load() } } }
                    }
                }
                .padding(.horizontal, 18)
                if loading {
                    ProgressView().padding()
                } else if let message {
                    ErrorState(message: message) { Task { await load() } }
                } else if items.isEmpty && !more {
                    ContentUnavailableView("لا توجد نتائج", systemImage: "magnifyingglass",
                                           description: Text("جرّب كلمة أخرى أو مصدراً آخر."))
                }
            }
            .padding(.vertical, 8)
        }
        .scrollDismissesKeyboard(.immediately)
        .task(id: series) {
            items = []; page = 0; more = true; message = nil; loading = false
            // Typing restarts this task, so a search runs once the viewer pauses.
            if !query.isEmpty {
                try? await Task.sleep(for: .milliseconds(350))
                if Task.isCancelled { return }
            }
            await load()
        }
    }

    private func load() async {
        guard !loading, more else { return }
        let kind = series
        loading = true; message = nil
        defer { loading = false }
        do {
            let result = try await SourceClient().catalog(source, series: kind, query: query, page: page + 1)
            // A late answer for the other tab must not be mixed into this one.
            guard kind == series, !Task.isCancelled else { return }
            let existing = Set(items.map(\.id))
            let additions = result.filter { !existing.contains($0.id) }
            items.append(contentsOf: additions)
            page += 1
            more = !additions.isEmpty && source != .dawwirli
        } catch is CancellationError {
        } catch {
            if kind == series && !Task.isCancelled { message = error.localizedDescription }
        }
    }
}

struct BrowseView: View {
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    var body: some View {
        CatalogGrid(source: SourcePreference.source(sourceName), query: "")
            .id(sourceName)
            .background(Theme.ink)
            .navigationTitle("تصفّح")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SourceMenu() } }
    }
}

struct SearchView: View {
    @EnvironmentObject private var library: LibraryStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var query = ""
    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    var body: some View {
        Group {
            if trimmed.count >= 2 {
                CatalogGrid(source: SourcePreference.source(sourceName), query: trimmed)
                    .id(sourceName + "/" + trimmed)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if library.searches.isEmpty {
                            ContentUnavailableView("ابحث في \(SourcePreference.source(sourceName).title)",
                                                   systemImage: "magnifyingglass",
                                                   description: Text("اكتب حرفين على الأقل من اسم الفيلم أو المسلسل."))
                                .padding(.top, 80)
                        } else {
                            HStack {
                                Text("عمليات البحث الأخيرة").font(.headline)
                                Spacer()
                                Button("مسح") { library.clearSearches() }.font(.subheadline)
                            }
                            ForEach(library.searches, id: \.self) { text in
                                Button { query = text } label: {
                                    Label(text, systemImage: "clock.arrow.circlepath")
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.vertical, 8)
                                }
                                .buttonStyle(.plain)
                                Divider()
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                }
            }
        }
        .background(Theme.ink)
        .navigationTitle("البحث")
        .searchable(text: $query, prompt: "اسم الفيلم أو المسلسل")
        .onSubmit(of: .search) { library.remember(search: trimmed) }
        .toolbar { ToolbarItem(placement: .topBarTrailing) { SourceMenu() } }
    }
}

struct LibraryView: View {
    enum Part: String, CaseIterable, Identifiable {
        case list = "قائمتي", recent = "تابع المشاهدة", watched = "شوهد"
        var id: String { rawValue }
    }
    @EnvironmentObject private var library: LibraryStore
    @State private var part: Part = .list
    private let columns = [GridItem(.adaptive(minimum: 104, maximum: 160), spacing: 12)]
    private var items: [Media] {
        switch part {
        case .list: return library.favorites
        case .recent: return library.recent
        case .watched: return library.watched
        }
    }
    private var emptyTitle: String { part == .list ? "قائمتك فارغة" : "لا يوجد شيء هنا بعد" }
    private var emptyText: String { part == .list ? "أضف أعمالاً بزر + من صفحة العمل." : "ابدأ المشاهدة وستظهر الأعمال هنا." }
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Picker("القسم", selection: $part) {
                    ForEach(Part.allCases) { part in Text(part.rawValue).tag(part) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 18)
                if items.isEmpty {
                    ContentUnavailableView(emptyTitle, systemImage: part == .list ? "bookmark" : "play.rectangle",
                                           description: Text(emptyText))
                        .padding(.top, 60)
                } else {
                    LazyVGrid(columns: columns, spacing: 18) {
                        ForEach(items) { media in
                            PosterCard(media: media, routeKey: "library-\(part.id)-\(media.id)", width: nil)
                        }
                    }
                    .padding(.horizontal, 18)
                }
            }
            .padding(.vertical, 8)
        }
        .background(Theme.ink)
        .navigationTitle("مكتبتي")
    }
}

struct SettingsView: View {
    @EnvironmentObject private var library: LibraryStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var probes: [ContentSource: SourceProbe] = [:]
    @State private var probing = false
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }
    var body: some View {
        Form {
            Section {
                Picker("المصدر", selection: $sourceName) {
                    ForEach(ContentSource.allCases) { source in Text(source.title).tag(source.rawValue) }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("مصدر المحتوى")
            } footer: {
                Text("اختر المصدر الذي تدعمه شبكة الإنترنت لديك. الأعمال المحفوظة تبقى مرتبطة بمصدرها الأصلي.")
            }
            Section {
                ForEach(ContentSource.allCases) { source in
                    ProbeRow(source: source, probe: probes[source], running: probing)
                }
                Button { Task { await runProbes() } } label: {
                    Label(probing ? "جارٍ الفحص…" : "فحص الاتصال", systemImage: "arrow.clockwise")
                }
                .disabled(probing)
            } header: {
                Text("فحص الاتصال بالمصادر")
            } footer: {
                Text("يجرّب التطبيق تحميل الصفحة الرئيسية لكل مصدر ويعرض سبب الفشل ورمزه إن وُجد.")
            }
            Section("البحث") {
                Button("مسح سجل البحث", role: .destructive) { library.clearSearches() }
                    .disabled(library.searches.isEmpty)
            }
            Section("حول التطبيق") {
                LabeledContent("الإصدار", value: version)
                LabeledContent("الحفظ", value: "على هذا الجهاز فقط")
                Text("تطوير Emad Hasnawi")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.ink)
        .navigationTitle("الإعدادات")
    }

    private func runProbes() async {
        probing = true
        probes = [:]
        await withTaskGroup(of: SourceProbe.self) { group in
            for source in ContentSource.allCases { group.addTask { await SourceClient().probe(source) } }
            for await result in group { probes[result.source] = result }
        }
        probing = false
    }
}

private struct ProbeRow: View {
    let source: ContentSource
    let probe: SourceProbe?
    let running: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(source.title)
                Spacer()
                if let probe {
                    if probe.error == nil {
                        Label("متصل · \(probe.items) عمل", systemImage: "checkmark.circle.fill").foregroundStyle(Theme.positive)
                    } else {
                        Label("غير متاح", systemImage: "xmark.circle.fill").foregroundStyle(Theme.negative)
                    }
                } else if running {
                    ProgressView()
                } else {
                    Text("لم يُفحص").foregroundStyle(.secondary)
                }
            }
            .font(.subheadline)
            if let error = probe?.error {
                Text(error).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
