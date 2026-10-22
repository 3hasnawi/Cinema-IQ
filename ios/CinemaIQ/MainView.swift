import SwiftUI
import CinemaCore

struct MainView: View {
    @EnvironmentObject private var library: LibraryStore
    var body: some View {
        TabView {
            NavigationStack { HomeView() }.tabItem { Label("الرئيسية", systemImage: "house.fill") }
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
                MediaRow(title: "تابع المشاهدة", items: library.recent)
                ForEach(shelves) { shelf in MediaRow(title: shelf.title, items: shelf.items) }
                if loading { ProgressView().frame(maxWidth: .infinity) }
                if let error { Text(error).padding(); Button("إعادة المحاولة") { Task { await load() } }.padding() }
            }
        }.navigationTitle("Cinema iQ").navigationBarTitleDisplayMode(.inline)
            .task { if shelves.isEmpty { await load() } }.refreshable { await load() }
    }
    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do { shelves = try await DawwirliClient().home() }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
struct SearchView: View {
    @State private var query = ""
    @State private var items: [Media] = []
    @State private var message: String?
    @State private var loading = false
    var body: some View {
        ScrollView {
            if loading { ProgressView().padding() }
            if let message { Text(message).padding() }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 130))], spacing: 20) {
                ForEach(items) { media in
                    NavigationLink { DetailView(initial: media) } label: {
                        VStack { Artwork(url: media.poster).frame(height: 200).clipped().clipShape(RoundedRectangle(cornerRadius: 14)); Text(media.title).font(.caption).lineLimit(2) }.foregroundStyle(.white)
                    }
                }
            }.padding()
        }.navigationTitle("البحث").searchable(text: $query, prompt: "ابحث في دورلي")
            .task(id: query) {
                items = []; message = nil; loading = false
                guard query.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 else { return }
                do {
                    try await Task.sleep(for: .milliseconds(400)); loading = true
                    let result = try await DawwirliClient().search(query)
                    try Task.checkCancellation(); items = result; loading = false
                    if result.isEmpty { message = "لا توجد نتائج" }
                } catch { if !Task.isCancelled { message = error.localizedDescription; loading = false } }
            }
    }
}
struct SettingsView: View {
    var body: some View {
        Form {
            Section("مصدر المحتوى") { Label("دورلي", systemImage: "play.rectangle"); Text("هذه نسخة آيفون أولية. توفر المحتوى والتشغيل يعتمد على وصول شبكتك إلى المصدر.").foregroundStyle(.secondary) }
            Section("الحساب والمزامنة") { Text("الحفظ حالياً على هذا الجهاز فقط. تفعيل الحساب والمزامنة بين الأجهزة سيكون في المرحلة التالية بعد إعداد تطبيق iOS في Firebase.") }
            Section("عن التطبيق") { Text("Cinema iQ · iPhone Preview"); Text("مشروع مستقل بواجهة عربية ومشغّل iOS الأصلي.").foregroundStyle(.secondary) }
        }.navigationTitle("الملف الشخصي")
    }
}
