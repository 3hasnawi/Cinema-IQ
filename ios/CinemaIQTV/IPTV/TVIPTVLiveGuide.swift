import SwiftUI
import CinemaCore

/// Floating channels over the unchanged full-screen video; physical Right reveals categories on the right.
struct TVIPTVLiveGuide: View {
    @ObservedObject var session: IPTVPlayerSession
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let selected: () -> Void
    @State private var query = ""
    @State private var section: IPTVChannelSection
    @State private var categoriesOpen = false
    @State private var visible: [IPTVChannel] = []
    @State private var categories: [(name: String, count: Int)] = []
    @State private var lastFocused: String
    @State private var focusTask: Task<Void, Never>?
    @FocusState private var focus: String?
    private struct FilterKey: Hashable { let revision: UUID; let section: IPTVChannelSection; let query: String; let favorites: Set<String> }
    init(session: IPTVPlayerSession, selected: @escaping () -> Void) {
        self.session = session; self.selected = selected
        _section = State(initialValue: session.section); _lastFocused = State(initialValue: session.channel.id)
    }
    private var groupID: String {
        switch section { case .all: return "group.all"; case .favorites: return "group.favorites"; case .group(let name): return "group." + name; case .recent: return "group.all" }
    }
    private func restoreChannels() {
        focusTask?.cancel()
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.15)) { categoriesOpen = false }
        focusTask = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            focus = IPTVNavigation.entry(currentID: lastFocused, in: visible).map { "channel." + $0 } ?? "categories"
        }
    }
    private func openCategories() {
        focusTask?.cancel()
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.15)) { categoriesOpen = true }
        focusTask = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(160)) } catch { return }
            guard categoriesOpen else { return }; focus = groupID
        }
    }
    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 28) {
                channelPanel.frame(width: min(840, max(600, geometry.size.width * 0.36)))
                    .onMoveCommand { if $0 == .right && focus != "search" { openCategories() } }
                if categoriesOpen {
                    categoryPanel.frame(width: min(540, max(380, geometry.size.width * 0.22)))
                        .onMoveCommand { if $0 == .left { restoreChannels() } }
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .frame(height: geometry.size.height * 0.82)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing).padding(.horizontal, 48)
        }
        .environment(\.layoutDirection, .leftToRight)
        .onChange(of: query) { _, value in if value.count > 200 { query = String(value.prefix(200)) } }
        .onChange(of: focus) { _, value in if let value, value.hasPrefix("channel.") { lastFocused = String(value.dropFirst(8)) } }
        .onExitCommand { if categoriesOpen { restoreChannels() } else { selected() } }
        .onDisappear { focusTask?.cancel() }
        .task(id: session.catalogRevision) {
            let channels = session.channels, source = session.sourceID
            let values = await Task.detached { IPTVNavigation.categories(channels, sourceID: source) }.value
            guard !Task.isCancelled else { return }; categories = values
        }
    }
    private var channelPanel: some View {
        VStack(spacing: 24) {
            HStack {
                Text("القنوات").font(.system(size: 44, weight: .bold)).accessibilityIdentifier("tv.iptv.guide"); Spacer()
                Button("إغلاق", action: selected).font(.system(size: 26)).accessibilityIdentifier("tv.iptv.guide.close")
            }
            TextField("ابحث عن قناة", text: $query).font(.system(size: 26)).focused($focus, equals: "search")
                .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("tv.iptv.guide.search")
                .onSubmit { restoreChannels() }
                .onMoveCommand { if $0 == .down { restoreChannels() } }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(visible) { channel in
                            Button {
                                session.section = section; session.group = section.group
                                if channel.id != session.channel.id { session.tune(channel) }
                                selected()
                            } label: {
                                HStack(spacing: 18) {
                                    TVIPTVLogo(channel: channel).frame(width: 100, height: 86)
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(channel.name).font(.system(size: 30, weight: .semibold)).lineLimit(2)
                                        Text(channel.group).font(.system(size: 24)).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                    if channel.id == session.channel.id { Image(systemName: "checkmark").font(.system(size: 26)) }
                                    Text(String(session.numbers[channel.id] ?? 0)).font(.system(size: 24, design: .monospaced)).foregroundStyle(.white.opacity(0.6))
                                }.frame(maxWidth: .infinity, minHeight: 96, alignment: .leading).padding(12)
                            }
                            .buttonStyle(TVIPTVTrackButtonStyle()).focusEffectDisabled()
                            .focused($focus, equals: "channel." + channel.id).id(channel.id)
                            .accessibilityLabel(channel.name).accessibilityIdentifier("tv.iptv.guide.channel." + channel.id)
                            .onMoveCommand { direction in
                                if direction == .right { openCategories() }
                                else if direction == .up && visible.first?.id == channel.id { focus = "search" }
                            }
                        }
                        if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.").font(.system(size: 26)).foregroundStyle(.white.opacity(0.65)) }
                    }.padding(10)
                }.focusSection()
                    .task(id: FilterKey(revision: session.catalogRevision, section: section, query: query, favorites: store.channelCollection.favorites)) {
                        let channels = session.channels, source = session.sourceID, selection = section, text = query, collection = store.channelCollection
                        if !text.isEmpty { do { try await Task.sleep(for: .milliseconds(150)) } catch { return } }
                        let result = await Task.detached { collection.channels(channels, sourceID: source, section: selection, query: text) }.value
                        guard !Task.isCancelled else { return }; visible = result
                        let id = IPTVNavigation.entry(currentID: lastFocused, in: result)
                        await Task.yield()
                        guard !Task.isCancelled else { return }
                        if let id { proxy.scrollTo(id, anchor: .center) }
                        guard focus != "search", !categoriesOpen else { return }
                        do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                        focus = id.map { "channel." + $0 } ?? "categories"
                    }
            }
            Button("التصنيفات  ›", action: openCategories).font(.system(size: 26)).focused($focus, equals: "categories")
                .accessibilityIdentifier("tv.iptv.guide.categories")
        }
        .padding(32).background(TVIPTVStyle.panel.opacity(0.86), in: RoundedRectangle(cornerRadius: 40))
        .overlay(RoundedRectangle(cornerRadius: 40).stroke(.white.opacity(0.16), lineWidth: 1))
        .environment(\.layoutDirection, .rightToLeft)
    }
    private var categoryPanel: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("التصنيفات").font(.system(size: 42, weight: .bold)).accessibilityIdentifier("tv.iptv.guide.groups")
            ScrollView {
                LazyVStack(spacing: 20) {
                    category("كل القنوات", value: .all, count: session.channels.count)
                    category("المفضلة", value: .favorites, count: store.channelCollection.favorites.filter { session.numbers[$0] != nil }.count)
                    ForEach(categories, id: \.name) { category($0.name, value: .group($0.name), count: $0.count) }
                }.padding(10)
            }.focusSection()
            Button("رجوع", action: restoreChannels).font(.system(size: 26))
        }
        .padding(32).background(TVIPTVStyle.panel.opacity(0.88), in: RoundedRectangle(cornerRadius: 40))
        .overlay(RoundedRectangle(cornerRadius: 40).stroke(.white.opacity(0.16), lineWidth: 1))
        .environment(\.layoutDirection, .rightToLeft)
    }
    private func category(_ title: String, value: IPTVChannelSection, count: Int) -> some View {
        let id: String
        switch value { case .all: id = "all"; case .favorites: id = "favorites"; case .group(let name): id = name; case .recent: id = "all" }
        return Button {
            section = value; query = ""; lastFocused = session.channel.id
            restoreChannels()
        } label: {
            HStack(spacing: 10) {
                Text(title).font(.system(size: 28)).lineLimit(2).minimumScaleFactor(0.8)
                Spacer(minLength: 4); Text(String(count)).font(.system(size: 24)).foregroundStyle(.white.opacity(0.65))
                if section == value { Image(systemName: "checkmark") }
            }.padding(20).frame(maxWidth: .infinity, minHeight: 76)
        }.buttonStyle(TVIPTVTrackButtonStyle()).focusEffectDisabled().focused($focus, equals: "group." + id)
            .accessibilityIdentifier("tv.iptv.guide.group." + id)
            .onMoveCommand { if $0 == .left { restoreChannels() } }
    }
}
