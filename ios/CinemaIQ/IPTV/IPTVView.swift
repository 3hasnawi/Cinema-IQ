import SwiftUI
import CinemaCore

struct IPTVView: View {
    @EnvironmentObject private var store: IPTVStore
    @State private var query = ""
    @State private var group: String?
    @State private var visible: [IPTVChannel] = []
    @State private var sheet: Sheet?
    @State private var playing: IPTVChannel?
    private enum Sheet: Identifiable {
        case source(IPTVSource?), manage, groups
        var id: String { switch self { case .source(let source): return source?.id ?? "add"; case .manage: return "manage"; case .groups: return "groups" } }
    }
    private struct FilterKey: Hashable { let revision: UUID; let query: String; let group: String? }
    private var filterKey: FilterKey { FilterKey(revision: store.catalogRevision, query: query, group: group) }
    var body: some View {
        List {
            if !store.storageAvailable {
                Section {
                    Text(store.message ?? "تعذّر الوصول إلى البيانات الآمنة.")
                    Button("إعادة المحاولة") { store.readSources() }
                }
            } else if store.sources.isEmpty {
                ContentUnavailableView {
                    Label("أضف مصدر IPTV", systemImage: "dot.radiowaves.left.and.right")
                } description: { Text("أضف رابط قائمة M3U أو اشتراك Xtream لعرض قنواتك وتشغيلها داخل التطبيق.") }
                actions: { Button("إضافة مصدر") { sheet = .source(nil) }.accessibilityIdentifier("ios.iptv.empty.add") }
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    Picker("المصدر", selection: $store.selectedID) {
                        ForEach(store.sources) { source in Text(source.name).tag(Optional(source.id)) }
                    }.accessibilityIdentifier("ios.iptv.source")
                    Button { sheet = .manage } label: { Label("إدارة المصادر", systemImage: "slider.horizontal.3") }
                        .accessibilityIdentifier("ios.iptv.manage")
                    Button { sheet = .groups } label: {
                        HStack { Label("التصنيف", systemImage: "line.3.horizontal.decrease"); Spacer(); Text(group ?? "كل القنوات").foregroundStyle(.secondary).lineLimit(1) }
                    }.disabled(store.loading || store.catalog.channels.isEmpty).accessibilityIdentifier("ios.iptv.groups")
                }
                if store.loading {
                    Section { HStack { ProgressView(); Text("جارٍ تحميل القنوات…") } }
                } else if let message = store.message {
                    Section {
                        Text(message).foregroundStyle(.secondary).accessibilityIdentifier("ios.iptv.error")
                        Button("إعادة المحاولة") { Task { await store.reload(force: true) } }
                    }
                } else {
                    Section("\(visible.count) قناة") {
                        ForEach(visible) { channel in
                            Button { playing = channel } label: {
                                HStack(spacing: 12) {
                                    channelLogo(channel)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(channel.name).font(.body.weight(.medium)).foregroundStyle(.primary).lineLimit(2)
                                        Text(channel.group).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    Spacer(minLength: 8)
                                    Image(systemName: "play.circle.fill").font(.title3).foregroundStyle(Theme.accent)
                                }.padding(.vertical, 3)
                            }
                            .accessibilityLabel(channel.name).accessibilityIdentifier("ios.iptv.channel." + channel.id)
                        }
                        if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.").foregroundStyle(.secondary) }
                    }
                }
            }
        }
        .navigationTitle("IPTV")
        .searchable(text: $query, prompt: "ابحث عن قناة")
        .scrollDismissesKeyboard(.interactively)
        .refreshable { await store.reload(force: true) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { sheet = .source(nil) } label: { Label("إضافة مصدر", systemImage: "plus") }
                    .disabled(!store.storageAvailable).accessibilityIdentifier("ios.iptv.add")
            }
        }
        .task(id: store.loadKey) { await store.reload() }
        .task(id: filterKey) {
            let values = store.catalog.channels, selected = group, text = query.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { do { try await Task.sleep(for: .milliseconds(200)) } catch { return } }
            let result = await Task.detached(priority: .userInitiated) {
                values.filter { (selected == nil || $0.group == selected) &&
                    (text.isEmpty || $0.name.localizedStandardContains(text) || $0.group.localizedStandardContains(text)) }
            }.value
            guard !Task.isCancelled else { return }
            visible = result
        }
        .onChange(of: store.selectedID) { _, _ in group = nil; query = "" }
        .sheet(item: $sheet) { item in
            switch item {
            case .source(let source): IPTVSourceForm(existing: source).environmentObject(store)
            case .groups: IPTVGroupPicker(groups: store.catalog.groups, selection: $group)
            case .manage: IPTVSourceManager(add: { sheet = .source(nil) }, edit: { sheet = .source($0) }).environmentObject(store)
            }
        }
        .fullScreenCover(item: $playing) { channel in
            ResponsiveContainer { IPTVPlaybackView(initial: channel).environmentObject(store) }
        }
    }
    private func channelLogo(_ channel: IPTVChannel) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Theme.panel)
            Image(systemName: "tv").foregroundStyle(.secondary)
            if let logo = channel.logo {
                AsyncImage(url: logo) { phase in if let image = phase.image { image.resizable().scaledToFit() } }
            }
        }.frame(width: 52, height: 44).clipped().accessibilityHidden(true)
    }
}

private struct IPTVGroupPicker: View {
    let groups: [String]
    @Binding var selection: String?
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List {
                Button { selection = nil; dismiss() } label: { row("كل القنوات", selected: selection == nil) }
                ForEach(groups.filter { search.isEmpty || $0.localizedStandardContains(search) }, id: \.self) { group in
                    Button { selection = group; dismiss() } label: { row(group, selected: selection == group) }
                }
            }
            .navigationTitle("تصنيفات القنوات").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "ابحث عن تصنيف")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { dismiss() } } }
        }
    }
    private func row(_ title: String, selected: Bool) -> some View {
        HStack { Text(title); Spacer(); if selected { Image(systemName: "checkmark") } }
    }
}

private struct IPTVSourceManager: View {
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    let add: () -> Void
    let edit: (IPTVSource) -> Void
    @State private var deleting: IPTVSource?
    var body: some View {
        NavigationStack {
            List {
                ForEach(store.sources) { source in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Label(source.name, systemImage: source.kind == .playlist ? "link" : "person.crop.rectangle")
                            Spacer()
                            if store.selectedID == source.id { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                        }
                        Text(source.address.host ?? "").font(.caption).foregroundStyle(.secondary)
                        HStack {
                            Button("اختيار") { store.selectedID = source.id; dismiss() }.buttonStyle(.borderless)
                            Button("تعديل") { edit(source) }.buttonStyle(.borderless)
                            Spacer()
                            Button("حذف", role: .destructive) { deleting = source }.buttonStyle(.borderless)
                        }.font(.subheadline)
                    }.padding(.vertical, 6)
                }
                if let message = store.message { Text(message).foregroundStyle(.secondary) }
            }
            .navigationTitle("مصادر IPTV").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) { Button(action: add) { Label("إضافة", systemImage: "plus") } }
            }
            .confirmationDialog("حذف المصدر من هذا الجهاز؟", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button("حذف المصدر", role: .destructive) { if let source = deleting { store.remove(source) }; deleting = nil }
                Button("إلغاء", role: .cancel) { deleting = nil }
            }
        }
    }
}
