import SwiftUI
import CinemaCore

/// Category/channel browsing with a continuing preview, independently implemented from public IPTV UI references.
struct TVIPTVLiveGuide: View {
    @ObservedObject var session: IPTVPlayerSession
    let selected: () -> Void
    let previewChanged: (CGRect) -> Void
    @State private var query = ""
    @State private var browsingGroup: String?
    @State private var visible: [IPTVChannel] = []
    @FocusState private var focus: String?
    init(session: IPTVPlayerSession, selected: @escaping () -> Void, previewChanged: @escaping (CGRect) -> Void) {
        self.session = session; self.selected = selected; self.previewChanged = previewChanged
        _browsingGroup = State(initialValue: session.group)
    }
    private var numbers: [String: Int] { IPTVNavigation.numbers(session.channels, sourceID: session.sourceID) }
    private var categories: [(name: String, count: Int)] { IPTVNavigation.categories(session.channels, sourceID: session.sourceID) }
    private var key: String { session.catalogRevision.uuidString + "|" + (browsingGroup ?? "") + "|" + query }
    private var groupID: String { "group." + (browsingGroup ?? "all") }
    var body: some View {
        let numbers = self.numbers
        let categories = self.categories
        return VStack(alignment: .leading, spacing: 22) {
            HStack {
                Text("القنوات").font(.title2.bold()); Text("اختيار للمعاينة · OK مرة أخرى لملء الشاشة").font(.system(size: 18)).foregroundStyle(.secondary); Spacer()
                Button("إغلاق", action: selected).font(.system(size: 22)).accessibilityIdentifier("tv.iptv.guide.close")
            }
            TextField("ابحث عن قناة", text: $query).font(.system(size: 23)).focused($focus, equals: "search")
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .multilineTextAlignment(.leading)
                .onSubmit { focus = IPTVNavigation.entry(currentID: session.channel.id, in: visible).map { "channel." + $0 } }
            HStack(alignment: .top, spacing: 20) {
                ScrollView {
                    LazyVStack(spacing: 16) {
                        category("كل القنوات", group: nil, count: session.channels.count)
                        ForEach(categories, id: \.name) { category($0.name, group: $0.name, count: $0.count) }
                    }.padding(10)
                }.frame(width: 230).focusSection()
                    .onMoveCommand { if $0 == .left { focus = IPTVNavigation.entry(currentID: session.channel.id, in: visible).map { "channel." + $0 } } }
                Divider()
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 16) {
                            ForEach(visible) { channel in
                                Button {
                                    session.group = browsingGroup
                                    if channel.id == session.channel.id { selected() } else { session.tune(channel) }
                                } label: {
                                    HStack(spacing: 14) {
                                        Text(String(numbers[channel.id] ?? 0)).font(.system(size: 18, design: .monospaced)).foregroundStyle(.secondary).frame(width: 46)
                                        ZStack {
                                            RoundedRectangle(cornerRadius: 10).fill(.white.opacity(0.06))
                                            Image(systemName: "tv").foregroundStyle(.secondary)
                                            if let logo = channel.logo { AsyncImage(url: logo) { if let image = $0.image { image.resizable().scaledToFit().padding(4) } } }
                                        }.frame(width: 58, height: 48).accessibilityHidden(true)
                                        VStack(alignment: .leading, spacing: 8) {
                                            Text(channel.name).font(.system(size: 23, weight: .semibold)).lineLimit(2)
                                            Text(channel.group).font(.system(size: 18)).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                        Spacer(minLength: 0)
                                        if channel.id == session.channel.id { Image(systemName: "checkmark") }
                                    }.frame(maxWidth: .infinity, alignment: .leading).padding(6)
                                }
                                .focused($focus, equals: "channel." + channel.id).id(channel.id)
                                .accessibilityLabel(channel.name).accessibilityIdentifier("tv.iptv.guide.channel." + channel.id)
                            }
                            if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.").font(.system(size: 22)) }
                        }.padding(12)
                    }.focusSection().onMoveCommand { if $0 == .right { focus = groupID } }
                    .task(id: key) {
                        let values = session.channels, source = session.sourceID, group = browsingGroup, text = query
                        let result = await Task.detached { IPTVNavigation.channels(values, sourceID: source, group: group, query: text) }.value
                        guard !Task.isCancelled else { return }; visible = result
                        await Task.yield()
                        let id = IPTVNavigation.entry(currentID: session.channel.id, in: result)
                        if let id { proxy.scrollTo(id, anchor: .center) }
                        guard focus != "search", focus?.hasPrefix("group.") != true else { return }
                        do { try await Task.sleep(for: .milliseconds(120)) } catch { return }
                        focus = id.map { "channel." + $0 } ?? groupID
                    }
                }
                Divider()
                VStack(alignment: .leading, spacing: 12) {
                    Color.clear.aspectRatio(16 / 9, contentMode: .fit)
                        .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .named("tv.iptv.player.space")) }, action: previewChanged)
                        .accessibilityElement().accessibilityLabel("معاينة القناة")
                        .accessibilityIdentifier("tv.iptv.guide.preview")
                    Text(session.channel.name).font(.system(size: 24, weight: .semibold)).lineLimit(2).accessibilityIdentifier("tv.iptv.guide.title")
                    Text(session.channel.group).font(.system(size: 20)).foregroundStyle(.secondary)
                    if let problem = session.problem {
                        Text(problem.replacingOccurrences(of: "على iOS", with: "على Apple TV")).font(.system(size: 18)).foregroundStyle(.secondary).lineLimit(2)
                        Button("إعادة المحاولة") { session.retry() }.font(.system(size: 22)).disabled(session.retrying)
                    } else {
                        Text(session.status).font(.system(size: 20)).foregroundStyle(.secondary)
                        Button("ملء الشاشة", action: selected).font(.system(size: 22)).accessibilityIdentifier("tv.iptv.guide.fullscreen")
                    }
                    Spacer()
                }.frame(maxWidth: .infinity).frame(width: 520).padding(8)
            }
        }
        .buttonStyle(.bordered).padding(32)
        .background(TVTheme.panel.opacity(0.78), in: RoundedRectangle(cornerRadius: 28))
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(.white.opacity(0.12)))
        .accessibilityIdentifier("tv.iptv.guide")
    }
    private func category(_ title: String, group: String?, count: Int) -> some View {
        Button { browsingGroup = group; query = "" } label: {
            HStack { Text(title).font(.system(size: 22)).lineLimit(2).minimumScaleFactor(0.8).layoutPriority(1); Spacer(minLength: 4); Text(String(count)).font(.system(size: 16)).foregroundStyle(.secondary); if browsingGroup == group { Image(systemName: "checkmark").font(.system(size: 18)) } }
                .frame(maxWidth: .infinity).padding(4)
        }.focused($focus, equals: "group." + (group ?? "all"))
            .accessibilityIdentifier("tv.iptv.guide.group." + (group ?? "all"))
    }
}
