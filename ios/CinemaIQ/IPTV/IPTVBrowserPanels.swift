import SwiftUI
import CinemaCore

/// The phone/tablet browser keeps its video above two independently scrolling lists.
struct IPTVBrowserPanels: View {
    @ObservedObject var session: IPTVPlayerSession
    @EnvironmentObject private var store: IPTVStore
    @Binding var section: IPTVChannelSection
    @Binding var query: String
    let choose: (IPTVChannel) -> Void
    @State private var visible: [IPTVChannel] = []
    @State private var numbering: [String: Int] = [:]
    @State private var groups: [(name: String, count: Int)] = []
    private struct FilterKey: Equatable {
        let revision: UUID; let section: IPTVChannelSection; let query: String; let collection: IPTVChannelCollection
    }
    private var key: FilterKey { .init(revision: session.catalogRevision, section: section, query: query, collection: store.channelCollection) }
    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 0) {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        category(.recent, icon: "clock.arrow.circlepath")
                        category(.favorites, icon: "star.fill")
                        category(.all, icon: "tv")
                        ForEach(groups, id: \.name) { category(.group($0.name), icon: "play.tv", count: $0.count) }
                    }
                }.frame(width: min(210, max(132, geometry.size.width * 0.39))).background(Color.black.opacity(0.28))
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(visible) { channel in
                                Button { choose(channel) } label: {
                                    HStack(spacing: 10) {
                                        ZStack {
                                            RoundedRectangle(cornerRadius: 7).fill(.white.opacity(0.05))
                                            Image(systemName: "tv").foregroundStyle(.secondary)
                                            if let logo = channel.logo { AsyncImage(url: logo) { if let image = $0.image { image.resizable().scaledToFit().padding(3) } } }
                                        }.frame(width: 38, height: 38).accessibilityHidden(true)
                                        Text(channel.name).font(.system(size: 15, weight: .medium)).foregroundStyle(.primary).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                                        if channel.id == session.channel.id { Image(systemName: "waveform").foregroundStyle(Theme.accent).font(.caption) }
                                        else { Text(String(numbering[channel.id] ?? 0)).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
                                    }.padding(.horizontal, 12).frame(minHeight: 62)
                                        .background(channel.id == session.channel.id ? Theme.accent.opacity(0.12) : Color.clear)
                                }.buttonStyle(.plain).id(channel.id).accessibilityLabel(channel.name)
                                    .accessibilityIdentifier("ios.iptv.channel." + channel.id)
                            }
                            if visible.isEmpty { Text("لا توجد قنوات في هذا القسم.").font(.subheadline).foregroundStyle(.secondary).padding(24) }
                        }
                    }
                    .task(id: key) {
                        let channels = session.channels, source = session.sourceID, collection = store.channelCollection, selected = section, text = query
                        let result = await Task.detached { collection.channels(channels, sourceID: source, section: selected, query: text) }.value
                        guard !Task.isCancelled else { return }; visible = result
                        await Task.yield()
                        if let id = IPTVNavigation.entry(currentID: session.channel.id, in: result) { proxy.scrollTo(id, anchor: .center) }
                    }
                }.background(Theme.panel)
            }
            // Match the reference's physical columns while Arabic paragraphs keep their own direction.
            .environment(\.layoutDirection, .leftToRight)
        }
        .task(id: session.catalogRevision) {
            let values = session.channels, source = session.sourceID
            let metadata = await Task.detached { (IPTVNavigation.numbers(values, sourceID: source), IPTVNavigation.categories(values, sourceID: source)) }.value
            guard !Task.isCancelled else { return }; numbering = metadata.0; groups = metadata.1
        }
    }
    private func category(_ value: IPTVChannelSection, icon: String, count: Int? = nil) -> some View {
        Button { section = value; query = "" } label: {
            HStack(spacing: 10) {
                Image(systemName: icon).frame(width: 22).foregroundStyle(value == .favorites ? .yellow : Theme.accent)
                Text(value.title).font(.system(size: 15, weight: .medium)).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                if let count { Text(String(count)).font(.caption2).foregroundStyle(.secondary) }
            }.padding(.horizontal, 12).frame(minHeight: 60)
                .background(section == value ? Color.white.opacity(0.1) : Color.clear)
        }.buttonStyle(.plain).accessibilityIdentifier("ios.iptv.browser.section." + value.title).accessibilityLabel(value.title).accessibilityValue(section == value ? "محدد" : "غير محدد")
    }
}
