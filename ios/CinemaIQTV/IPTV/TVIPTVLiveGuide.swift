import SwiftUI
import CinemaCore

struct TVIPTVLiveGuide: View {
    @ObservedObject var session: IPTVPlayerSession
    @EnvironmentObject private var store: IPTVStore
    @EnvironmentObject private var access: TVIPTVCategoryAccess
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let index: IPTVTelevisionGuide
    let sources: () -> Void
    let selected: (IPTVChannel, IPTVChannelSection, String?) -> Void
    let close: () -> Void
    @State private var section: IPTVChannelSection
    @State private var visit: String?
    @State private var pending: String?
    @State private var categoriesOpen = false
    @State private var managing = false
    @State private var search = false
    @State private var query = ""
    @State private var results: [IPTVChannel] = []
    @State private var lastID: String
    @State private var targetGroup: String?
    @State private var focusRequest = 0
    @FocusState private var focus: String?
    init(session: IPTVPlayerSession, index: IPTVTelevisionGuide, sources: @escaping () -> Void,
         selected: @escaping (IPTVChannel, IPTVChannelSection, String?) -> Void, close: @escaping () -> Void) {
        self.session = session; self.index = index; self.sources = sources; self.selected = selected; self.close = close
        _lastID = State(initialValue: session.channel.id)
        let group = session.section.group
        let locked = group.flatMap { index.rules.locked.contains($0) && !index.rules.hidden.contains($0) ? $0 : nil }
        _section = State(initialValue: locked == nil ? session.section : .all)
        _pending = State(initialValue: locked)
        _targetGroup = State(initialValue: locked)
    }

    private var rows: [IPTVChannel] {
        if !query.isEmpty { return results }
        return index.rows(section: section, visit: visit, favorites: store.channelCollection.favorites)
    }
    private var groupFocus: String { targetGroup.map { "group.named." + $0 } ?? (section == .favorites ? "group.favorites" : "group.all") }
    private var animation: Animation? { reduceMotion ? nil : .easeOut(duration: 0.18) }
    var body: some View {
        GeometryReader { size in
            ZStack(alignment: .trailing) {
                LinearGradient(stops: [
                    .init(color: .clear, location: 0), .init(color: TVIPTVStyle.guideInk.opacity(0.24), location: 0.15),
                    .init(color: TVIPTVStyle.guideInk.opacity(0.75), location: 0.38), .init(color: TVIPTVStyle.guideInk.opacity(0.91), location: 0.62),
                    .init(color: TVIPTVStyle.guideInk.opacity(0.97), location: 1)
                ], startPoint: .leading, endPoint: .trailing)
                    .frame(width: size.size.width * (categoriesOpen ? 0.82 : 0.61)).allowsHitTesting(false)
                HStack(spacing: 48) {
                    channelPanel.frame(width: size.size.width * 0.32)
                    if categoriesOpen {
                        categoryPanel.frame(width: size.size.width * 0.19)
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }.padding(.trailing, size.size.width * 0.032).padding(.top, 48).padding(.bottom, 36)
                    .disabled(pending != nil)
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                .overlay {
                    if let group = pending {
                        TVIPTVPinPad(creating: false, submit: { pin in
                            guard access.matches(pin), !access.rules(index.sourceID).hidden.contains(group) else { return false }
                            pending = nil; visit = group; choose(.group(group)); return true
                        }, cancel: { pending = nil; openCategories() })
                    }
                }
        }.environment(\.layoutDirection, .leftToRight)
            .onExitCommand(perform: pending == nil && !managing ? back : nil)
            .onChange(of: focus) { _, value in
                if let value, value.hasPrefix("channel.") { lastID = String(value.dropFirst(8)) }
            }
            .onChange(of: index.rules) { _, _ in
                visit = nil; section = .all; query = ""; results = []; focusRequest += 1
            }
            .fullScreenCover(isPresented: $managing, onDismiss: { focus = "manage" }) {
                TVIPTVCategoryManager(source: index.sourceID, categories: index.categories) { managing = false }
                    .environmentObject(access)
            }
    }
    private var channelPanel: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("القنوات").font(.system(size: 36, weight: .bold)).accessibilityIdentifier("tv.iptv.guide")
                    Text("\(rows.count) قناة").font(.system(size: 22)).foregroundStyle(.secondary)
                }
                Spacer()
                icon("البحث", "magnifyingglass", "search") {
                    visit = nil; section = .all; targetGroup = nil; categoriesOpen = false
                    search = true; focusRequest += 1
                }
                icon("إدارة التصنيفات", "ellipsis", "manage") { visit = nil; section = .all; managing = true }
                icon("المصادر والاشتراكات", "gearshape", "sources", sources)
            }
            if search {
                TextField("ابحث عن قناة", text: $query).font(.system(size: 26))
                    .focused($focus, equals: "editor").textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("tv.iptv.guide.editor").submitLabel(.search).onSubmit { finishSearch() }
                HStack {
                    Button("تم", action: finishSearch).focused($focus, equals: "done")
                    Button("إلغاء", action: clearSearch)
                }.font(.system(size: 24))
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 7) {
                        ForEach(rows) { channel in
                            Button {
                                guard access.rules(index.sourceID).allows(channel.group, visit: visit) else { return }
                                selected(channel, section, visit)
                            } label: {
                                HStack(spacing: 16) {
                                    TVIPTVLogo(channel: channel).frame(width: 72, height: 64)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(channel.televisionTitle).font(.system(size: 28, weight: .semibold)).lineLimit(1)
                                        Text(channel.group).font(.system(size: 20)).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
                                    }
                                    Spacer(minLength: 4)
                                    if channel.id == session.channel.id { Image(systemName: "waveform").foregroundStyle(.pink) }
                                    Text(String(index.numbers[channel.id] ?? 0)).font(.system(size: 22).monospacedDigit())
                                }.padding(.horizontal, 16).frame(maxWidth: .infinity, minHeight: 86)
                            }.buttonStyle(TVIPTVGuideButtonStyle()).focusEffectDisabled()
                                .focused($focus, equals: "channel." + channel.id).id(channel.id)
                                .accessibilityLabel(channel.televisionTitle).accessibilityIdentifier("tv.iptv.guide.channel." + channel.id)
                                .onMoveCommand {
                                    if $0 == .right { openCategories() }
                                    if $0 == .up && rows.first?.id == channel.id { focus = "search" }
                                }
                        }
                        if rows.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.").font(.caption) }
                    }
                }.frame(maxHeight: .infinity).scrollIndicators(.hidden)
                    .task(id: focusRequest) {
                        if pending != nil || managing { return }
                        if categoriesOpen { return }
                        if search { await Task.yield(); focus = "editor"; return }
                        let id = IPTVNavigation.entry(currentID: lastID, in: rows)
                        if let id { proxy.scrollTo(id, anchor: .center) }
                        do { try await Task.sleep(for: .milliseconds(90)) } catch { return }
                        guard !categoriesOpen, !search, pending == nil, !managing else { return }
                        focus = id.map { "channel." + $0 } ?? "search"
                    }
            }
            HStack {
                Text("OK للتشغيل").font(.system(size: 22)).foregroundStyle(.secondary)
                Spacer()
                Button("التصنيفات  ›", action: openCategories).buttonStyle(TVIPTVGuideButtonStyle())
                    .focused($focus, equals: "categories").accessibilityIdentifier("tv.iptv.guide.categories")
            }.font(.system(size: 24)).frame(height: 64)
        }.environment(\.layoutDirection, .rightToLeft)
            .task(id: SearchKey(query: query, revision: index.revision)) {
                guard !query.isEmpty else { results = []; return }
                let text = query, snapshot = index
                let found = await Task.detached { snapshot.rows(section: .all, visit: nil, favorites: [], query: text) }.value
                guard !Task.isCancelled, query == text else { return }
                results = found
                if !search { focusRequest += 1 }
            }
    }
    private struct SearchKey: Hashable { let query: String; let revision: UUID }
    private func icon(_ title: String, _ symbol: String, _ id: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 28)).frame(width: 68, height: 74) }
            .buttonStyle(TVIPTVGuideButtonStyle()).focusEffectDisabled().focused($focus, equals: id)
            .accessibilityLabel(title).accessibilityIdentifier("tv.iptv.guide." + id)
    }
    private var categoryPanel: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("التصنيفات").font(.system(size: 34, weight: .bold)).accessibilityIdentifier("tv.iptv.guide.groups")
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        category(.all, "all", count: index.unrestricted.count)
                        category(.favorites, "favorites", count: nil)
                        ForEach(index.categories) { group in
                            if !index.rules.hidden.contains(group.name) { category(.group(group.name), "named." + group.name, count: group.count) }
                        }
                    }
                }.scrollIndicators(.hidden).task(id: groupFocus) {
                    let target = groupFocus; proxy.scrollTo(target, anchor: .center)
                    do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                    if categoriesOpen && pending == nil { focus = target }
                }
            }
            Button("رجوع إلى القنوات", action: restoreChannels).buttonStyle(TVIPTVGuideButtonStyle(category: true)).font(.caption)
        }.frame(maxHeight: .infinity).environment(\.layoutDirection, .rightToLeft)
            .onMoveCommand { if $0 == .left { restoreChannels() } }
    }
    private func category(_ value: IPTVChannelSection, _ id: String, count: Int?) -> some View {
        Button {
            visit = nil
            if let name = value.group, access.rules(index.sourceID).locked.contains(name) { targetGroup = name; pending = name }
            else { choose(value) }
        } label: {
            HStack(spacing: 8) {
                if let group = value.group, index.rules.locked.contains(group) { Image(systemName: "lock.fill") }
                Text(value.title).lineLimit(1)
                Spacer(minLength: 4)
                if let count { Text(String(count)).font(.system(size: 21)) }
            }.padding(.horizontal, 14).frame(maxWidth: .infinity, minHeight: 78).font(.system(size: 26))
        }.buttonStyle(TVIPTVGuideButtonStyle(category: true, selected: section == value)).focusEffectDisabled()
            .focused($focus, equals: "group." + id).id("group." + id)
            .accessibilityIdentifier("tv.iptv.guide.group." + (value.group ?? id))
            .onMoveCommand { if $0 == .left { restoreChannels() } }
    }
    private func openCategories() {
        targetGroup = pending ?? section.group ?? targetGroup
        if visit != nil { visit = nil; section = .all }
        withAnimation(animation) { categoriesOpen = true }; focusRequest += 1
        if categoriesOpen { focus = groupFocus }
    }
    private func choose(_ value: IPTVChannelSection) {
        section = value; targetGroup = value.group; lastID = ""; query = ""; results = []
        restoreChannels()
    }
    private func restoreChannels() { withAnimation(animation) { categoriesOpen = false }; focusRequest += 1 }
    private func finishSearch() { focus = nil; search = false; lastID = ""; focusRequest += 1 }
    private func clearSearch() { query = ""; results = []; search = false; focus = "search" }
    private func back() {
        if search || !query.isEmpty { clearSearch() }
        else if categoriesOpen { restoreChannels() }
        else { close() }
    }
}
