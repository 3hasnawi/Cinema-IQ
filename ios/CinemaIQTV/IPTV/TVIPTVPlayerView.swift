import SwiftUI
import CinemaCore

/// Remote-first live playback. The full-screen focus surface owns surfing; guide/menu rows own their own arrows.
struct TVIPTVPlayerView: View {
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @StateObject private var session: IPTVPlayerSession
    @State private var guide = false
    @State private var controls = true
    @State private var pane: String?
    @State private var interaction = 0
    @FocusState private var focus: String?
    let onReturn: (IPTVChannel, String?) -> Void
    init(initial: IPTVChannel, group: String? = nil, onReturn: @escaping (IPTVChannel, String?) -> Void = { _, _ in }) {
        self.onReturn = onReturn; _session = StateObject(wrappedValue: IPTVPlayerSession(initial: initial, group: group))
    }
    private func close() { onReturn(session.channel, session.group == session.channel.group ? session.group : nil); dismiss() }
    private func closePanel() { guide = false; pane = nil; controls = true; interaction += 1; focus = "surface" }
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black.ignoresSafeArea()
                IPTVVideoSurface(player: session.controller.player).ignoresSafeArea().allowsHitTesting(false)
                Button { guide = true } label: { Color.clear.contentShape(Rectangle()) }
                    .buttonStyle(.plain).focusEffectDisabled().focused($focus, equals: "surface")
                    .accessibilityLabel(session.channel.name).accessibilityValue(session.status)
                    .accessibilityIdentifier("tv.iptv.player.native")
                    .onMoveCommand { direction in
                        switch direction {
                        case .up: session.surf(-1); interaction += 1; controls = true
                        case .down: session.surf(1); interaction += 1; controls = true
                        case .left, .right:
                            controls = true
                            Task { @MainActor in await Task.yield(); focus = "control.channels" }
                        default: break
                        }
                    }
                if controls || !session.playing || session.tuning {
                    VStack {
                        HStack {
                            VStack(alignment: .leading, spacing: 10) {
                                Text(session.channel.name).font(.title2.bold()).lineLimit(1).accessibilityIdentifier("tv.iptv.player.title")
                                Text(session.status + " · " + session.channel.group).font(.callout).foregroundStyle(.secondary)
                            }
                            .padding(22).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22))
                            Spacer()
                        }.allowsHitTesting(false)
                        Spacer()
                        if !guide && pane == nil {
                            HStack(spacing: 22) {
                                action("القنوات", id: "channels") { guide = true }
                                action("القناة السابقة", id: "last") { session.previous(); focus = "surface" }
                                action(session.playing ? "إيقاف مؤقت" : "تشغيل", id: "play") { session.toggle() }
                                action("الجودة", id: "quality") { pane = "quality" }
                                action("الصوت", id: "audio") { pane = "audio" }
                                action("الترجمة", id: "subtitles") { pane = "subtitles" }
                                action("إغلاق", id: "close", run: close)
                            }.buttonStyle(.bordered).buttonBorderShape(.capsule).focusSection()
                            Text("↑ ↓ تبديل القنوات  ·  OK قائمة القنوات  ·  يمين/يسار خيارات التشغيل")
                                .font(.system(size: 19)).foregroundStyle(.secondary).allowsHitTesting(false)
                        }
                    }.padding(54)
                }
                if session.buffering || session.tuning || session.retrying { ProgressView().controlSize(.large).allowsHitTesting(false) }
                if let problem = session.problem, !guide, pane == nil {
                    VStack(spacing: 24) {
                        Text(problem.replacingOccurrences(of: "على iOS", with: "على Apple TV")).multilineTextAlignment(.center)
                        HStack(spacing: 28) {
                            Button("إعادة المحاولة") { session.retry(); focus = "surface" }.disabled(session.retrying)
                            Button("القنوات") { guide = true }
                            Button("إغلاق", action: close).accessibilityIdentifier("tv.iptv.player.close")
                        }
                    }.padding(44).frame(maxWidth: 1100).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28)).focusSection()
                }
                if guide {
                    HStack {
                        TVIPTVLiveGuide(session: session) { closePanel() }
                            .frame(width: min(1100, geometry.size.width * .72), height: geometry.size.height * .82)
                        Spacer(minLength: 0)
                    }.padding(.horizontal, 54)
                }
                if let pane { trackPanel(pane).frame(width: min(900, geometry.size.width * .65), height: geometry.size.height * .8) }
            }
        }
        .defaultFocus($focus, "surface")
        .onAppear { session.attach(store); focus = "surface" }
        .onDisappear { session.close() }
        .onChange(of: phase) { _, value in if value != .active { session.pause() } }
        .onChange(of: session.channel.id) { _, _ in controls = true; if !guide && pane == nil { focus = "surface" } }
        .onPlayPauseCommand { session.toggle(); controls = true; interaction += 1 }
        .onExitCommand { if guide || pane != nil { closePanel() } else { close() } }
        .task(id: "\(session.playing)|\(focus ?? "")|\(controls)|\(guide)|\(pane ?? "")|\(interaction)") {
            guard controls, focus == "surface", session.playing, !guide, pane == nil else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }; controls = false
        }
    }
    private func action(_ title: String, id: String, run: @escaping () -> Void) -> some View {
        Button(title, action: run).focused($focus, equals: "control." + id).accessibilityIdentifier("tv.iptv.player." + id)
    }
    private func trackButton(_ title: String, id: String, selected: Bool, run: @escaping () -> Void) -> some View {
        Button { run(); closePanel() } label: {
            HStack { Text(title); Spacer(); if selected { Image(systemName: "checkmark") } }.frame(maxWidth: .infinity)
        }.focused($focus, equals: id)
    }
    private func trackPanel(_ value: String) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(value == "quality" ? "جودة البث" : value == "audio" ? "الصوت" : "الترجمة").font(.title2.bold())
            ScrollView {
                VStack(spacing: 18) {
                    if value == "quality" {
                        trackButton("تلقائي", id: "track.first", selected: session.quality == nil) { session.chooseQuality(nil) }
                        ForEach(session.qualities) { quality in
                            trackButton(quality.title, id: quality.id, selected: session.quality?.id == quality.id) { session.chooseQuality(quality) }
                        }
                        Text("الجودات المتاحة من البث فقط؛ الاختيار يحدد الحد الأقصى، والتلقائي يتكيّف مع الشبكة.").font(.system(size: 22)).foregroundStyle(.secondary)
                    } else if value == "audio" {
                        ForEach(Array(session.audio.enumerated()), id: \.element.id) { index, option in
                            trackButton(option.title, id: index == 0 ? "track.first" : option.id, selected: session.audioID == option.id) { session.chooseAudio(option.id) }
                        }
                        if session.audio.isEmpty { Text("لا تتوفر مسارات صوت إضافية.") }
                    } else {
                        trackButton("إيقاف الترجمة", id: "track.first", selected: session.subtitleID == nil) { session.chooseSubtitle(nil) }
                        ForEach(session.subtitles) { option in trackButton(option.title, id: option.id, selected: session.subtitleID == option.id) { session.chooseSubtitle(option.id) } }
                    }
                    Button("إغلاق", action: closePanel).focused($focus, equals: "track.close")
                }.padding(12)
            }.buttonStyle(.bordered)
        }
        .padding(38).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(.white.opacity(.12)))
        .task(id: value) { await Task.yield(); focus = value == "audio" && session.audio.isEmpty ? "track.close" : "track.first" }
    }
}
