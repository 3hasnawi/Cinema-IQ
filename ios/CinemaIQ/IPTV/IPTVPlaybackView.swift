import SwiftUI
import UIKit
import CinemaCore

/// A touch-first live player with a floating guide; the AVPlayer stays mounted while sheets are open.
struct IPTVPlaybackView: View {
    let onReturn: (IPTVChannel, String?) -> Void
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @Environment(\.verticalSizeClass) private var verticalSize
    @StateObject private var session: IPTVPlayerSession
    @State private var controls = true
    @State private var panel: Panel?
    @State private var idleSetting = false
    @State private var interaction = 0
    private enum Panel: String, Identifiable { case channels, quality, audio, subtitles; var id: String { rawValue } }
    init(initial: IPTVChannel, group: String? = nil, onReturn: @escaping (IPTVChannel, String?) -> Void = { _, _ in }) {
        self.onReturn = onReturn; _session = StateObject(wrappedValue: IPTVPlayerSession(initial: initial, group: group))
    }
    private func close() { onReturn(session.channel, session.group == session.channel.group ? session.group : nil); dismiss() }
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            IPTVVideoSurface(player: session.controller.player).ignoresSafeArea().contentShape(Rectangle())
                .accessibilityElement(children: .ignore).accessibilityLabel("إظهار خيارات المشغل")
                .accessibilityIdentifier("ios.iptv.player.surface").accessibilityValue(session.status)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { controls = true; interaction += 1 }
                .onTapGesture { controls.toggle(); interaction += 1 }
                .gesture(DragGesture(minimumDistance: 50).onEnded { value in
                    guard abs(value.translation.height) > abs(value.translation.width), abs(value.translation.height) > 65 else { return }
                    session.surf(value.translation.height < 0 ? 1 : -1); controls = true; interaction += 1
                })
            if controls || session.problem != nil || !session.playing {
                VStack(spacing: 16) {
                    HStack(spacing: 12) {
                        Button(action: close) { Image(systemName: "xmark").padding(12) }
                            .accessibilityLabel("إغلاق المشغل").accessibilityIdentifier("ios.iptv.player.close")
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.channel.name).font(.headline).lineLimit(1).accessibilityIdentifier("ios.iptv.player.title")
                            Text(session.status).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("ios.iptv.player.status")
                        }
                        Spacer(minLength: 4); RoutePicker().frame(width: 40, height: 40)
                    }
                    .padding(12).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22))
                    Spacer()
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 22) {
                            control("list.bullet", "القنوات", id: "channels") { panel = .channels }
                            control("chevron.up", "القناة السابقة", id: "previous") { session.surf(-1); interaction += 1 }
                            control(session.playing ? "pause.fill" : "play.fill", session.playing ? "إيقاف مؤقت" : "تشغيل", id: "play") { session.toggle(); interaction += 1 }
                            control("chevron.down", "القناة التالية", id: "next") { session.surf(1); interaction += 1 }
                            control("slider.horizontal.3", "الجودة", id: "quality") { panel = .quality }
                            control("waveform", "الصوت", id: "audio") { panel = .audio }
                            control("captions.bubble", "الترجمة", id: "subtitles") { panel = .subtitles }
                        }.padding(.horizontal, 18).padding(.vertical, 12)
                    }.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22))
                }.padding(16)
            }
            if session.buffering || session.tuning || session.retrying { ProgressView().controlSize(.large).allowsHitTesting(false) }
            if let problem = session.problem {
                VStack(spacing: 18) {
                    Text(problem).multilineTextAlignment(.center)
                    HStack {
                        Button("إعادة المحاولة") { session.retry() }.disabled(session.retrying).primaryAction().accessibilityIdentifier("ios.iptv.player.retry")
                        Button("القنوات") { panel = .channels }.secondaryAction()
                    }
                }.padding(24).frame(maxWidth: 500).glass(in: RoundedRectangle(cornerRadius: 24)).padding(24)
            }
        }
        .buttonStyle(.plain)
        .onAppear { idleSetting = UIApplication.shared.isIdleTimerDisabled; UIApplication.shared.isIdleTimerDisabled = true; session.attach(store) }
        .onDisappear { session.close(); UIApplication.shared.isIdleTimerDisabled = idleSetting }
        .onChange(of: verticalSize) { _, _ in controls = true; interaction += 1 }
        .onChange(of: phase) { _, value in
            if value != .active { session.pause(); UIApplication.shared.isIdleTimerDisabled = idleSetting }
            else { UIApplication.shared.isIdleTimerDisabled = true }
        }
        .task(id: "\(interaction)|\(panel?.rawValue ?? "")|\(session.playing)|\(controls)") {
            guard controls, panel == nil, session.playing else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            if !UIAccessibility.isVoiceOverRunning { controls = false }
        }
        .sheet(item: $panel, onDismiss: { interaction += 1; controls = true }) { value in
            NavigationStack {
                Group {
                    if value == .channels { IPTVLiveGuide(session: session) { panel = nil } }
                    else { trackOptions(value) }
                }
                .navigationTitle(title(value)).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("تم") { panel = nil } } }
            }
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .presentationBackground(.ultraThinMaterial).environment(\.layoutDirection, .rightToLeft)
        }
        .persistentSystemOverlays(.hidden)
    }
    private func title(_ value: Panel) -> String {
        switch value { case .channels: return "القنوات"; case .quality: return "جودة البث"; case .audio: return "الصوت"; case .subtitles: return "الترجمة" }
    }
    @ViewBuilder private func trackOptions(_ value: Panel) -> some View {
        List {
            if value == .quality {
                Button { session.chooseQuality(nil); panel = nil } label: { choice("تلقائي", selected: !session.prefersHighestQuality && session.quality == nil && session.maximumHeight == nil) }
                    .accessibilityIdentifier("ios.iptv.quality.auto")
                    .accessibilityValue(!session.prefersHighestQuality && session.quality == nil && session.maximumHeight == nil ? "محدد" : "غير محدد")
                Button { session.preferHighestQuality(); panel = nil } label: { choice("تفضيل أعلى جودة متاحة", selected: session.prefersHighestQuality) }
                    .accessibilityIdentifier("ios.iptv.quality.highest")
                    .accessibilityValue(session.prefersHighestQuality ? "محدد" : "غير محدد")
                ForEach(session.qualities) { quality in Button { session.chooseQuality(quality); panel = nil } label: { choice(quality.title, selected: !session.prefersHighestQuality && session.quality?.id == quality.id) } }
                if let height = session.maximumHeight { Text("الحد الأقصى الحالي: \(height)p").font(.footnote) }
                Text("تُعرض الجودات التي يعلنها البث فقط. الاختيار يحدد الحد الأقصى ويحافظ على الصوت والترجمة؛ التلقائي يتكيّف مع الشبكة.").font(.footnote).foregroundStyle(.secondary)
                Text("أعلى جودة تزيل حدود الدقة الأقل؛ تبقى الجودة الفعلية بحسب البث والشبكة وقدرة الجهاز.").font(.footnote).foregroundStyle(.secondary)
            } else if value == .audio {
                ForEach(session.audio) { option in Button { session.chooseAudio(option.id); panel = nil } label: { choice(option.title, selected: session.audioID == option.id) } }
                if session.audio.isEmpty { Text("لا تتوفر مسارات صوت إضافية لهذا البث.") }
            } else {
                Button { session.chooseSubtitle(nil); panel = nil } label: { choice("إيقاف الترجمة", selected: session.subtitleID == nil) }
                ForEach(session.subtitles) { option in Button { session.chooseSubtitle(option.id); panel = nil } label: { choice(option.title, selected: session.subtitleID == option.id) } }
            }
        }
    }
    private func choice(_ text: String, selected: Bool) -> some View { HStack { Text(text); Spacer(); if selected { Image(systemName: "checkmark").foregroundStyle(Theme.accent) } } }
    private func control(_ symbol: String, _ label: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.title3).frame(width: 38, height: 38) }
            .accessibilityLabel(label).accessibilityIdentifier("ios.iptv.player." + id)
    }
}

private struct IPTVLiveGuide: View {
    @ObservedObject var session: IPTVPlayerSession
    let selected: () -> Void
    @State private var query = ""
    @State private var visible: [IPTVChannel] = []
    private var key: String { session.catalogRevision.uuidString + "|" + (session.group ?? "") + "|" + query }
    var body: some View {
        ScrollViewReader { proxy in
            List {
                Section {
                    Picker("التصنيف", selection: $session.group) {
                        Text("كل القنوات").tag(Optional<String>.none)
                        ForEach(session.groups, id: \.self) { Text($0).tag(Optional($0)) }
                    }.pickerStyle(.menu).accessibilityIdentifier("ios.iptv.guide.groups")
                }
                Section("\(visible.count) قناة") {
                    ForEach(visible) { channel in
                        Button { session.tune(channel); selected() } label: {
                            HStack(spacing: 12) {
                                Image(systemName: channel.id == session.channel.id ? "waveform" : "tv").foregroundStyle(Theme.accent)
                                VStack(alignment: .leading) { Text(channel.name).foregroundStyle(.primary); Text(channel.group).font(.caption).foregroundStyle(.secondary) }
                                Spacer(); if channel.id == session.channel.id { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                            }.padding(.vertical, 4)
                        }.id(channel.id).accessibilityLabel(channel.name).accessibilityIdentifier("ios.iptv.guide.channel." + channel.id)
                    }
                    if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.") }
                }
            }
            .searchable(text: $query, prompt: "ابحث عن قناة").scrollDismissesKeyboard(.interactively)
            .task(id: key) {
                let values = session.channels, source = session.sourceID, group = session.group, text = query
                let result = await Task.detached { IPTVNavigation.channels(values, sourceID: source, group: group, query: text) }.value
                guard !Task.isCancelled else { return }; visible = result
                if query.isEmpty, let id = IPTVNavigation.entry(currentID: session.channel.id, in: result) { proxy.scrollTo(id, anchor: .center) }
            }
        }
        .accessibilityIdentifier("ios.iptv.guide")
    }
}
