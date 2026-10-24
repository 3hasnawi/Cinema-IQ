import SwiftUI
import AVKit
import CinemaCore

/// Full-screen player: the system tvOS player (Siri Remote scrubbing, info and audio panels) with Cinema iQ's
/// menus in its transport bar: quality, subtitles (the source's files, size and background) and speed. The next
/// episode appears as a contextual button near the end.
struct TVPlayerView: View {
    let selection: PlaybackSelection
    @StateObject private var controller = PlaybackController()
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var active: Media?
    /// The season list when playback started without one (from continue watching).
    @State private var fetchedQueue: [Media] = []
    @State private var streams: [StreamChoice] = []
    @State private var quality: String?
    @State private var externalTracks: [SubtitleTrack] = []
    @State private var loading = false
    @State private var resolutionError: String?
    @AppStorage(SubtitlePreference.sizeKey) private var subtitleSize = 20.0
    @AppStorage(SubtitlePreference.languageKey) private var subtitleLanguage = "ar"
    @AppStorage(SubtitlePreference.backgroundKey) private var subtitleBackground = 0.5

    private var current: Media { active ?? selection.media }
    private var queue: [Media] { selection.queue.isEmpty ? fetchedQueue : selection.queue }
    private var next: Media? { following(current) }
    private func following(_ media: Media) -> Media? {
        let list = queue
        guard let index = list.firstIndex(where: { $0.id == media.id }), list.indices.contains(index + 1) else { return nil }
        return list[index + 1]
    }
    private var offerNext: Bool {
        guard next != nil else { return false }
        if controller.ended { return true }
        guard let left = controller.remaining else { return false }
        return left <= 20
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            // On an error the system player is taken away, so the remote reaches the panel's buttons and Menu.
            if failure == nil {
            SystemPlayer(player: controller.player, title: current.episode > 0 ? "الموسم \(current.season) · الحلقة \(current.episode)" : current.title,
                         subtitle: current.episode > 0 ? current.title : "",
                         menuKey: menuKey, menus: { menus }, actionKey: offerNext ? (next?.id ?? "") : "", actions: { actions },
                         episodesKey: queue.map(\.id).joined(separator: ",") + "|" + current.id, episodes: { episodesPanel })
                .ignoresSafeArea()
            }
            VStack {
                Spacer()
                if let cue = controller.cue {
                    SubtitleText(text: cue, size: subtitleSize, background: subtitleBackground)
                        .padding(.horizontal, 120).padding(.bottom, 90)
                }
            }
            .allowsHitTesting(false)
            if loading && resolutionError == nil && controller.error == nil { ProgressView().allowsHitTesting(false) }
            if let message = failure {
                VStack(spacing: 28) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 60)).foregroundStyle(TVTheme.accent)
                    Text(current.episode > 0 ? "\(current.title) · الموسم \(current.season) · الحلقة \(current.episode)" : current.title)
                        .font(.headline)
                    Text(message).multilineTextAlignment(.center).foregroundStyle(TVTheme.muted).frame(maxWidth: 1000)
                    HStack(spacing: 30) {
                        if streams.count > 1 && resolutionError == nil {
                            Menu("جودة أخرى") { ForEach(streams) { stream in Button(stream.quality) { choose(stream) } } }
                        }
                        if let next {
                            Button("الحلقة \(next.episode)") { active = next }
                        }
                        Button("إغلاق") { dismiss() }
                    }
                }
                .padding(60)
                .background(TVTheme.panel.opacity(0.95), in: RoundedRectangle(cornerRadius: 36, style: .continuous))
                .focusSection()
                .onExitCommand { dismiss() }
            }
        }
        .task(id: current.id) { await start(current) }
        .onDisappear { controller.close() }
    }

    /// The error to show, worded for the Apple TV (the shared messages speak of the iPhone's player).
    private var failure: String? {
        (resolutionError ?? controller.error).map {
            $0.replacingOccurrences(of: "بمشغّل الآيفون", with: "على Apple TV").replacingOccurrences(of: "الآيفون", with: "Apple TV")
        }
    }

    // MARK: Menus in the transport bar

    /// Everything the menus show; they are rebuilt only when this changes, so an open menu is not replaced while
    /// the video plays (the controller publishes its position several times a second).
    private var menuKey: String {
        var parts: [String] = []
        parts.append(streams.map(\.id).joined(separator: ","))
        parts.append(quality ?? "")
        parts.append(controller.subtitleID ?? "")
        parts.append(externalTracks.map(\.id).joined(separator: ","))
        parts.append(controller.embeddedSubtitles.map(\.id).joined(separator: ","))
        parts.append(String(subtitleSize))
        parts.append(String(subtitleBackground))
        parts.append(controller.audioOptions.map(\.id).joined(separator: ","))
        parts.append(controller.audioID ?? "")
        parts.append(String(controller.rate))
        return parts.joined(separator: "|")
    }

    private var menus: [UIMenuElement] {
        var items: [UIMenuElement] = []
        if streams.count > 1 {
            items.append(UIMenu(title: "الجودة", image: UIImage(systemName: "slider.horizontal.3"), children: streams.map { stream in
                UIAction(title: stream.quality, state: stream.id == quality ? .on : .off) { _ in choose(stream) }
            }))
        }
        var subtitleItems: [UIMenuElement] = [
            UIAction(title: "بدون ترجمة", state: controller.subtitleID == nil ? .on : .off) { _ in
                controller.selectSubtitle(nil, external: externalTracks)
            }
        ]
        subtitleItems += externalTracks.map { track in
            UIAction(title: track.title, state: controller.subtitleID == "ext:" + track.id ? .on : .off) { _ in
                controller.selectSubtitle("ext:" + track.id, external: externalTracks)
            }
        }
        subtitleItems += controller.embeddedSubtitles.map { option in
            UIAction(title: option.title + " (مدمجة)", state: controller.subtitleID == option.id ? .on : .off) { _ in
                controller.selectSubtitle(option.id, external: externalTracks)
            }
        }
        subtitleItems.append(UIMenu(title: "الحجم", children: SubtitlePreference.sizes.map { option in
            UIAction(title: option.0, state: abs(option.1 - subtitleSize) < 0.01 ? .on : .off) { _ in subtitleSize = option.1 }
        }))
        subtitleItems.append(UIMenu(title: "الخلفية", children: SubtitlePreference.backgrounds.map { option in
            UIAction(title: option.0, state: abs(option.1 - subtitleBackground) < 0.01 ? .on : .off) { _ in subtitleBackground = option.1 }
        }))
        items.append(UIMenu(title: "الترجمة", image: UIImage(systemName: "captions.bubble"), children: subtitleItems))
        // Audio tracks are in the system's own audio menu.
        items.append(UIMenu(title: "السرعة", image: UIImage(systemName: "gauge.with.dots.needle.67percent"),
                            children: [Float(0.75), 1, 1.25, 1.5, 2].map { value in
            UIAction(title: value == 1 ? "عادية" : String(format: "%gx", value), state: controller.rate == value ? .on : .off) { _ in
                controller.setRate(value)
            }
        }))
        return items
    }

    /// The "الحلقات" tab of the info panel (swipe down while playing): the season's episodes, the one playing marked.
    private var episodesPanel: [UIViewController] {
        let list = queue
        guard list.count > 1 else { return [] }
        let row = PlayerEpisodesRow(episodes: list, currentID: current.id, multiSeason: Set(list.map(\.season)).count > 1,
                                    progress: { library.fraction($0) }, select: { episode in
            if episode.id != current.id { active = episode }
        })
        let host = UIHostingController(rootView: row)
        host.title = "الحلقات"
        host.preferredContentSize = CGSize(width: 1920, height: 380)
        host.view.backgroundColor = .clear
        return [host]
    }

    private var actions: [UIAction] {
        guard offerNext, let next else { return [] }
        return [UIAction(title: "الحلقة \(next.episode)", image: UIImage(systemName: "forward.end.fill")) { _ in active = next }]
    }

    // MARK: Playback

    private func choose(_ stream: StreamChoice) {
        quality = stream.id
        open(current, stream: stream, resume: controller.position)
    }

    private func open(_ media: Media, stream: StreamChoice, resume: Double) {
        controller.open(media, url: stream.url, resume: resume) { seconds, duration, completed in
            library.save(media, seconds: seconds, duration: duration, completed: completed, next: completed ? following(media) : nil)
        }
    }

    private func start(_ media: Media) async {
        controller.close(); controller.resetSubtitles(preferred: subtitleLanguage == "off" ? nil : subtitleLanguage)
        streams = []; externalTracks = []; quality = nil; loading = true; resolutionError = nil
        async let tracks = SourceClient().subtitles(media)
        // An episode opened from continue watching comes without its season: load it so the next episode is known.
        if selection.queue.isEmpty, media.episode > 0, !fetchedQueue.contains(where: { $0.id == media.id }) {
            Task {
                if let found = try? await SourceClient().detail(media) { fetchedQueue = found.1.flatMap(\.episodes) }
            }
        }
        do {
            let result = try await SourceClient().streams(media)
            try Task.checkCancellation()
            streams = result
            quality = result[0].id
            let prior = library.progress[media.id]
            open(media, stream: result[0], resume: prior?.completed == false ? prior?.seconds ?? 0 : 0)
        } catch {
            if !Task.isCancelled { resolutionError = error.localizedDescription }
        }
        if !Task.isCancelled { loading = false }
        let found = await tracks
        guard !Task.isCancelled else { return }
        externalTracks = found
        if controller.subtitleID == nil, let preferred = controller.preferredLanguage,
           let track = found.first(where: { $0.language == preferred }) {
            controller.selectSubtitle("ext:" + track.id, external: found)
        }
    }
}

/// AVPlayerViewController with the given transport bar menus and contextual actions. Menus, actions and the title
/// are handed to the controller only when they change.
struct SystemPlayer: UIViewControllerRepresentable {
    let player: AVPlayer
    let title: String
    let subtitle: String
    let menuKey: String
    let menus: () -> [UIMenuElement]
    let actionKey: String
    let actions: () -> [UIAction]
    let episodesKey: String
    let episodes: () -> [UIViewController]

    final class Coordinator {
        var menuKey: String?
        var actionKey: String?
        var titleKey: String?
        var episodesKey: String?
    }
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.view.backgroundColor = .black
        // One subtitle button: Cinema iQ's menu (source files, embedded tracks, size and background). Allowing no
        // language hides the system's own subtitle button.
        controller.allowedSubtitleOptionLanguages = ["zxx"]
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        let state = context.coordinator
        if controller.player !== player { controller.player = player }
        if state.menuKey != menuKey {
            state.menuKey = menuKey
            controller.transportBarCustomMenuItems = menus()
        }
        if state.actionKey != actionKey {
            state.actionKey = actionKey
            controller.contextualActions = actions()
        }
        if state.episodesKey != episodesKey {
            state.episodesKey = episodesKey
            controller.customInfoViewControllers = episodes()
        }
        guard let item = player.currentItem else { return }
        let titleKey = "\(ObjectIdentifier(item))|\(title)|\(subtitle)"
        guard state.titleKey != titleKey else { return }
        state.titleKey = titleKey
        let titleItem = AVMutableMetadataItem()
        titleItem.identifier = .commonIdentifierTitle
        titleItem.value = title as NSString
        titleItem.extendedLanguageTag = "und"
        var metadata: [AVMetadataItem] = [titleItem]
        if !subtitle.isEmpty {
            let subtitleItem = AVMutableMetadataItem()
            subtitleItem.identifier = .iTunesMetadataTrackSubTitle
            subtitleItem.value = subtitle as NSString
            subtitleItem.extendedLanguageTag = "und"
            metadata.append(subtitleItem)
        }
        item.externalMetadata = metadata
    }
}

/// Episodes in the player's info panel: a row of stills; the one playing says so; pressing another plays it.
struct PlayerEpisodesRow: View {
    let episodes: [Media]
    let currentID: String
    let multiSeason: Bool
    let progress: (Media) -> Double
    let select: (Media) -> Void
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(episodes) { episode in
                        Button { select(episode) } label: {
                            Artwork(url: episode.backdrop.isEmpty ? episode.poster : episode.backdrop)
                                .frame(width: 380, height: 214)
                                .overlay(alignment: .bottom) {
                                    let fraction = progress(episode)
                                    if fraction > 0 {
                                        ProgressStrip(fraction: fraction).clipShape(Capsule()).padding(.horizontal, 16).padding(.bottom, 14)
                                    }
                                }
                                .overlay(alignment: .topLeading) {
                                    if episode.id == currentID {
                                        Text("يُعرض الآن").font(.caption2.weight(.bold)).foregroundStyle(.black)
                                            .padding(.horizontal, 12).padding(.vertical, 5)
                                            .background(Color.white, in: Capsule()).padding(12)
                                    }
                                }
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                .hoverEffect(.highlight)
                            Text(multiSeason ? "م\(episode.season) · الحلقة \(episode.episode)" : "الحلقة \(episode.episode)")
                                .font(.caption).lineLimit(1).frame(width: 380, alignment: .leading)
                        }
                        .buttonStyle(.borderless)
                        .id(episode.id)
                    }
                }
                .padding(.horizontal, 90).padding(.vertical, 24)
            }
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
            .onAppear { proxy.scrollTo(currentID, anchor: .center) }
        }
        .environment(\.layoutDirection, .rightToLeft)
    }
}
