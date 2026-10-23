import SwiftUI
import AVKit
import AVFoundation
import CinemaCore

struct PlaybackSelection: Identifiable {
    let media: Media
    let queue: [Media]
    var id: String { media.id }
}

/// Opens the full-screen player from any tab.
@MainActor final class PlayerPresenter: ObservableObject {
    @Published var selection: PlaybackSelection?
    func play(_ media: Media, queue: [Media] = []) { selection = PlaybackSelection(media: media, queue: queue) }
}

struct MediaOption: Identifiable, Hashable {
    let id: String
    let title: String
}

/// Owns the AVPlayer. Every control of the player (quality, subtitles, audio, speed) goes through here so the
/// screen has one set of controls instead of the system ones plus an overlay.
@MainActor final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    @Published var error: String?
    @Published var notice: String?
    @Published var ended = false
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var playing = false
    @Published var buffering = false
    @Published var rate: Float = 1
    /// The subtitle line on screen now.
    @Published var cue: String?
    /// nil = off, "ext:<url>" = the source's file, "emb:<n>" = a track inside the video.
    @Published var subtitleID: String?
    @Published var embeddedSubtitles: [MediaOption] = []
    @Published var audioOptions: [MediaOption] = []
    @Published var audioID: String?
    var remaining: Double? { duration > 0 ? max(0, duration - position) : nil }

    private var legibleGroup: AVMediaSelectionGroup?
    private var audibleGroup: AVMediaSelectionGroup?
    private var cues: [SubtitleCue] = []
    /// The language chosen automatically for a new title (from Settings); cleared once the viewer turns
    /// subtitles off, so they are not switched back on by themselves.
    private(set) var preferredLanguage: String? = "ar"
    private var timer: Any?
    private var endToken: NSObjectProtocol?
    private var failureToken: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var controlObservation: NSKeyValueObservation?
    private var save: ((Double, Double, Bool) -> Void)?
    private var ticks = 0
    private static let arabicWindows = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
        CFStringEncoding(CFStringEncodings.windowsArabic.rawValue)))

    init() { player.appliesMediaSelectionCriteriaAutomatically = false }

    func open(_ media: Media, url: URL, resume: Double, save: @escaping (Double, Double, Bool) -> Void) {
        close(); self.save = save; error = nil; ended = false; position = max(0, resume); duration = 0; ticks = 0
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch { self.error = error.localizedDescription; return }
        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            if item.status == .failed {
                Task { @MainActor in self?.error = "تعذّر تشغيل هذه النسخة. قد تكون صيغة الفيديو أو الصوت غير مدعومة على iOS. جرّب جودة أخرى." }
            }
        }
        controlObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in
                self?.playing = status != .paused
                self?.buffering = status == .waitingToPlayAtSpecifiedRate
            }
        }
        endToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                self.ended = true; self.persist(completed: true); self.player.pause()
            }
        }
        failureToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.error = "انقطع تشغيل الفيديو. تحقق من الاتصال وأعد فتح العمل." }
        }
        timer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        Task { await loadSelectionGroups(item) }
        if resume.isFinite && resume > 0 { player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
        player.defaultRate = rate
        player.play()
    }

    private func tick() {
        guard let item = player.currentItem else { return }
        let now = player.currentTime().seconds, length = item.duration.seconds
        if now.isFinite { position = now }
        if length.isFinite && length > 0 { duration = length }
        let line = cues.isEmpty ? nil : SubtitleCatalog.cue(at: position, in: cues)?.text
        if line != cue { cue = line }
        ticks += 1
        if ticks % 20 == 0 { persist() }
    }

    func persist(completed: Bool = false) {
        guard let item = player.currentItem else { return }
        let now = player.currentTime().seconds, length = item.duration.seconds
        guard now.isFinite, length.isFinite, length > 0 else { return }
        save?(now, length, completed || ended)
    }

    func togglePlay() {
        if player.timeControlStatus == .paused {
            if ended { ended = false; seek(to: 0) }
            player.play()
        } else {
            player.pause()
        }
    }
    func seek(to seconds: Double) {
        let target = max(0, duration > 0 ? min(seconds, duration) : seconds)
        position = target
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    func skip(_ delta: Double) { seek(to: position + delta) }
    func setRate(_ value: Float) {
        rate = value
        player.defaultRate = value
        if player.timeControlStatus != .paused { player.rate = value }
    }

    /// A new title starts with no subtitle chosen; the preferred language is picked automatically when offered.
    func resetSubtitles(preferred: String?) {
        subtitleID = nil; cues = []; cue = nil; preferredLanguage = preferred
    }

    func selectSubtitle(_ id: String?, external: [SubtitleTrack]) {
        subtitleID = id; cues = []; cue = nil; notice = nil
        if id == nil { preferredLanguage = nil }
        applyEmbeddedSelection()
        guard let id, id.hasPrefix("ext:"), let track = external.first(where: { "ext:" + $0.id == id }) else { return }
        Task {
            do {
                let data = try await SubtitleCatalog.download(track)
                let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: Self.arabicWindows) ?? ""
                let parsed = SubtitleCatalog.cues(text)
                guard subtitleID == id else { return }
                if parsed.isEmpty { notice = "ملف الترجمة غير صالح للعرض." }
                cues = parsed
            } catch {
                if subtitleID == id { notice = "تعذّر تحميل الترجمة." }
            }
        }
    }

    func selectAudio(_ id: String) {
        audioID = id
        guard let group = audibleGroup, let item = player.currentItem,
              let index = Int(id.dropFirst(4)), group.options.indices.contains(index) else { return }
        item.select(group.options[index], in: group)
    }

    private func applyEmbeddedSelection() {
        guard let group = legibleGroup, let item = player.currentItem else { return }
        if let id = subtitleID, id.hasPrefix("emb:"), let index = Int(id.dropFirst(4)), group.options.indices.contains(index) {
            item.select(group.options[index], in: group)
        } else {
            item.select(nil, in: group)
        }
    }

    private func loadSelectionGroups(_ item: AVPlayerItem) async {
        let legible = (try? await item.asset.loadMediaSelectionGroup(for: .legible)) ?? nil
        let audible = (try? await item.asset.loadMediaSelectionGroup(for: .audible)) ?? nil
        guard player.currentItem === item else { return }
        legibleGroup = legible; audibleGroup = audible
        embeddedSubtitles = (legible?.options ?? []).enumerated().map { MediaOption(id: "emb:\($0.offset)", title: $0.element.displayName) }
        audioOptions = (audible?.options ?? []).enumerated().map { MediaOption(id: "aud:\($0.offset)", title: $0.element.displayName) }
        if let audible, let selected = item.currentMediaSelection.selectedMediaOption(in: audible),
           let index = audible.options.firstIndex(of: selected) { audioID = "aud:\(index)" }
        if subtitleID == nil, let preferred = preferredLanguage, let legible,
           let index = legible.options.firstIndex(where: { $0.locale?.language.languageCode?.identifier == preferred }) {
            subtitleID = "emb:\(index)"
        }
        applyEmbeddedSelection()
    }

    func close() {
        persist(); player.pause()
        if let timer { player.removeTimeObserver(timer) }; timer = nil
        if let endToken { NotificationCenter.default.removeObserver(endToken) }; endToken = nil
        if let failureToken { NotificationCenter.default.removeObserver(failureToken) }; failureToken = nil
        statusObservation = nil; controlObservation = nil
        legibleGroup = nil; audibleGroup = nil; embeddedSubtitles = []; audioOptions = []; audioID = nil
        player.replaceCurrentItem(with: nil); save = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

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
    @State private var active: Media?
    /// The season list when playback started without one (from continue watching).
    @State private var fetchedQueue: [Media] = []
    @StateObject private var controller = PlaybackController()
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @State private var previousIdleSetting = false
    @State private var streams: [StreamChoice] = []
    @State private var quality: String?
    @State private var externalTracks: [SubtitleTrack] = []
    @State private var loading = false
    @State private var resolutionError: String?
    @State private var controlsVisible = true
    @State private var scrubbing: Double?
    /// The options panel: nil closed, otherwise the page shown in it.
    @State private var optionsPage: OptionsPage?
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
    /// Without credits times from the source, the next episode is offered for the last 15 seconds (as on Android).
    private var offerNext: Bool {
        guard next != nil, !controller.ended, let left = controller.remaining else { return false }
        return left <= 15
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(player: controller.player)
                .ignoresSafeArea()
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { controlsVisible.toggle() } }
            subtitleLayer
            if controlsVisible { controls.transition(.opacity) }
            if (loading || controller.buffering) && resolutionError == nil && controller.error == nil {
                ProgressView().controlSize(.large).tint(.white).allowsHitTesting(false)
            }
            if let error = resolutionError ?? controller.error {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(Theme.accent)
                    Text(error).multilineTextAlignment(.center)
                    HStack {
                        if streams.count > 1 && resolutionError == nil {
                            Menu("جودة أخرى") { qualityItems }.menuStyle(.button).secondaryAction()
                        }
                        Button("إغلاق") { dismiss() }.primaryAction()
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
                    Button("إغلاق") { dismiss() }.secondaryAction()
                }
                .padding(24)
                .glass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            }
        }
        .statusBarHidden(!controlsVisible)
        .persistentSystemOverlays(.hidden)
        // Controls fade after four seconds of playback; any change of state starts the wait again.
        .task(id: "\(controlsVisible)-\(controller.playing)-\(scrubbing == nil)-\(optionsPage == nil)") {
            // The controls must not fade while the options are open: that removed the panel under the finger.
            guard controlsVisible, controller.playing, scrubbing == nil, optionsPage == nil else { return }
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { withAnimation(.easeInOut(duration: 0.3)) { controlsVisible = false } }
        }
        .task(id: current.id) { await start(current) }
        .onAppear { previousIdleSetting = UIApplication.shared.isIdleTimerDisabled; UIApplication.shared.isIdleTimerDisabled = true }
        .onChange(of: phase) { _, value in
            if value != .active { controller.persist(); controller.player.pause(); UIApplication.shared.isIdleTimerDisabled = previousIdleSetting }
            else { UIApplication.shared.isIdleTimerDisabled = true }
        }
        .onDisappear { controller.close(); UIApplication.shared.isIdleTimerDisabled = previousIdleSetting }
    }

    // MARK: Layers

    private var subtitleLayer: some View {
        VStack {
            Spacer()
            if let cue = controller.cue {
                SubtitleText(text: cue, size: subtitleSize, background: subtitleBackground)
                    .padding(.horizontal, 24)
                    .padding(.bottom, controlsVisible ? 120 : 34)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        .allowsHitTesting(false)
    }

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.55), .clear, .clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        if optionsPage != nil { optionsPage = nil } else { controlsVisible = false }
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
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.headline).frame(width: 24, height: 24)
            }
            .secondaryAction()
            .buttonBorderShape(.circle)
            .accessibilityLabel("إغلاق المشغل")
            VStack(alignment: .leading, spacing: 0) {
                Text(current.title).font(.subheadline.bold())
                if current.episode > 0 {
                    Text("الموسم \(current.season) • الحلقة \(current.episode)").font(.caption).foregroundStyle(.white.opacity(0.75))
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
    }

    enum OptionsPage { case root, quality, subtitles, audio, speed }

    private var subtitleTitle: String {
        guard let id = controller.subtitleID else { return "بدون" }
        if id.hasPrefix("ext:") { return externalTracks.first { "ext:" + $0.id == id }?.title ?? "مفعّلة" }
        return controller.embeddedSubtitles.first { $0.id == id }?.title ?? "مفعّلة"
    }

    @ViewBuilder private func optionsPanel(_ page: OptionsPage) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            switch page {
            case .root:
                if streams.count > 1 {
                    optionRow("الجودة", icon: "slider.horizontal.3", value: streams.first { $0.id == qualityBinding.wrappedValue }?.quality) { optionsPage = .quality }
                }
                optionRow("الترجمة", icon: "captions.bubble", value: subtitleTitle) { optionsPage = .subtitles }
                if controller.audioOptions.count > 1 {
                    optionRow("الصوت", icon: "waveform", value: controller.audioOptions.first { $0.id == audioBinding.wrappedValue }?.title) { optionsPage = .audio }
                }
                optionRow("السرعة", icon: "gauge.with.dots.needle.67percent", value: rateLabel(controller.rate)) { optionsPage = .speed }
            case .quality:
                panelHeader("الجودة")
                ForEach(streams) { stream in
                    choiceRow(stream.quality, selected: stream.id == qualityBinding.wrappedValue) { qualityBinding.wrappedValue = stream.id; optionsPage = nil }
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
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        choiceRow("بدون ترجمة", selected: controller.subtitleID == nil) { subtitleBinding.wrappedValue = nil }
                        ForEach(externalTracks) { track in
                            choiceRow(track.title, selected: controller.subtitleID == "ext:" + track.id) { subtitleBinding.wrappedValue = "ext:" + track.id }
                        }
                        ForEach(controller.embeddedSubtitles) { option in
                            choiceRow("\(option.title) (مدمجة)", selected: controller.subtitleID == option.id) { subtitleBinding.wrappedValue = option.id }
                        }
                        // Size and background of the subtitles, saved like in Settings and applied at once.
                        Text("الحجم").font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.6))
                            .padding(.horizontal, 14).padding(.top, 10)
                        chips(SubtitlePreference.sizes, selected: subtitleSize) { subtitleSize = $0 }
                        Text("الخلفية").font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.6))
                            .padding(.horizontal, 14).padding(.top, 6)
                        chips(SubtitlePreference.backgrounds, selected: subtitleBackground) { subtitleBackground = $0 }
                    }
                }
                .frame(maxHeight: 230)
            }
        }
        .padding(8)
        .frame(width: 300)
        .glass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .animation(.easeInOut(duration: 0.2), value: page)
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
        Picker("الجودة", selection: qualityBinding) {
            ForEach(streams) { stream in Text(stream.quality).tag(stream.id) }
        }
    }

    private var transport: some View {
        HStack(spacing: 36) {
            roundButton("gobackward.10", size: 24, diameter: 60, label: "رجوع 10 ثوانٍ") { controller.skip(-10) }
            roundButton(controller.playing ? "pause.fill" : "play.fill", size: 32, diameter: 78,
                        label: controller.playing ? "إيقاف مؤقت" : "تشغيل") { controller.togglePlay() }
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

    private var qualityBinding: Binding<String> {
        Binding(get: { quality ?? streams.first?.id ?? "" }, set: { id in
            guard id != quality, let stream = streams.first(where: { $0.id == id }) else { return }
            quality = id
            controller.error = nil
            open(current, stream: stream, resume: controller.position)
        })
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

    private func open(_ media: Media, stream: StreamChoice, resume: Double) {
        controller.open(media, url: stream.url, resume: resume) { seconds, duration, completed in
            library.save(media, seconds: seconds, duration: duration, completed: completed, next: completed ? following(media) : nil)
        }
        if phase != .active { controller.player.pause() }
    }

    private func start(_ media: Media) async {
        controller.close(); controller.resetSubtitles(preferred: subtitleLanguage == "off" ? nil : subtitleLanguage)
        streams = []; externalTracks = []; quality = nil; loading = true; resolutionError = nil; controlsVisible = true
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
        // The preferred language from Settings, from the source's files, unless the viewer already chose.
        if controller.subtitleID == nil, let preferred = controller.preferredLanguage,
           let track = found.first(where: { $0.language == preferred }) {
            controller.selectSubtitle("ext:" + track.id, external: found)
        }
    }
}
