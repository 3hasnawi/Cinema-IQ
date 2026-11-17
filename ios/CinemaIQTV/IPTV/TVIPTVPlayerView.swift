import SwiftUI
import CinemaCore

/// Android TV 4.39 live layout, with a stable full-screen video plane and independent floating focus panels.
struct TVIPTVPlayerView: View {
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var session: IPTVPlayerSession
    @State private var guide = false
    @State private var controls = true
    @State private var pane: String?
    @State private var interaction = 0
    @State private var bounds: [String: CGRect] = [:]
    @State private var programmes = IPTVNowNext()
    @State private var restoreTask: Task<Void, Never>?
    @FocusState private var focus: String?
    let onReturn: (IPTVChannel, String?) -> Void

    init(initial: IPTVChannel, group: String? = nil, startInGuide: Bool = false, onReturn: @escaping (IPTVChannel, String?) -> Void = { _, _ in }) {
        self.onReturn = onReturn; _guide = State(initialValue: startInGuide)
        let preference = VideoQuality(rawValue: UserDefaults.standard.string(forKey: TVPlaybackPreference.qualityKey) ?? "auto") ?? .automatic
        _session = StateObject(wrappedValue: IPTVPlayerSession(initial: initial, group: group, preferredMaximumHeight: preference.height, automaticallyReconnect: true))
    }
    private func close() { onReturn(session.channel, session.group == session.channel.group ? session.group : nil); dismiss() }
    private func interact() { controls = true; interaction += 1 }
    private func showToolbar() {
        interact(); restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            guard !guide, pane == nil, controls, session.problem == nil else { return }
            focus = "control.channels"
        }
    }
    private func openTracks(_ value: String) {
        restoreTask?.cancel(); interact()
        withAnimation(reduceMotion ? nil : .spring(response: 0.25, dampingFraction: 0.85)) { pane = value }
    }
    private func closeTracks() {
        let target = pane.map { "control." + $0 } ?? "surface"
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.13)) { pane = nil }
        interact(); restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(reduceMotion ? 0 : 140)) } catch { return }
            guard !guide, pane == nil else { return }
            focus = session.problem == nil ? target : "error.retry"
        }
    }
    private func openGuide() {
        restoreTask?.cancel(); interact()
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { guide = true }
    }
    private func closeGuide() {
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.15)) { guide = false }
        interact(); restoreTask?.cancel()
        restoreTask = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(reduceMotion ? 0 : 160)) } catch { return }
            guard !guide, pane == nil else { return }
            focus = session.problem == nil ? "surface" : "error.retry"
        }
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                Color.black
                PlaybackVideoSurface(controller: session.controller).frame(width: geometry.size.width, height: geometry.size.height)
                    .environment(\.layoutDirection, .leftToRight).allowsHitTesting(false)
                Button(action: openGuide) { Color.clear.contentShape(Rectangle()) }
                    .buttonStyle(TVArtworkButtonStyle()).focusEffectDisabled().focused($focus, equals: "surface")
                    .disabled(guide || pane != nil || session.problem != nil)
                    .accessibilityLabel(session.channel.name).accessibilityValue(session.status)
                    .accessibilityIdentifier("tv.iptv.player.native")
                    .onMoveCommand { direction in
                        switch direction {
                        case .up: session.surf(-1); interact()
                        case .down: session.surf(1); interact()
                        case .left, .right: showToolbar()
                        default: break
                        }
                    }
                if controls || pane != nil {
                    TVIPTVPlayerHUD(session: session, programmes: programmes, focus: $focus,
                        channels: openGuide, tracks: openTracks, iconBounds: { bounds[$0] = $1 }, interact: interact)
                        .disabled(guide || pane != nil || session.problem != nil)
                        .opacity(guide ? 0 : 1)
                }
                if (session.buffering || session.tuning || session.retrying) && !guide {
                    ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity).allowsHitTesting(false)
                }
                if let problem = session.problem, !guide, pane == nil { errorPanel(problem).frame(maxWidth: .infinity, maxHeight: .infinity) }
                if guide {
                    TVIPTVLiveGuide(session: session, selected: closeGuide)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .transition(.opacity)
                }
                if let pane { trackPanel(pane, window: geometry.size) }
            }.coordinateSpace(name: "tv.iptv.player.space")
        }
        .ignoresSafeArea()
        .defaultFocus($focus, "surface")
        .onAppear { session.attach(store); session.setActive(phase == .active); if !guide { focus = "surface" } }
        .onDisappear { restoreTask?.cancel(); session.close() }
        .onChange(of: phase) { _, value in session.setActive(value == .active) }
        .onChange(of: session.channel.id) { _, _ in interact(); programmes = IPTVNowNext(); if !guide && pane == nil { focus = "surface" } }
        .onChange(of: focus) { _, value in
            if value?.hasPrefix("control.") == true || value?.hasPrefix("track.") == true { interact() }
        }
        .onPlayPauseCommand { session.toggle(); interact() }
        .onExitCommand {
            if pane != nil { closeTracks() }
            else if !guide { close() }
        }
        .task(id: "\(controls)|\(guide)|\(pane ?? "")|\(interaction)|\(phase)|\(session.problem == nil)") {
            guard controls, !guide, pane == nil, session.problem == nil, phase == .active else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            focus = "surface"; controls = false
        }
        .task(id: "\(session.channel.id)|\(phase)|\(session.channels.isEmpty)") {
            programmes = IPTVNowNext()
            guard phase == .active else { return }
            do { try await Task.sleep(for: .milliseconds(750)) } catch { return }
            while !Task.isCancelled {
                do {
                    let result = try await session.programmes()
                    guard !Task.isCancelled else { return }
                    programmes = result
                }
                catch { if Task.isCancelled { return }; programmes = IPTVNowNext() }
                do { try await Task.sleep(for: .seconds(60)) } catch { return }
            }
        }
    }
    private func errorPanel(_ problem: String) -> some View {
        VStack(spacing: 28) {
            Text(problem.replacingOccurrences(of: "على iOS", with: "على Apple TV")).multilineTextAlignment(.center)
            HStack(spacing: 28) {
                Button("تحديث الرابط وإعادة المحاولة") { session.retry(); focus = "surface" }
                    .disabled(session.retrying).focused($focus, equals: "error.retry")
                if session.controller.lastFailure == .unsupported || session.controller.lastFailure == .unknown {
                    Button("توافق موسّع") { session.controller.chooseEngine(.compatibility); focus = "surface" }
                }
                Button("القنوات", action: openGuide)
            }
        }.padding(44).frame(maxWidth: 1100).background(TVIPTVStyle.panel.opacity(0.9), in: RoundedRectangle(cornerRadius: 28))
            .focusSection().task { await Task.yield(); focus = "error.retry" }
    }
    private func trackButton(_ title: String, id: String, selected: Bool = false, run: @escaping () -> Void) -> some View {
        Button { run(); closeTracks() } label: {
            HStack(spacing: 12) { Text(title).lineLimit(1); Spacer(minLength: 4); if selected { Image(systemName: "checkmark") } }
                .font(.system(size: 26)).padding(.horizontal, 20).padding(.vertical, 12).frame(minHeight: 76)
        }.buttonStyle(TVIPTVTrackButtonStyle()).focusEffectDisabled().focused($focus, equals: id)
            .accessibilityIdentifier("tv.iptv." + id).accessibilityValue(selected ? "محدد" : "غير محدد")
    }
    private func trackPanel(_ value: String, window: CGSize) -> some View {
        let quality = value == "quality", audio = value == "audio"
        let tracks = audio ? session.audio : session.subtitles
        let defaults: [String] = audio && !tracks.isEmpty ? [] : [audio ? "المسار الافتراضي" : "إيقاف الترجمة"]
        let labels: [String] = quality ? ["جودة البث", "تلقائي", "تفضيل أعلى جودة متاحة", "محرك التشغيل"] + session.qualities.map(\.title)
                             : [audio ? "الصوت" : "الترجمة"] + defaults + tracks.map(\.title)
        let rows = quality ? session.qualities.count + 3 : tracks.count + defaults.count
        return TVIPTVTrackPopup(title: quality ? "جودة البث" : audio ? "مسار الصوت" : "الترجمة", labels: labels, rows: rows,
            footnoteLines: !quality && tracks.isEmpty ? 2 : 0,
            anchor: bounds[value] ?? CGRect(x: window.width - 600, y: window.height - 80, width: 46, height: 46),
            window: window, ready: { focus = "track.first" }) {
                if quality {
                    trackButton("تلقائي", id: "track.first", selected: !session.prefersHighestQuality && session.quality == nil && session.maximumHeight == nil) { session.chooseQuality(nil) }
                    trackButton("تفضيل أعلى جودة متاحة", id: "track.highest", selected: session.prefersHighestQuality) { session.preferHighestQuality() }
                    ForEach(session.qualities) { item in
                        trackButton(item.title, id: item.id, selected: !session.prefersHighestQuality && session.quality?.id == item.id) { session.chooseQuality(item) }
                    }
                    Menu {
                        ForEach(IPTVPlaybackMode.allCases, id: \.rawValue) { mode in
                            Button(mode.title) { session.controller.chooseEngine(mode); closeTracks() }
                                .accessibilityIdentifier("tv.iptv.engine." + mode.rawValue)
                        }
                        Text("VideoLAN VLCKit · LGPL 2.1+")
                    } label: {
                        HStack { Text("محرك التشغيل"); Spacer(); Image(systemName: "chevron.left") }
                            .font(.system(size: 26)).padding(20).frame(minHeight: 76)
                    }.menuStyle(.button).buttonStyle(TVIPTVTrackButtonStyle()).focused($focus, equals: "track.engine")
                } else if audio {
                    let first = session.audio.first
                    // Keep one stable focus target while the manifest's audio tracks arrive.
                    trackButton(first?.title ?? "المسار الافتراضي", id: "track.first", selected: first.map { session.audioID == $0.id } ?? true) {
                        if let first { session.chooseAudio(first.id) }
                    }
                    ForEach(session.audio.dropFirst()) { item in trackButton(item.title, id: item.id, selected: session.audioID == item.id) { session.chooseAudio(item.id) } }
                } else {
                    trackButton("إيقاف الترجمة", id: "track.first", selected: session.subtitleID == nil) { session.chooseSubtitle(nil) }
                    ForEach(session.subtitles) { item in trackButton(item.title, id: item.id, selected: session.subtitleID == item.id) { session.chooseSubtitle(item.id) } }
                }
                if !quality && tracks.isEmpty {
                    Text("لم يوفّر هذا البث خيارات إضافية من هذا النوع.").font(.system(size: 26)).foregroundStyle(.white.opacity(0.65)).lineLimit(2)
                }
            }
    }
}
