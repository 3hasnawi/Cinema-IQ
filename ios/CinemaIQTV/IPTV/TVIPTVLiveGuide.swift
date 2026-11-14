import SwiftUI
import CinemaCore

/// Two focus sections over the continuing video: categories on the right and a virtualized channel list.
struct TVIPTVLiveGuide: View {
    @ObservedObject var session: IPTVPlayerSession
    let selected: () -> Void
    @State private var query = ""
    @State private var visible: [IPTVChannel] = []
    @FocusState private var focus: String?
    private var key: String { session.catalogRevision.uuidString + "|" + (session.group ?? "") + "|" + query }
    private var groupID: String { "group." + (session.group ?? "all") }
    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack {
                Text("القنوات").font(.title2.bold()); Spacer()
                Button("إغلاق", action: selected).font(.system(size: 22)).accessibilityIdentifier("tv.iptv.guide.close")
            }
            TextField("ابحث عن قناة", text: $query).font(.system(size: 23)).focused($focus, equals: "search")
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .multilineTextAlignment(.leading)
                .onSubmit { focus = IPTVNavigation.entry(currentID: session.channel.id, in: visible).map { "channel." + $0 } }
            HStack(alignment: .top, spacing: 20) {
                ScrollView {
                    LazyVStack(spacing: 16) {
                        category("كل القنوات", group: nil)
                        ForEach(session.groups, id: \.self) { category($0, group: $0) }
                    }.padding(10)
                }.frame(width: 270).focusSection()
                    .onMoveCommand { if $0 == .left { focus = IPTVNavigation.entry(currentID: session.channel.id, in: visible).map { "channel." + $0 } } }
                Divider()
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 16) {
                            ForEach(visible) { channel in
                                Button { session.tune(channel); selected() } label: {
                                    HStack(spacing: 14) {
                                        Image(systemName: channel.id == session.channel.id ? "waveform" : "tv")
                                        VStack(alignment: .leading, spacing: 8) {
                                            Text(channel.name).font(.system(size: 23, weight: .semibold)).lineLimit(2)
                                            Text(channel.group).font(.system(size: 18)).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                        Spacer(minLength: 0)
                                        if channel.id == session.channel.id { Image(systemName: "checkmark") }
                                    }.frame(maxWidth: .infinity, alignment: .leading).padding(6)
                                }
                                .focused($focus, equals: "channel." + channel.id).id(channel.id)
                                .accessibilityIdentifier("tv.iptv.guide.channel." + channel.id)
                            }
                            if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.").font(.system(size: 22)) }
                        }.padding(12)
                    }.focusSection().onMoveCommand { if $0 == .right { focus = groupID } }
                    .task(id: key) {
                        let values = session.channels, source = session.sourceID, group = session.group, text = query
                        let result = await Task.detached { IPTVNavigation.channels(values, sourceID: source, group: group, query: text) }.value
                        guard !Task.isCancelled else { return }; visible = result
                        guard focus != "search" else { return }
                        await Task.yield()
                        let id = IPTVNavigation.entry(currentID: session.channel.id, in: result)
                        if let id { proxy.scrollTo(id, anchor: .center) }
                        do { try await Task.sleep(for: .milliseconds(120)) } catch { return }
                        focus = id.map { "channel." + $0 } ?? groupID
                    }
                }
            }
        }
        .buttonStyle(.bordered).padding(32)
        .background(TVTheme.panel.opacity(0.78), in: RoundedRectangle(cornerRadius: 28))
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(.white.opacity(0.12)))
        .accessibilityIdentifier("tv.iptv.guide")
    }
    private func category(_ title: String, group: String?) -> some View {
        Button { session.group = group; query = "" } label: {
            HStack { Text(title).font(.system(size: 22)).lineLimit(2).minimumScaleFactor(0.8).layoutPriority(1); Spacer(minLength: 4); if session.group == group { Image(systemName: "checkmark").font(.system(size: 18)) } }
                .frame(maxWidth: .infinity).padding(4)
        }.focused($focus, equals: "group." + (group ?? "all"))
            .accessibilityIdentifier("tv.iptv.guide.group." + (group ?? "all"))
    }
}
