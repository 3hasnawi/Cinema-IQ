import SwiftUI
import AVKit
import AVFoundation
import CinemaCore

/// The picture only; the controls are drawn by SwiftUI.
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    final class PlayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }
    func updateUIView(_ view: PlayerView, context: Context) { view.playerLayer.player = player }
}

/// AirPlay button.
struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = .white
        view.activeTintColor = UIColor(Theme.accent)
        view.prioritizesVideoDevices = true
        return view
    }
    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}

struct PlaybackView: View {
    let selection: PlaybackSelection
    @Environment(\.contentLayout) private var layout
    @State private var active: Media?
    /// The season list when playback started without one (from continue watching).
    @State private var fetchedQueue: [Media] = []
    /// IntroDB timings of what plays: the opening (skip button) and the start of the end credits.
    @State private var skips = SkipSegments()
    @State private var fetchedSeries: Media?
    @StateObject private var controller = PlaybackController(enginePreference: .phoneMovies)
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var previousIdleSetting = false
    @State private var streams: [StreamChoice] = []
    @State private var quality: String?
    @State private var externalTracks: [SubtitleTrack] = []
    @State private var loading = false
    @State private var resolutionError: String?
    @State private var controlsVisible = true
    @State private var exiting = false
    @State private var episodesVisible = false
    @State private var overlayInfo: OMDbInfo?
    @State private var pauseFactsHeight: CGFloat = 0
    @State private var scrubbing: Double?
    /// The options panel: nil closed, otherwise the page shown in it.
    @State private var optionsPage: OptionsPage?
    @State private var optionsHeight: CGFloat = 0
    @State private var qualityMode = VideoQuality.automatic
    @State private var automaticQuality = true
    @AppStorage(PhonePlaybackPreference.qualityKey) private var preferredQuality = VideoQuality.automatic.rawValue
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
    /// Use IntroDB's credits, or the same final 20 seconds offered by the TV player.
    private var offerNext: Bool {
        guard next != nil, !controller.ended, let left = controller.remaining else { return false }
        if let credits = skips.creditsStart, controller.duration > 0, credits < controller.duration, controller.position >= credits { return true }
        return left <= 20
    }
    private var inIntro: Bool { skips.inIntro(controller.position) }
    private var pausedInformation: Bool {
        !controller.playing && !controller.buffering && !loading && !controller.ended &&
            controller.position > 0 && resolutionError == nil && controller.error == nil
    }
    private var compactPlayer: Bool { layout.size.width > layout.size.height && layout.size.height < 500 }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if !exiting {
            PlaybackVideoSurface(controller: controller)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityElement(children: .ignore)
                .accessibilityIdentifier("ios.player.video")
                .accessibilityLabel("الفيديو")
                .accessibilityValue(clock(controller.position))
            // Decoder views are deliberately noninteractive; a stable SwiftUI layer owns picture taps.
            Button {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                    if optionsPage != nil { optionsPage = nil; controlsVisible = true }
                    else { controlsVisible.toggle() }
                }
            } label: {
                Color.clear.contentShape(Rectangle())
            }
            .buttonStyle(.plain).ignoresSafeArea()
            .accessibilityLabel(controlsVisible ? "إخفاء أدوات المشغل" : "إظهار أدوات المشغل")
            .accessibilityIdentifier("ios.player.controls.toggle")
            subtitleLayer
            if pausedInformation && !controlsVisible {
                VStack(alignment: .leading) {
                    Text(selection.series?.title ?? fetchedSeries?.title ?? current.title)
                        .font(.title3.bold()).lineLimit(2)
                    Spacer()
                    MoviePauseFacts(media: current, info: overlayInfo, showOverview: !compactPlayer && controller.cue == nil)
                        .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { pauseFactsHeight = $0 }
                }
                .padding(.horizontal, 24).padding(.top, 36).padding(.bottom, 34)
                .frame(maxWidth: .infinity, alignment: .leading)
                .allowsHitTesting(false)
            }
            if controlsVisible { controls.transition(.opacity) }
            // Skip the opening: shown during it whether or not the controls are, above the timeline.
            if inIntro, let end = skips.introEnd, resolutionError == nil, controller.error == nil {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button { controller.seek(to: end) } label: {
                            Label("تخطي المقدمة", systemImage: "forward.fill").font(.headline)
                        }
                        .primaryAction()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, controlsVisible ? 96 : 30)
                .transition(.opacity)
            }
            if (loading || controller.buffering) && resolutionError == nil && controller.error == nil {
                ProgressView().controlSize(.large).tint(.white).allowsHitTesting(false)
            }
            if let error = resolutionError ?? controller.error {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(Theme.accent)
                    Text(error).multilineTextAlignment(.center)
                    HStack {
                        if resolutionError == nil {
                            Menu("محرك التشغيل") { ForEach(IPTVPlaybackMode.allCases, id: \.rawValue) { mode in Button(mode.title) { controller.chooseEngine(mode) } } }.menuStyle(.button).secondaryAction()
                        }
                        if !streams.isEmpty && resolutionError == nil {
                            Menu("جودة أخرى") { qualityItems }.menuStyle(.button).secondaryAction()
                        }
                        Button("إغلاق", action: exitPlayer).primaryAction()
                    }
                }
                .padding(24)
                .glass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .padding(32)
            } else if controller.ended {
                VStack(spacing: 16) {
                    Text("انتهت المشاهدة").font(.title3.bold())
                    if let next {
                        Button { active = next } label: { Label("الحلقة \(next.episode)", systemImage: "play.fill").font(.headline) }
                            .primaryAction()
                    }
                    Button("إغلاق", action: exitPlayer).secondaryAction()
                }
                .padding(24)
                .glass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            }
            }
        }
#if DEBUG
        .overlay(alignment: .topLeading) {
            if UITestSupport.playerFixtureEnabled && !exiting {
                let actual = controller.player.currentTime().seconds
                Text(verbatim: "\(actual.isFinite ? Int(actual) : -1)|\(controller.playing)")
                    .font(.system(size: 1)).opacity(0.01).frame(width: 1, height: 1)
                    .accessibilityIdentifier("ios.player.testClock").allowsHitTesting(false)
            }
        }
#endif
        .simultaneousGesture(DragGesture(minimumDistance: 32).onEnded { value in
            if !exiting, optionsPage == nil, queue.count > 1,
               value.translation.height < -55, abs(value.translation.height) > abs(value.translation.width) * 1.2 {
                episodesVisible = true; controlsVisible = true
            }
        })
        .sheet(isPresented: $episodesVisible, onDismiss: { controlsVisible = true }) {
            ResponsiveContainer { MovieEpisodesSheet(episodes: queue, currentID: current.id,
                               fraction: { library.fraction($0) }, select: { episode in
                episodesVisible = false
                if !exiting, episode.id != current.id { active = episode }
            }) }
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .environment(\.layoutDirection, .rightToLeft)
        }
        .statusBarHidden(!controlsVisible)
        .persistentSystemOverlays(.hidden)
        // Controls fade after four seconds of playback; any change of state starts the wait again.
        .task(id: "\(controlsVisible)-\(controller.playing)-\(scrubbing == nil)-\(optionsPage == nil)-\(episodesVisible)") {
            #if DEBUG
            // The layout tour keeps its HUD visible; the controls regression uses the production timeout.
            guard !UITestSupport.playerFixtureEnabled ||
                ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-player-controls") else { return }
            #endif
            // The controls must not fade while the options are open: that removed the panel under the finger.
            guard !exiting, controlsVisible, controller.playing, scrubbing == nil, optionsPage == nil, !episodesVisible else { return }
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { withAnimation(.easeInOut(duration: 0.3)) { controlsVisible = false } }
        }
        .task(id: current.id) { await start(current) }
        .task(id: "queue-\(current.id)") { await loadQueue(current) }
        .task(id: "pause-info-\(current.id)-\(fetchedSeries?.id ?? "")") {
            overlayInfo = nil
            let identity = current.id
            let found = await OMDb.shared?.info(for: selection.series ?? fetchedSeries ?? current)
            guard !exiting, !Task.isCancelled, current.id == identity else { return }
            overlayInfo = found
        }
        .task(id: "skips-\(current.id)") {
            skips = SkipSegments()
            let identity = current.id
            let found = await SkipTimings.load(current, series: selection.series ?? fetchedSeries, omdb: OMDb.shared)
            guard !exiting, !Task.isCancelled, current.id == identity else { return }
            skips = found.segments
            if fetchedSeries == nil { fetchedSeries = found.series }
        }
        .onAppear { previousIdleSetting = UIApplication.shared.isIdleTimerDisabled; UIApplication.shared.isIdleTimerDisabled = true }
        .onChange(of: phase) { _, value in
            if value != .active { controller.persist(); controller.pause(); UIApplication.shared.isIdleTimerDisabled = previousIdleSetting }
            else { UIApplication.shared.isIdleTimerDisabled = true }
        }
        .onDisappear { exiting = true; controller.close(); UIApplication.shared.isIdleTimerDisabled = previousIdleSetting }
    }

    private func exitPlayer() {
        guard !exiting else { return }
        exiting = true; optionsPage = nil; episodesVisible = false
        controller.close()
        dismiss()
    }

    // MARK: Layers

    private var subtitleLayer: some View {
        VStack {
            Spacer()
            if let cue = controller.cue {
                SubtitleText(text: cue, size: subtitleSize, background: subtitleBackground)
                    .padding(.horizontal, 24)
                    .padding(.bottom, pausedInformation ? pauseFactsHeight + (controlsVisible ? 110 : 60) : (controlsVisible ? 120 : 34))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        .allowsHitTesting(false)
    }

    private var controls: some View {
        ZStack {
            if !pausedInformation {
            LinearGradient(colors: [.black.opacity(0.55), .clear, .clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        if optionsPage != nil { optionsPage = nil } else { controlsVisible = false }
                    }
                }
            }
            VStack(spacing: 14) {
                topBar
                Spacer()
                transport
                Spacer()
                if let notice = controller.notice {
                    Text(notice).font(.footnote).padding(.horizontal, 12).padding(.vertical, 6).glass(in: Capsule())
                }
                if pausedInformation {
                    MoviePauseFacts(media: current, info: overlayInfo, showOverview: !compactPlayer && controller.cue == nil)
                        .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { pauseFactsHeight = $0 }
                        .allowsHitTesting(false)
                }
                if offerNext, let next {
                    HStack {
                        Spacer()
                        Button { active = next } label: {
                            Label("الحلقة التالية", systemImage: "forward.end.fill").font(.headline)
                        }
                        .primaryAction()
                    }
                }
                scrubber
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            if let page = optionsPage {
                VStack {
                    HStack {
                        Spacer()
                        optionsPanel(page)
                    }
                    Spacer()
                }
                .padding(.top, 62)
                .padding(.horizontal, 16)
                .transition(.scale(scale: 0.85, anchor: .topTrailing).combined(with: .opacity))
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Button(action: exitPlayer) {
                Image(systemName: "xmark").font(.headline).frame(width: 24, height: 24)
            }
            .secondaryAction()
            .buttonBorderShape(.circle)
            .accessibilityLabel("إغلاق المشغل")
            .accessibilityIdentifier("ios.player.close")
            VStack(alignment: .leading, spacing: 0) {
                Text(current.title).font(.subheadline.bold())
                if current.episode > 0 {
                    Text("الموسم \(current.season) • الحلقة \(current.episode)").font(.caption).foregroundStyle(.white.opacity(0.75))
                        .accessibilityIdentifier("ios.player.episode.info")
                }
            }
            .lineLimit(1)
            Spacer(minLength: 8)
            RoutePicker()
                .frame(width: 44, height: 44)
                .glass(in: Circle())
                .accessibilityLabel("AirPlay")
            optionsMenu
        }
    }

    /// Opens the player's own options panel. A system menu was dismissed whenever the controls faded under it,
    /// so taps on its items were sometimes lost.
    private var optionsMenu: some View {
        Button {
            withAnimation(.spring(duration: 0.3)) { optionsPage = optionsPage == nil ? .root : nil }
        } label: {
            Image(systemName: "ellipsis").font(.headline).frame(width: 24, height: 24)
        }
        .secondaryAction()
        .buttonBorderShape(.circle)
        .accessibilityLabel("خيارات التشغيل")
        .accessibilityIdentifier("ios.player.options")
    }

    enum OptionsPage { case root, quality, subtitles, audio, speed, engine }

    private var subtitleTitle: String {
        guard let id = controller.subtitleID else { return "بدون" }
        if id.hasPrefix("ext:") { return externalTracks.first { "ext:" + $0.id == id }?.title ?? "مفعّلة" }
        return controller.embeddedSubtitles.first { $0.id == id }?.title ?? "مفعّلة"
    }

    @ViewBuilder private func optionsPanel(_ page: OptionsPage) -> some View {
        ScrollView {
            optionContents(page)
                .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { optionsHeight = $0 }
            }
        .scrollBounceBehavior(.basedOnSize)
        .frame(height: min(optionsHeight > 0 ? optionsHeight : 180, layout.panelHeight))
        .padding(8)
        .frame(width: layout.panelWidth)
        .glass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .animation(.easeInOut(duration: 0.2), value: page)
        .accessibilityIdentifier("ios.player.options.panel")
    }

    @ViewBuilder private func optionContents(_ page: OptionsPage) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            switch page {
            case .root:
                if !streams.isEmpty {
                    optionRow("الجودة", icon: "slider.horizontal.3", value: automaticQuality ? VideoQuality.automatic.title : (qualityMode.height == nil ? streams.first { $0.id == quality }?.quality : qualityMode.title)) { optionsPage = .quality }
                }
                optionRow("الترجمة", icon: "captions.bubble", value: subtitleTitle) { optionsPage = .subtitles }
                if controller.audioOptions.count > 1 {
                    optionRow("الصوت", icon: "waveform", value: controller.audioOptions.first { $0.id == audioBinding.wrappedValue }?.title) { optionsPage = .audio }
                }
                optionRow("السرعة", icon: "gauge.with.dots.needle.67percent", value: rateLabel(controller.rate)) { optionsPage = .speed }
                optionRow("محرك التشغيل", icon: "play.rectangle", value: controller.mode.title) { optionsPage = .engine }
                if queue.count > 1 {
                    optionRow("الحلقات", icon: "rectangle.stack", value: nil) { optionsPage = nil; episodesVisible = true }
                        .accessibilityIdentifier("ios.player.episodes")
                }
            case .engine:
                panelHeader("محرك التشغيل")
                ForEach(IPTVPlaybackMode.allCases, id: \.rawValue) { value in
                    choiceRow(value.title, selected: value == controller.mode) { controller.chooseEngine(value); optionsPage = nil }
                }
                Text("VideoLAN VLCKit · LGPL 2.1 أو أحدث").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 14)
                Link("مصدر المكتبة والترخيص", destination: URL(string: "https://code.videolan.org/videolan/VLCKit")!).font(.caption).padding(14)
            case .quality:
                panelHeader("الجودة")
                choiceRow("تلقائي", selected: automaticQuality) { chooseQuality(.automatic); optionsPage = nil }
                    .accessibilityIdentifier("ios.player.quality.auto")
                if streams.contains(where: VideoQuality.looksLikeHLS) {
                    ForEach(VideoQuality.allCases.filter { $0 != .automatic }, id: \.rawValue) { mode in
                        choiceRow(mode.title, selected: !automaticQuality && qualityMode == mode) { chooseQuality(mode); optionsPage = nil }
                    }
                } else {
                    ForEach(streams) { stream in
                        choiceRow(stream.quality, selected: !automaticQuality && stream.id == quality) { choose(stream); optionsPage = nil }
                    }
                }
            case .audio:
                panelHeader("الصوت")
                ForEach(controller.audioOptions) { option in
                    choiceRow(option.title, selected: option.id == audioBinding.wrappedValue) { audioBinding.wrappedValue = option.id; optionsPage = nil }
                }
            case .speed:
                panelHeader("السرعة")
                ForEach([Float(0.75), 1, 1.25, 1.5, 2], id: \.self) { value in
                    choiceRow(rateLabel(value), selected: controller.rate == value) { rateBinding.wrappedValue = value; optionsPage = nil }
                }
            case .subtitles:
                panelHeader("الترجمة")
                subtitleChoices
            }
        }
    }

    private var subtitleChoices: some View {
        VStack(alignment: .leading, spacing: 2) {
            choiceRow("بدون ترجمة", selected: controller.subtitleID == nil) { subtitleBinding.wrappedValue = nil }
            ForEach(externalTracks) { track in
                choiceRow(track.title, selected: controller.subtitleID == "ext:" + track.id) { subtitleBinding.wrappedValue = "ext:" + track.id }
            }
            ForEach(controller.embeddedSubtitles) { option in
                choiceRow("\(option.title) (مدمجة)", selected: controller.subtitleID == option.id) { subtitleBinding.wrappedValue = option.id }
            }
            Text("الحجم").font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.6))
                .padding(.horizontal, 14).padding(.top, 10)
            chips(SubtitlePreference.sizes, selected: subtitleSize) { subtitleSize = $0 }
            Text("الخلفية").font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.6))
                .padding(.horizontal, 14).padding(.top, 6)
            chips(SubtitlePreference.backgrounds, selected: subtitleBackground) { subtitleBackground = $0 }
        }
    }

    private func optionRow(_ title: String, icon: String, value: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).foregroundStyle(Theme.accent).frame(width: 24)
                Text(title).font(.body.weight(.medium))
                Spacer(minLength: 8)
                if let value { Text(value).font(.footnote).foregroundStyle(.white.opacity(0.65)).lineLimit(1) }
                Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(.white.opacity(0.7))
            }
            .padding(.horizontal, 14).frame(height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func panelHeader(_ title: String) -> some View {
        Button { optionsPage = .root } label: {
            HStack(spacing: 8) {
                Image(systemName: "chevron.backward").font(.footnote.weight(.semibold))
                Text(title).font(.subheadline.bold())
                Spacer()
            }
            .padding(.horizontal, 14).frame(height: 40)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func choiceRow(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).font(.body).lineLimit(1)
                Spacer(minLength: 8)
                if selected { Image(systemName: "checkmark").font(.footnote.weight(.bold)).foregroundStyle(Theme.accent) }
            }
            .padding(.horizontal, 14).frame(height: 42)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func chips(_ options: [(String, Double)], selected: Double, pick: @escaping (Double) -> Void) -> some View {
        HStack(spacing: 6) {
            ForEach(options, id: \.1) { option in
                let on = abs(option.1 - selected) < 0.01
                Button { pick(option.1) } label: {
                    Text(option.0).font(.caption.weight(on ? .bold : .regular))
                        .foregroundStyle(on ? Color.black : .white)
                        .padding(.horizontal, 10).frame(height: 30)
                        .background(on ? Color.white : Color.white.opacity(0.12), in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10).padding(.bottom, 6)
    }

    @ViewBuilder private var qualityItems: some View {
        Button("تلقائي") { chooseQuality(.automatic) }
        ForEach(streams) { stream in Button(stream.quality) { choose(stream) } }
    }

    private var transport: some View {
        HStack(spacing: 36) {
            roundButton("gobackward.10", size: 24, diameter: 60, label: "رجوع 10 ثوانٍ") { controller.skip(-10) }
            roundButton(controller.playing ? "pause.fill" : "play.fill", size: 32, diameter: 78,
                        label: controller.playing ? "إيقاف مؤقت" : "تشغيل") { controller.togglePlay() }
                .accessibilityIdentifier("ios.player.playPause")
            roundButton("goforward.10", size: 24, diameter: 60, label: "تقديم 10 ثوانٍ") { controller.skip(10) }
        }
        .environment(\.layoutDirection, .leftToRight)
    }

    private func roundButton(_ symbol: String, size: CGFloat, diameter: CGFloat, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: diameter, height: diameter)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glass(in: Circle(), interactive: true)
        .accessibilityLabel(label)
    }

    private var scrubber: some View {
        let shown = scrubbing ?? controller.position
        return HStack(spacing: 12) {
            Text(clock(shown)).monospacedDigit()
            Slider(value: Binding(get: { scrubbing ?? controller.position }, set: { scrubbing = $0 }),
                   in: 0...max(controller.duration, 1)) { editing in
                if !editing, let target = scrubbing { controller.seek(to: target); scrubbing = nil }
            }
            .tint(Theme.accent)
            Text("-" + clock(max(0, controller.duration - shown))).monospacedDigit()
        }
        .font(.caption.weight(.medium))
        .padding(.horizontal, 16).padding(.vertical, 8)
        .glass(in: Capsule())
        .environment(\.layoutDirection, .leftToRight)
    }

    // MARK: Bindings and helpers

    private func chooseQuality(_ mode: VideoQuality) {
        guard let stream = mode.select(from: streams) else { return }
        qualityMode = mode; automaticQuality = mode == .automatic
        select(stream)
    }
    private func choose(_ stream: StreamChoice) {
        automaticQuality = false
        qualityMode = VideoQuality.allCases.first { $0.height == VideoQuality.resolution(stream.quality) } ?? .automatic
        select(stream)
    }
    private func select(_ stream: StreamChoice) {
        guard !exiting else { return }
        let sameStream = quality == stream.id && controller.error == nil && controller.hasItem
        quality = stream.id
        if sameStream { controller.setQuality(maximumHeight: qualityMode.height) }
        else { open(current, stream: stream, resume: controller.position, playing: controller.playbackRequested) }
    }
    private var subtitleBinding: Binding<String?> {
        Binding(get: { controller.subtitleID }, set: { controller.selectSubtitle($0, external: externalTracks) })
    }
    private var audioBinding: Binding<String> {
        Binding(get: { controller.audioID ?? "" }, set: { controller.selectAudio($0) })
    }
    private var rateBinding: Binding<Float> {
        Binding(get: { controller.rate }, set: { controller.setRate($0) })
    }
    private func rateLabel(_ value: Float) -> String {
        value == 1 ? "عادية" : String(format: "%gx", value)
    }
    private func clock(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "0:00" }
        let total = Int(seconds.rounded(.down))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    private func open(_ media: Media, stream: StreamChoice, resume: Double, playing shouldPlay: Bool = true) {
        guard !exiting else { return }
        #if DEBUG
        guard let playbackURL = UITestSupport.playbackURL(or: stream.url) else {
            controller.error = "UI test player fixture is missing."
            return
        }
        #else
        let playbackURL = stream.url
        #endif
        controller.open(media, url: playbackURL, resume: resume, initiallyPlaying: shouldPlay && phase == .active) { seconds, duration, completed in
            library.save(media, seconds: seconds, duration: duration, completed: completed, next: completed ? following(media) : nil)
        }
        controller.setQuality(maximumHeight: qualityMode.height)
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-pending-pause") {
            controller.pause(); controller.seek(to: 30)
        }
#endif
    }

    private func loadQueue(_ media: Media) async {
        guard !exiting, selection.queue.isEmpty, media.episode > 0,
              !fetchedQueue.contains(where: { $0.id == media.id }) else { return }
        guard let found = try? await SourceClient().detail(media),
              !exiting, !Task.isCancelled, current.id == media.id else { return }
        fetchedQueue = found.1.flatMap(\.episodes)
        if found.0.isSeries && fetchedSeries == nil { fetchedSeries = found.0 }
    }

    private func start(_ media: Media) async {
        guard !exiting else { return }
        controller.close(); controller.resetSubtitles(preferred: subtitleLanguage == "off" ? nil : subtitleLanguage)
        streams = []; externalTracks = []; quality = nil; loading = true; resolutionError = nil; controlsVisible = true
        async let tracks = SourceClient().subtitles(media)
        do {
            let result = try await SourceClient().streams(media)
            try Task.checkCancellation()
            guard !exiting, current.id == media.id else { return }
            streams = result
            qualityMode = VideoQuality(rawValue: preferredQuality) ?? .automatic
            automaticQuality = qualityMode == .automatic
            guard let first = qualityMode.select(from: result) else { throw CinemaError.noStreams }
            quality = first.id
            let prior = library.progress[media.id]
            open(media, stream: first, resume: prior?.completed == false ? prior?.seconds ?? 0 : 0)
        } catch {
            if !exiting, !Task.isCancelled, current.id == media.id { resolutionError = error.localizedDescription }
        }
        if !exiting, !Task.isCancelled, current.id == media.id { loading = false }
        let found = await tracks
        guard !exiting, !Task.isCancelled, current.id == media.id else { return }
        externalTracks = found
        // The preferred language from Settings, from the source's files, unless the viewer already chose.
        if controller.subtitleID == nil, let preferred = controller.preferredLanguage,
           let track = found.first(where: { $0.language == preferred }) {
            controller.selectSubtitle("ext:" + track.id, external: found)
        }
    }
}
