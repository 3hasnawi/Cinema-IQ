import SwiftUI
import CinemaCore

struct TVIPTVView: View {
    @EnvironmentObject private var router: TVRouter
    @StateObject private var store = TVIPTVConfiguration.makeStore()
    @StateObject private var access = TVIPTVCategoryAccess()
    @State private var playing: IPTVChannel?
    @State private var startGroup: String?
    @State private var modal: Sheet?
    @State private var manageOnDismiss = false
    @State private var leaving = false
    @State private var editing: Editor?
    @State private var pinGroup: String?
    @State private var completedLoad: String?
    @State private var refreshID = 0
    @State private var forceRefresh = false
    @FocusState private var focus: String?
    private enum Sheet: String, Identifiable { case sources, add, categories; var id: Self { self } }
    private struct Editor: Identifiable { let source: IPTVSource?; let id = UUID() }
    private struct IndexKey: Hashable { let id: String?; let catalog: UUID; let rules: UUID; let ready: Bool }
    private var key: IndexKey { IndexKey(id: store.selectedID, catalog: store.catalogRevision, rules: access.revision, ready: access.loaded && !access.unavailable) }
    private var guide: IPTVTelevisionGuide? { access.guide.flatMap { $0.sourceID == store.selectedID ? $0 : nil } }
    private var waiting: some View {
        VStack(spacing: 26) {
            Image("TelevisionAntenna").resizable().scaledToFit().frame(width: 90, height: 90)
            ProgressView().accessibilityLabel("جارٍ فتح التلفزيون")
        }.accessibilityIdentifier("tv.iptv.loading")
    }
    var body: some View {
        ZStack {
            TVTheme.ink.ignoresSafeArea()
            if let pinGroup {
                TVIPTVPinPad(creating: false, submit: { pin in
                    guard access.matches(pin), let row = guide?.rows(section: .group(pinGroup), visit: pinGroup, favorites: []).first,
                          access.rules(row.sourceID).allows(row.group, visit: pinGroup) else { return false }
                    self.pinGroup = nil; startGroup = pinGroup; playing = row; return true
                }, cancel: { self.pinGroup = nil; focus = pinGroup })
            } else if store.storageLoading || !access.loaded || leaving || playing != nil { waiting }
            else if !store.storageAvailable || access.unavailable {
                VStack(spacing: 26) {
                    Text("التلفزيون").font(.title).accessibilityIdentifier("tv.iptv.heading")
                    Text("تعذّر قراءة بيانات التلفزيون المحفوظة")
                    HStack(spacing: 30) {
                        Button("إعادة المحاولة") { access.load(); store.readSources() }
                            .focused($focus, equals: "retry").accessibilityIdentifier("tv.iptv.storage.retry")
                        Button("العودة إلى الرئيسية") { router.tab = .home }
                            .focused($focus, equals: "return").accessibilityIdentifier("tv.iptv.return")
                    }
                }.defaultFocus($focus, "return").onAppear { focus = "return" }
            } else if store.sources.isEmpty {
                VStack(spacing: 28) {
                    HStack { Image("TelevisionAntenna"); Text("التلفزيون").accessibilityIdentifier("tv.iptv.heading") }.font(.title)
                    Text("أضف رابط M3U أو اشتراك Xtream لفتح القنوات.")
                    HStack(spacing: 30) {
                        Button("إضافة مصدر") { modal = .add }.focused($focus, equals: "add").accessibilityIdentifier("tv.iptv.add")
                        Button("العودة إلى الرئيسية") { router.tab = .home }.focused($focus, equals: "return")
                            .accessibilityIdentifier("tv.iptv.return")
                    }
                }.defaultFocus($focus, "add").onAppear { focus = "add" }
            } else if let guide, guide.unrestricted.isEmpty, completedLoad == store.loadKey, !store.loading {
                ScrollView {
                    VStack(spacing: 24) {
                        Text(store.message ?? "لا توجد قنوات متاحة حاليًا").font(.title3)
                        ForEach(guide.categories.filter { guide.rules.locked.contains($0.name) && !guide.rules.hidden.contains($0.name) }) { group in
                            Button { pinGroup = group.name } label: { Label(group.name, systemImage: "lock") }.focused($focus, equals: group.name)
                        }
                        Button("إدارة التصنيفات") { modal = .categories }
                        Button("المصادر والاشتراكات") { modal = .sources }
                        Button("إعادة المحاولة") { forceRefresh = true; refreshID += 1 }
                    }.padding(44)
                }
            } else { waiting }
        }
        .environmentObject(access)
        .task { access.load() }
        .task(id: store.loadKey + String(refreshID)) {
            let key = store.loadKey
            let force = forceRefresh; forceRefresh = false
            await store.reload(force: force)
            if !Task.isCancelled { completedLoad = key }
        }
        .task(id: key) {
            guard access.loaded, !access.unavailable, let source = store.selectedID else { access.guide = nil; return }
            let catalog = store.catalog.channels, rules = access.rules(source)
            let value = await Task.detached(priority: .userInitiated) { IPTVTelevisionGuide(channels: catalog, sourceID: source, rules: rules) }.value
            guard !Task.isCancelled, store.selectedID == source else { return }
            access.guide = value
            enter()
        }
        .onChange(of: router.tab) { _, tab in if tab == .iptv { leaving = false; enter() } }
        .onAppear { enter() }
        .onExitCommand(perform: modal == nil && playing == nil && pinGroup == nil ? { router.tab = .home } : nil)
        .fullScreenCover(item: $modal, onDismiss: { enter(); if store.sources.isEmpty { focus = "add" } }) { sheet in
            Group {
                switch sheet {
                case .add: TVIPTVSourceForm(existing: nil)
                case .categories:
                    if let guide { TVIPTVCategoryManager(source: guide.sourceID, categories: guide.categories) { modal = nil } }
                case .sources:
                    TVIPTVSourcesView(edit: { editing = Editor(source: $0) })
                        .fullScreenCover(item: $editing) { editor in TVIPTVSourceForm(existing: editor.source).environmentObject(store) }
                }
            }.environmentObject(store).environmentObject(access).environment(\.layoutDirection, .rightToLeft)
        }
        .fullScreenCover(item: $playing, onDismiss: {
            if manageOnDismiss { manageOnDismiss = false; modal = .sources }
            else if leaving { router.tab = .home }
            else { enter() }
        }) { channel in
            TVIPTVPlayerView(initial: channel, group: startGroup, onSources: { manageOnDismiss = true },
                             onClose: { leaving = true })
                .environmentObject(store).environmentObject(access)
        }
    }
    private func enter() {
        guard router.tab == .iptv, !leaving, !manageOnDismiss, playing == nil, modal == nil, pinGroup == nil,
              let guide, guide.rules == access.rules(guide.sourceID), !guide.unrestricted.isEmpty else { return }
        let recent = store.channelCollection.recent
        let wanted = Set(recent)
        let matches = Dictionary(uniqueKeysWithValues: guide.unrestricted.filter { wanted.contains($0.id) }.map { ($0.id, $0) })
        startGroup = nil
        playing = recent.lazy.compactMap { matches[$0] }.first ?? guide.unrestricted.first
    }
}

private struct TVIPTVSourcesView: View {
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    let edit: (IPTVSource?) -> Void
    @State private var deleting: IPTVSource?
    @FocusState private var focus: String?
    var body: some View {
        NavigationStack {
            List {
                ForEach(store.sources) { source in
                    Section {
                        Button { store.selectedID = source.id; dismiss() } label: {
                            HStack { Text(source.name); Spacer(); if store.selectedID == source.id { Image(systemName: "checkmark") } }
                        }.focused($focus, equals: source.id).accessibilityIdentifier("tv.iptv.source." + source.id)
                        Text(source.address.host ?? "").font(.caption).foregroundStyle(.secondary)
                        HStack(spacing: 32) {
                            Button("تعديل") { edit(source) }.accessibilityIdentifier("tv.iptv.edit." + source.id)
                            Button("حذف", role: .destructive) { deleting = source }.accessibilityIdentifier("tv.iptv.delete." + source.id)
                        }.buttonStyle(.bordered)
                    }
                }
                Button("إضافة مصدر") { edit(nil) }.accessibilityIdentifier("tv.iptv.add")
                if let message = store.message { Text(message).foregroundStyle(.secondary) }
                Button("إغلاق") { dismiss() }
            }.buttonStyle(.bordered).navigationTitle("مصادر IPTV")
        }
        .defaultFocus($focus, store.selectedID ?? store.sources.first?.id ?? "")
        .onExitCommand { dismiss() }
        .confirmationDialog("حذف المصدر من Apple TV؟", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("حذف المصدر", role: .destructive) { if let source = deleting { store.remove(source) }; deleting = nil }
            Button("إلغاء", role: .cancel) { deleting = nil }
        }
    }
}
