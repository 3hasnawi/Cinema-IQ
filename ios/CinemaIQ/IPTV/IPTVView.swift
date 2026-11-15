import SwiftUI
import CinemaCore

struct IPTVView: View {
    @EnvironmentObject private var store: IPTVStore
    @State private var group: String?
    @State private var sheet: Sheet?
    @State private var browserInitial: IPTVChannel?
    private enum Sheet: Identifiable {
        case source(IPTVSource?), manage, groups
        var id: String { switch self { case .source(let source): return source?.id ?? "add"; case .manage: return "manage"; case .groups: return "groups" } }
    }
    var body: some View {
        Group {
            if let initial = browserInitial, initial.sourceID == store.selectedID {
                IPTVPlaybackView(initial: initial, group: group, startInBrowser: true).id(initial.sourceID)
            } else {
                List {
                    if store.storageLoading || store.loading {
                        HStack { ProgressView(); Text("جارٍ تحميل قنوات IPTV…") }
                    } else if !store.storageAvailable {
                        Text(store.message ?? "تعذّر الوصول إلى البيانات الآمنة.")
                        Button("إعادة المحاولة") { store.readSources() }
                    } else if store.sources.isEmpty {
                        ContentUnavailableView {
                            Label("أضف مصدر IPTV", systemImage: "dot.radiowaves.left.and.right")
                        } description: { Text("أضف رابط قائمة M3U أو اشتراك Xtream لعرض قنواتك وتشغيلها داخل التطبيق.") }
                        actions: { Button("إضافة مصدر") { sheet = .source(nil) }.accessibilityIdentifier("ios.iptv.empty.add") }
                            .listRowBackground(Color.clear)
                    } else if let message = store.message {
                        Text(message).foregroundStyle(.secondary).accessibilityIdentifier("ios.iptv.error")
                        Button("إعادة المحاولة") { Task { await store.reload(force: true) } }
                    } else { Text("لا تتوفر قنوات لهذا المصدر.").foregroundStyle(.secondary) }
                }
            }
        }
        .navigationTitle("IPTV").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { sheet = .groups } label: { Image(systemName: "line.3.horizontal.decrease") }
                    .disabled(store.catalog.channels.isEmpty).accessibilityLabel("التصنيف").accessibilityIdentifier("ios.iptv.groups")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("إدارة المصادر") { sheet = .manage }
                    Button("تحديث القنوات") { Task { await store.reload(force: true) } }
                } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel("مصادر IPTV").accessibilityIdentifier("ios.iptv.manage")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { sheet = .source(nil) } label: { Label("إضافة مصدر", systemImage: "plus") }
                    .disabled(!store.storageAvailable).accessibilityIdentifier("ios.iptv.add")
            }
        }
        .task(id: store.loadKey) { await store.reload() }
        .onChange(of: store.catalogRevision, initial: true) { _, _ in
            if browserInitial?.sourceID != store.selectedID, let source = store.selectedID {
                browserInitial = store.channelCollection.channels(store.catalog.channels, sourceID: source, section: .recent).first
                    ?? store.catalog.channels.first(where: { $0.sourceID == source })
            }
#if DEBUG
            IPTVFixtureSupport.markReady(channelCount: store.catalog.channels.count)
#endif
        }
        .onChange(of: store.selectedID) { _, _ in group = nil; browserInitial = nil }
        .sheet(item: $sheet) { item in
            switch item {
            case .source(let source): IPTVSourceForm(existing: source).environmentObject(store)
            case .groups: IPTVGroupPicker(groups: store.catalog.groups, selection: $group)
            case .manage: IPTVSourceManager(add: { sheet = .source(nil) }, edit: { sheet = .source($0) }).environmentObject(store)
            }
        }
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
