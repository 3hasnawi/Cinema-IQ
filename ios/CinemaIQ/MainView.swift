import SwiftUI
import CinemaCore

struct MainView: View {
    @EnvironmentObject private var library: LibraryStore
    @AppStorage("ios.contentSource.v1") private var sourceName = ContentSource.dawwirli.rawValue
    var body: some View {
        TabView {
            NavigationStack { HomeView() }.id(sourceName).tabItem { Label("الرئيسية", systemImage: "house.fill") }
            NavigationStack { SearchView() }.tabItem { Label("البحث", systemImage: "magnifyingglass") }
            NavigationStack {
                ScrollView { VStack(alignment: .leading, spacing: 28) {
                    MediaRow(title: "قائمتي", items: library.favorites)
                    MediaRow(title: "سجل المشاهدة", items: library.watched)
                    if library.favorites.isEmpty && library.watched.isEmpty { ContentUnavailableView("مكتبتك", systemImage: "bookmark", description: Text("أضف أعمالاً إلى قائمتك أو ابدأ المشاهدة.")) }
                }.padding(.vertical) }.navigationTitle("مكتبتي")
            }.tabItem { Label("مكتبتي", systemImage: "bookmark.fill") }
            NavigationStack { SettingsView() }.tabItem { Label("الملف الشخصي", systemImage: "person.crop.circle") }
        }
    }
}
struct Artwork: View {
    let url: String
    var body: some View {
        AsyncImage(url: URL(string: url)) { image in image.resizable().scaledToFill() }
        placeholder: { Rectangle().fill(.white.opacity(0.08)).overlay { Image(systemName: "film").foregroundStyle(.secondary) } }
    }
}
struct MediaRow: View {
    let title: String
    let items: [Media]
    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading) {
                Text(title).font(.title3.bold()).padding(.horizontal)
                ScrollView(.horizontal) { LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(items) { media in
                        NavigationLink { DetailView(initial: media) } label: {
                            VStack(alignment: .leading) {
                                Artwork(url: media.poster).frame(width: 126, height: 184).clipped().clipShape(RoundedRectangle(cornerRadius: 14))
                                Text(media.title).font(.caption).lineLimit(2).frame(width: 126, alignment: .leading)
                            }.foregroundStyle(.white)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal) }.scrollIndicators(.hidden)
            }
        }
    }
}
struct HomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @AppStorage("ios.contentSource.v1") private var sourceName = ContentSource.dawwirli.rawValue
    private var source: ContentSource { ContentSource(rawValue: sourceName) ?? .dawwirli }
    @State private var shelves: [Shelf] = []
    @State private var error: String?
    @State private var loading = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                if let hero = shelves.first?.items.first {
                    NavigationLink { DetailView(initial: hero) } label: {
                        ZStack(alignment: .bottomLeading) {
                            Artwork(url: hero.backdrop.isEmpty ? hero.poster : hero.backdrop).frame(height: 340).clipped()
                            LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                            VStack(alignment: .leading) { Text(hero.title).font(.largeTitle.bold()); Label("عرض التفاصيل", systemImage: "play.circle.fill") }.foregroundStyle(.white).padding()
                        }.frame(height: 340)
                    }.buttonStyle(.plain)
                }
                NavigationLink { CatalogView(source: source, query: "") .navigationTitle("تصفح \(source.title)") } label: { Label("تصفح الأفلام والمسلسلات", systemImage: "square.grid.2x2").padding(.horizontal) }
                MediaRow(title: "تابع المشاهدة", items: library.recent.filter { (try? ContentSource.owner(of: $0.id)) == source })
                ForEach(shelves) { shelf in MediaRow(title: shelf.title, items: shelf.items) }
                if loading { ProgressView().frame(maxWidth: .infinity) }
                if let error { Text(error).padding(); Button("إعادة المحاولة") { Task { await load() } }.padding() }
            }
        }.navigationTitle("Cinema iQ").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SourcePicker() } }
            .task(id: sourceName) { shelves = []; await load() }.refreshable { await load() }
    }
    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do { let result = try await SourceClient().home(source); try Task.checkCancellation(); shelves = result }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
struct SourcePicker: View {
    @AppStorage("ios.contentSource.v1") private var sourceName = ContentSource.dawwirli.rawValue
    var body: some View {
        Picker("مصدر المحتوى", selection: $sourceName) {
            ForEach(ContentSource.allCases) { source in Text(source.title).tag(source.rawValue) }
        }.pickerStyle(.menu)
    }
}
struct SearchView: View {
    @AppStorage("ios.contentSource.v1") private var sourceName = ContentSource.dawwirli.rawValue
    @State private var query = ""
    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    var body: some View {
        Group {
            if trimmed.count >= 2 {
                CatalogView(source: ContentSource(rawValue: sourceName) ?? .dawwirli, query: trimmed)
                    .id(sourceName + "/" + trimmed)
            } else { ContentUnavailableView("البحث", systemImage: "magnifyingglass", description: Text("اكتب حرفين على الأقل للبحث في المصدر المحدد.")) }
        }.navigationTitle("البحث")
            .searchable(text: $query, prompt: "اسم الفيلم أو المسلسل")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SourcePicker() } }
    }
}
struct CatalogView: View {
    let source: ContentSource
    let query: String
    @State private var series = false
    var body: some View {
        VStack {
            Picker("نوع العمل", selection: $series) { Text("أفلام").tag(false); Text("مسلسلات").tag(true) }.pickerStyle(.segmented).padding(.horizontal)
            CatalogResults(source: source, query: query, series: series).id(series)
        }
    }
}
private struct CatalogResults: View {
    let source: ContentSource
    let query: String
    let series: Bool
    @State private var items: [Media] = []
    @State private var message: String?
    @State private var loading = false
    @State private var page = 0
    @State private var more = true
    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 130))], spacing: 20) {
                ForEach(items) { media in
                    NavigationLink { DetailView(initial: media) } label: {
                        VStack { Artwork(url: media.poster).frame(height: 200).clipped().clipShape(RoundedRectangle(cornerRadius: 14)); Text(media.title).font(.caption).lineLimit(2) }.foregroundStyle(.white)
                    }
                }
            }.padding()
            if loading { ProgressView().padding() }
            if let message { Text(message).padding() }
            if !loading && more { Button(message == nil ? "عرض المزيد" : "إعادة المحاولة") { Task { await load() } }.padding() }
        }.task {
            do { if !query.isEmpty { try await Task.sleep(for: .milliseconds(400)) }; try Task.checkCancellation(); await load() } catch {}
        }
    }
    @MainActor private func load() async {
        guard !loading else { return }
        loading = true; message = nil
        defer { loading = false }
        do {
            let result = try await SourceClient().catalog(source, series: series, query: query, page: page + 1)
            try Task.checkCancellation()
            let existing = Set(items.map(\.id)); let additions = result.filter { !existing.contains($0.id) }
            items.append(contentsOf: additions); page += 1
            more = !additions.isEmpty && source != .dawwirli
            if items.isEmpty { message = "لا توجد نتائج" }
        } catch { if !Task.isCancelled { message = error.localizedDescription } }
    }
}
struct SettingsView: View {
    var body: some View {
        Form {
            Section("مصدر المحتوى") { SourcePicker(); Text("اختر سينمانا أو فودو أو دورلي حسب الخدمة التي تدعمها شبكة الإنترنت لديك. سيظهر محتوى المصدر المحدد في الرئيسية والبحث. الأعمال المحفوظة تبقى مرتبطة بمصدرها الأصلي.").foregroundStyle(.secondary) }
            Section("الحساب والمزامنة") { Text("الحفظ حالياً على هذا الجهاز فقط. تفعيل الحساب والمزامنة بين الأجهزة سيكون في المرحلة التالية بعد إعداد تطبيق iOS في Firebase.") }
            Section("عن التطبيق") { Text("Cinema iQ · iPhone Preview"); Text("مشروع مستقل بواجهة عربية ومشغّل iOS الأصلي.").foregroundStyle(.secondary) }
        }.navigationTitle("الملف الشخصي")
    }
}
