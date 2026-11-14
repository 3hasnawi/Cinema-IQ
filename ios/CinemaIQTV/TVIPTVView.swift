import SwiftUI
import CinemaCore

/// Live channels, with real remote focus targets and an escape path in every loading/error state.
struct TVIPTVView: View {
    @EnvironmentObject private var router: TVRouter
    @StateObject private var store = TVIPTVConfiguration.makeStore()
    @State private var query = ""
    @State private var group: String?
    @State private var visible: [IPTVChannel] = []
    @State private var sheet: Sheet?
    @State private var playing: IPTVChannel?
    @State private var lastChannel: String?
    @State private var lastControl = "add"
    @State private var modalSource: String?
    @State private var modalGroup: String?
    @State private var modalRevision = UUID()
    @State private var reloadID = UUID()
    @State private var forceReload = false
    @State private var editingSearch = false
    @State private var focusTask: Task<Void, Never>?
    @FocusState private var focus: String?
    @Namespace private var gridScope
    @Environment(\.resetFocus) private var resetFocus
    private enum Sheet: Identifiable {
        case source(IPTVSource?), sources, groups
        var id: String { switch self { case .source(let value): return value?.id ?? "add"; case .sources: return "sources"; case .groups: return "groups" } }
    }
    private struct FilterKey: Hashable { let revision: UUID; let query: String; let group: String? }
    private var filterKey: FilterKey { FilterKey(revision: store.catalogRevision, query: query, group: group) }
    private var initialFocus: String {
        if let first = visible.first { return "channel." + first.id }
        return store.storageAvailable ? "add" : "return"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            header
            if store.storageLoading {
                HStack(spacing: 20) { ProgressView(); Text("جارٍ قراءة مصادر IPTV…") }
            } else if !store.storageAvailable {
                Text((store.message ?? "تعذّر الوصول إلى البيانات الآمنة.").replacingOccurrences(of: "افتح قفل الجهاز", with: "أعد تشغيل Apple TV"))
                    .foregroundStyle(.secondary)
                Button("إعادة المحاولة") { store.readSources() }.accessibilityIdentifier("tv.iptv.storage.retry")
            } else if store.sources.isEmpty {
                VStack(alignment: .leading, spacing: 22) {
                    Text("قنواتك في مكان واحد").font(.title2.bold())
                    Text("أضف رابط قائمة M3U أو بيانات اشتراك Xtream لتصفح القنوات وتشغيلها داخل التطبيق.")
                        .font(.callout).foregroundStyle(TVTheme.muted)
                }.padding(48).frame(maxWidth: .infinity, alignment: .leading)
                    .background(TVTheme.panel, in: RoundedRectangle(cornerRadius: 28))
            } else {
                controls
                if store.loading {
                    HStack(spacing: 20) { ProgressView(); Text("جارٍ تحميل القنوات…") }
                } else if let message = store.message {
                    Text(message).foregroundStyle(.secondary).accessibilityIdentifier("tv.iptv.error")
                    Button("إعادة المحاولة", action: refresh).accessibilityIdentifier("tv.iptv.retry")
                } else {
                    Text("\(visible.count) قناة").font(.callout).foregroundStyle(TVTheme.muted)
                    if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.").foregroundStyle(.secondary) }
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 32) {
                            ForEach(visible) { channel in
                                Button { lastChannel = channel.id; playing = channel } label: { card(channel) }
                                    .buttonStyle(.card)
                                    .focused($focus, equals: "channel." + channel.id)
                                    .prefersDefaultFocus(visible.first?.id == channel.id, in: gridScope)
                                    .accessibilityLabel(channel.name)
                                    .accessibilityIdentifier("tv.iptv.channel." + channel.id)
                            }
                        }.padding(20)
                    }.focusScope(gridScope).focusSection().accessibilityIdentifier("tv.iptv.channels")
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 60).padding(.top, 32).padding(.bottom, 24)
        .background(TVTheme.ink.ignoresSafeArea())
        .defaultFocus($focus, initialFocus)
        .task(id: store.loadKey + reloadID.uuidString) {
            let forced = forceReload; forceReload = false
            await store.reload(force: forced)
        }
        .task(id: filterKey) {
            let channels = store.catalog.channels, selected = group, text = query.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { do { try await Task.sleep(for: .milliseconds(200)) } catch { return } }
            let result = await Task.detached(priority: .userInitiated) {
                channels.filter { (selected == nil || $0.group == selected) &&
                    (text.isEmpty || $0.name.localizedStandardContains(text) || $0.group.localizedStandardContains(text)) }
            }.value
            guard !Task.isCancelled else { return }
            let changed = visible.map(\.id) != result.map(\.id)
            visible = result
            // A cache refresh on returning from AVKit must not override the restored channel focus.
            if changed, !editingSearch, sheet == nil, playing == nil { focusFirstChannel() }
        }
        .onChange(of: store.selectedID) { _, _ in group = nil; query = ""; lastChannel = nil }
        .onChange(of: store.storageLoading) { _, loading in if !loading { focus = initialFocus } }
        .onChange(of: focus) { _, value in
            if value == "search" { editingSearch = true }
            else if value != nil { editingSearch = false }
        }
        .onAppear { focus = initialFocus }
        .fullScreenCover(item: $sheet, onDismiss: restoreAfterModal) { item in
            Group {
                switch item {
                case .source(let source): TVIPTVSourceForm(existing: source).environmentObject(store)
                case .sources: TVIPTVSourcesView(edit: { sheet = .source($0) }).environmentObject(store)
                case .groups: TVIPTVGroupView(groups: store.catalog.groups, selection: $group)
                }
            }
            .environment(\.layoutDirection, .rightToLeft)
        }
        .fullScreenCover(item: $playing, onDismiss: restoreAfterPlayer) { channel in TVIPTVPlayerView(initial: channel).environmentObject(store) }
        .onDisappear { focusTask?.cancel() }
        // Let native keyboards, modal lists and AVKit consume Menu before this page's Home fallback.
        .onExitCommand(perform: sheet == nil && playing == nil ? { router.tab = .home } : nil)
    }
    private var header: some View {
        HStack(spacing: 24) {
            Label("IPTV", systemImage: "dot.radiowaves.left.and.right").font(.system(size: 48, weight: .bold))
                .accessibilityIdentifier("tv.iptv.heading")
            Spacer()
            Button { present(.source(nil), from: "add") } label: { Label("إضافة مصدر", systemImage: "plus") }
                .disabled(!store.storageAvailable).focused($focus, equals: "add").accessibilityIdentifier("tv.iptv.add")
            Button { router.tab = .home } label: { Image(systemName: "house") }
                .focused($focus, equals: "return").accessibilityLabel("العودة إلى الرئيسية").accessibilityIdentifier("tv.iptv.return")
        }.buttonBorderShape(.capsule).focusSection()
    }
    private var controls: some View {
        HStack(spacing: 24) {
            Button { present(.sources, from: "source") } label: { Label(store.selected?.name ?? "المصادر", systemImage: "slider.horizontal.3") }
                .focused($focus, equals: "source").accessibilityIdentifier("tv.iptv.sources")
            Button { present(.groups, from: "group") } label: { Label(group ?? "كل القنوات", systemImage: "line.3.horizontal.decrease") }
                .disabled(store.loading).focused($focus, equals: "group").accessibilityIdentifier("tv.iptv.groups")
            TextField("ابحث عن قناة", text: $query).textInputAutocapitalization(.never).autocorrectionDisabled()
                .focused($focus, equals: "search").accessibilityIdentifier("tv.iptv.search")
                .onSubmit { editingSearch = false; focus = initialFocus }
            Button(action: refresh) { Image(systemName: "arrow.clockwise") }
                .disabled(store.loading).focused($focus, equals: "refresh").accessibilityLabel("تحديث القنوات").accessibilityIdentifier("tv.iptv.refresh")
        }
        .onMoveCommand { if $0 == .down, let first = visible.first { focus = "channel." + first.id } }
        .focusSection()
    }
    private func card(_ channel: IPTVChannel) -> some View {
        HStack(spacing: 22) {
            ZStack {
                RoundedRectangle(cornerRadius: 18).fill(TVTheme.raised)
                Image(systemName: "tv").font(.system(size: 40)).foregroundStyle(.secondary)
                if let logo = channel.logo { AsyncImage(url: logo) { if let image = $0.image { image.resizable().scaledToFit() } } }
            }.frame(width: 96, height: 86).clipped().accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 12) {
                Text(channel.name).font(.system(size: 25, weight: .semibold)).lineLimit(2)
                Text(channel.group).font(.system(size: 20)).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(24).frame(maxWidth: .infinity, minHeight: 150, alignment: .leading)
        .background(TVTheme.panel, in: RoundedRectangle(cornerRadius: 24))
    }
    private func present(_ item: Sheet, from control: String) {
        lastControl = control; modalSource = store.selectedID; modalGroup = group; modalRevision = store.sourceRevision
        sheet = item
    }
    private func restoreAfterModal() {
        let changed = modalSource != store.selectedID || modalGroup != group || modalRevision != store.sourceRevision
        if changed { focusFirstChannel() }
        else { focus = lastControl }
    }
    private func focusFirstChannel() {
        focusTask?.cancel()
        focusTask = Task { @MainActor in
            // Allow native modal focus restoration/layout to finish before selecting the new grid.
            do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            guard sheet == nil, playing == nil, !editingSearch else { return }
            focus = initialFocus
            if !visible.isEmpty { resetFocus(in: gridScope) }
        }
    }
    private func restoreAfterPlayer() {
        focusTask?.cancel()
        focusTask = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            guard sheet == nil, playing == nil else { return }
            if let lastChannel, visible.contains(where: { $0.id == lastChannel }) { focus = "channel." + lastChannel }
            else { focus = initialFocus }
        }
    }
    private func refresh() { forceReload = true; reloadID = UUID() }
}

private struct TVIPTVGroupView: View {
    let groups: [String]
    @Binding var selection: String?
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focus: String?
    var body: some View {
        NavigationStack {
            List {
                Button { selection = nil; dismiss() } label: { row("كل القنوات", selected: selection == nil) }
                    .focused($focus, equals: "all").accessibilityIdentifier("tv.iptv.group.all")
                ForEach(groups, id: \.self) { group in
                    Button { selection = group; dismiss() } label: { row(group, selected: selection == group) }
                        .focused($focus, equals: group).accessibilityIdentifier("tv.iptv.group." + group)
                }
                Button("إغلاق") { dismiss() }
            }.buttonStyle(.bordered).navigationTitle("تصنيفات القنوات")
        }.defaultFocus($focus, selection ?? "all").onExitCommand { dismiss() }
    }
    private func row(_ text: String, selected: Bool) -> some View {
        HStack { Text(text); Spacer(); if selected { Image(systemName: "checkmark") } }
    }
}

private struct TVIPTVSourcesView: View {
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    let edit: (IPTVSource) -> Void
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
