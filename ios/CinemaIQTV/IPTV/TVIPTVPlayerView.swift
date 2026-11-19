import SwiftUI
import CinemaCore

/// The live surface stays mounted while focus moves between the guide and the HUD.
struct TVIPTVPlayerView: View {
    @EnvironmentObject private var access: TVIPTVCategoryAccess
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
    let onSources: () -> Void
    let onClose: () -> Void
    private final class PlaybackGrant {
        var id: String?
        var group: String?
    }
    @State private var surfVisit: String?
    @State private var grant: PlaybackGrant

    init(initial: IPTVChannel, group: String?, onSources: @escaping () -> Void, onClose: @escaping () -> Void) {
        self.onSources = onSources; self.onClose = onClose
        let grant = PlaybackGrant()
        if group == initial.group { grant.id = initial.id; grant.group = group }
        _grant = State(initialValue: grant)
        _surfVisit = State(initialValue: group)
        let preference = VideoQuality(rawValue: UserDefaults.standard.string(forKey: TVPlaybackPreference.qualityKey) ?? "auto") ?? .automatic
        _session = StateObject(wrappedValue: IPTVPlayerSession(initial: initial, group: group, preferredMaximumHeight: preference.height, automaticallyReconnect: true, automaticEngine: true))
    }
    private func permits(_ channel: IPTVChannel) -> Bool {
        access.rules(channel.sourceID).allows(channel.group, visit: grant.id == channel.id ? grant.group : nil)
    }
    private func surf(_ direction: Int) {
        guard let index = access.guide, index.sourceID == session.sourceID else { return }
        let rules = access.rules(index.sourceID)
        let section = session.section.group.map { rules.allows($0, visit: surfVisit) } == false ? .all : session.section
        let rows = index.rows(section: section, visit: surfVisit, favorites: store.channelCollection.favorites)
        if let next = IPTVNavigation.neighbor(of: session.channel.id, step: direction, in: rows),
           next.id != session.channel.id, rules.allows(next.group, visit: surfVisit) {
            grant.id = next.id; grant.group = surfVisit; session.section = section; session.tune(next, debounce: true)
        }
        interact()
    }
    private func validateCurrent() {
        let current = session.channels.first { $0.id == session.channel.id } ?? session.channel
        guard !permits(current) else { return }
        if let next = session.channels.first(where: { access.rules($0.sourceID).allows($0.group) }) {
            session.section = .all; session.tune(next)
        } else { session.close(); dismiss() }
    }
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
        surfVisit = nil
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
                    .accessibilityLabel(session.channel.televisionTitle).accessibilityValue(session.status)
                    .accessibilityIdentifier("tv.iptv.player.native")
                    .onMoveCommand { direction in
                        switch direction {
                        case .up: surf(-1)
                        case .down: surf(1)
                        case .left, .right: showToolbar()
                        default: break
                        }
                    }
                if !guide && (controls || pane != nil) {
                    TVIPTVPlayerHUD(session: session, programmes: programmes, focus: $focus,
                        channels: openGuide, tracks: openTracks, iconBounds: { bounds[$0] = $1 }, surf: surf, interact: interact)
                        .disabled(guide || pane != nil || session.problem != nil)
                }
                if (session.buffering || session.tuning || session.retrying) && !guide {
                    ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity).allowsHitTesting(false)
                }
                if let problem = session.problem, !guide, pane == nil { errorPanel(problem).frame(maxWidth: .infinity, maxHeight: .infinity) }
                if guide, let index = access.guide, index.sourceID == session.sourceID {
                    TVIPTVLiveGuide(session: session, index: index, sources: { onSources(); dismiss() }, selected: { channel, section, visit in
                        guard access.rules(channel.sourceID).allows(channel.group, visit: visit) else { return }
                        surfVisit = visit; grant.id = channel.id; grant.group = visit; session.section = section; session.group = section.group
                        if session.channel.id != channel.id { session.tune(channel) }
                        closeGuide()
                    }, close: closeGuide)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .transition(.opacity)
                }
                if let pane { trackPanel(pane, window: geometry.size) }
            }.coordinateSpace(name: "tv.iptv.player.space")
        }
        .ignoresSafeArea()
        // Popup placement uses physical screen coordinates; individual HUD/panel text remains RTL.
        .environment(\.layoutDirection, .leftToRight)
        .defaultFocus($focus, "surface")
        .onAppear { session.permitsPlayback = { [access, grant] channel in
            access.rules(channel.sourceID).allows(channel.group, visit: grant.id == channel.id ? grant.group : nil)
        }; session.attach(store); session.setActive(phase == .active); if !guide { focus = "surface" } }
        .onDisappear { restoreTask?.cancel(); session.close() }
        .onChange(of: access.revision) { _, _ in surfVisit = nil; grant.id = nil; grant.group = nil; validateCurrent() }
        .onChange(of: session.catalogRevision) { _, _ in validateCurrent() }
        .onChange(of: phase) { _, value in session.setActive(value == .active) }
        .onChange(of: session.channel.id) { _, _ in interact(); programmes = IPTVNowNext(); if !guide && pane == nil { focus = "surface" } }
        .onChange(of: focus) { _, value in
            if value?.hasPrefix("control.") == true || value?.hasPrefix("track.") == true { interact() }
        }
        .onPlayPauseCommand { session.toggle(); interact() }
        .onExitCommand(perform: guide ? nil : {
            if pane != nil { closeTracks() }
            else if controls && session.problem == nil { restoreTask?.cancel(); controls = false; focus = "surface" }
            else { onClose(); dismiss() }
        })
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
                Button("القنوات", action: openGuide)
            }
        }.padding(44).frame(maxWidth: 1100).background(TVIPTVStyle.panel.opacity(0.9), in: RoundedRectangle(cornerRadius: 28))
            .environment(\.layoutDirection, .rightToLeft)
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
        let labels: [String] = quality ? ["جودة البث", "تلقائي", "تفضيل أعلى جودة متاحة"] + session.qualities.map(\.title)
                             : [audio ? "الصوت" : "الترجمة"] + defaults + tracks.map(\.title)
        let rows = quality ? session.qualities.count + 2 : tracks.count + defaults.count
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
