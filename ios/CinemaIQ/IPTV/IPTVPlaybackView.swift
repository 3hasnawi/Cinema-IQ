import SwiftUI
import UIKit
import CinemaCore

/// A touch-first live player with a floating guide; the AVPlayer stays mounted while sheets are open.
struct IPTVPlaybackView: View {
    let startInBrowser: Bool
    let initialGroup: String?
    let onReturn: (IPTVChannel, String?) -> Void
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @Environment(\.verticalSizeClass) private var verticalSize
    @StateObject private var session: IPTVPlayerSession
    @State private var controls = true
    @State private var fullscreen: Bool
    @State private var browserSection: IPTVChannelSection
    @State private var browserQuery = ""
    @State private var searching = false
    @State private var panel: Panel?
    @State private var idleSetting = false
    @State private var interaction = 0
    private enum Panel: String, Identifiable { case channels, quality, audio, subtitles; var id: String { rawValue } }
    init(initial: IPTVChannel, group: String? = nil, startInBrowser: Bool = false, onReturn: @escaping (IPTVChannel, String?) -> Void = { _, _ in }) {
        self.onReturn = onReturn; self.startInBrowser = startInBrowser; self.initialGroup = group
        _fullscreen = State(initialValue: !startInBrowser)
        _browserSection = State(initialValue: group.map(IPTVChannelSection.group) ?? .all)
        _session = StateObject(wrappedValue: IPTVPlayerSession(initial: initial, group: group))
    }
    private func close() {
        if startInBrowser && fullscreen { fullscreen = false; controls = true; return }
        onReturn(session.channel, session.group == session.channel.group ? session.group : nil); dismiss()
    }
    private func chooseInBrowser(_ channel: IPTVChannel) {
        session.group = browserSection.group
        if channel.id == session.channel.id { fullscreen = true; controls = true; interaction += 1 }
        else { session.tune(channel) }
    }
    var body: some View {
        let phoneGuide = !startInBrowser && panel == .channels && UIDevice.current.userInterfaceIdiom == .phone
        return GeometryReader { geometry in
          ZStack {
            playbackSurface(in: geometry, phoneGuide: phoneGuide).contentShape(Rectangle())
                .accessibilityElement(children: .ignore).accessibilityLabel("إظهار خيارات المشغل")
                .accessibilityIdentifier("ios.iptv.player.surface").accessibilityValue(session.status)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { fullscreen = true; controls = true; interaction += 1 }
                .onTapGesture { if !fullscreen { fullscreen = true; controls = true } else { controls.toggle() }; interaction += 1 }
                .gesture(DragGesture(minimumDistance: 50).onEnded { value in
                    guard abs(value.translation.height) > abs(value.translation.width), abs(value.translation.height) > 65 else { return }
                    session.surf(value.translation.height < 0 ? 1 : -1); controls = true; interaction += 1
                })
            if !fullscreen {
                let wide = geometry.size.width > geometry.size.height
                let previewWidth = geometry.size.width * (wide ? 0.52 : 1)
                if wide {
                    HStack(spacing: 0) {
                        inlinePreview(width: previewWidth).frame(maxHeight: .infinity, alignment: .top)
                        browserPanels
                    }.environment(\.layoutDirection, .leftToRight)
                } else {
                    VStack(spacing: 0) { inlinePreview(width: previewWidth); browserPanels }
                }
            }
            if fullscreen && !phoneGuide && (controls || session.problem != nil || !session.playing) {
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
                        .simultaneousGesture(DragGesture(minimumDistance: 4).onChanged { _ in interaction += 1 }.onEnded { _ in interaction += 1 })
                        .accessibilityIdentifier("ios.iptv.player.controls")
                }.padding(16)
            }
            if session.buffering || session.tuning || session.retrying { ProgressView().controlSize(.large).position(x: fullscreen ? geometry.size.width / 2 : geometry.size.width * (geometry.size.width > geometry.size.height ? 0.26 : 0.5), y: fullscreen ? geometry.size.height / 2 : geometry.size.width * (geometry.size.width > geometry.size.height ? 0.52 : 1) * 9 / 32).allowsHitTesting(false) }
            if let problem = session.problem, fullscreen {
                VStack(spacing: 18) {
                    Text(problem).multilineTextAlignment(.center)
                    HStack {
                        Button("إعادة المحاولة") { session.retry() }.disabled(session.retrying).primaryAction().accessibilityIdentifier("ios.iptv.player.retry")
                        Button("القنوات") { panel = .channels }.secondaryAction()
                    }
                    Button("تجربة التوافق الموسّع") { session.controller.chooseEngine(.compatibility) }.secondaryAction()
                }.padding(24).frame(maxWidth: 500).glass(in: RoundedRectangle(cornerRadius: 24)).padding(24)
            }
          }.frame(width: geometry.size.width, height: geometry.size.height)
        }
        .background(Color.black.ignoresSafeArea())
        .toolbar(fullscreen ? .hidden : .visible, for: .navigationBar, .tabBar)
        .navigationBarBackButtonHidden(fullscreen)
        .buttonStyle(.plain)
        .onAppear { idleSetting = UIApplication.shared.isIdleTimerDisabled; UIApplication.shared.isIdleTimerDisabled = true; session.attach(store) }
        .onDisappear { session.close(); UIApplication.shared.isIdleTimerDisabled = idleSetting }
        .onChange(of: initialGroup) { _, group in browserSection = group.map(IPTVChannelSection.group) ?? .all; browserQuery = "" }
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
    private func playbackSurface(in geometry: GeometryProxy, phoneGuide: Bool) -> some View {
        let width = geometry.size.width
        let canvasHeight = startInBrowser ? width * 9 / 16 : geometry.size.height
        let scale = startInBrowser ? (fullscreen ? min(1, geometry.size.height / max(1, canvasHeight)) : (width > geometry.size.height ? 0.52 : 1)) : 1
        let x = fullscreen ? (width - width * scale) / 2 : 0
        let y = startInBrowser ? (fullscreen ? (geometry.size.height - canvasHeight * scale) / 2 : 0) : 0
        return PlaybackVideoSurface(controller: session.controller)
            .frame(width: width, height: canvasHeight)
            .scaleEffect(scale, anchor: .topLeading)
            .offset(x: x, y: y + (phoneGuide && geometry.size.height > width ? -geometry.size.height * 0.25 : 0))
            .frame(width: width, height: geometry.size.height, alignment: .topLeading)
            .environment(\.layoutDirection, .leftToRight)
    }
    private var browserPanels: some View {
        VStack(spacing: 0) {
            if searching {
                TextField("ابحث عن قناة", text: $browserQuery).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .submitLabel(.search).padding(12).background(Theme.panel)
                    .accessibilityIdentifier("ios.iptv.browser.search")
            }
            IPTVBrowserPanels(session: session, section: $browserSection, query: $browserQuery, choose: chooseInBrowser)
        }.environment(\.layoutDirection, .rightToLeft)
    }
    private func inlinePreview(width: CGFloat) -> some View {
        ZStack(alignment: .bottom) {
            Color.clear
            if let problem = session.problem {
                VStack(spacing: 8) {
                    Text(problem).font(.caption).multilineTextAlignment(.center).lineLimit(3)
                    Button("إعادة المحاولة") { session.retry() }.disabled(session.retrying)
                }.padding(10).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12)).frame(maxHeight: .infinity)
            }
            HStack(spacing: 0) {
                Text("● LIVE").font(.caption.bold()).foregroundStyle(.red).padding(8).background(.black.opacity(0.45), in: Capsule())
                Spacer(minLength: 0)
                Button { store.toggleFavorite(session.channel) } label: {
                    Image(systemName: store.channelCollection.favorites.contains(session.channel.id) ? "heart.fill" : "heart").font(.title2).frame(width: 44, height: 44)
                }.accessibilityLabel(store.channelCollection.favorites.contains(session.channel.id) ? "إزالة القناة من المفضلة" : "إضافة القناة إلى المفضلة")
                    .accessibilityIdentifier("ios.iptv.browser.favorite").accessibilityValue(store.channelCollection.favorites.contains(session.channel.id) ? "محدد" : "غير محدد")
                Button { fullscreen = true; controls = true; interaction += 1 } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right").font(.title2).frame(width: 44, height: 44)
                }.accessibilityLabel("ملء الشاشة").accessibilityIdentifier("ios.iptv.browser.fullscreen")
                Button { searching.toggle(); if !searching { browserQuery = "" } } label: {
                    Image(systemName: "magnifyingglass").font(.title2).frame(width: 44, height: 44)
                }.accessibilityLabel("البحث").accessibilityIdentifier("ios.iptv.browser.search.toggle")
            }.foregroundStyle(.white).padding(6).background(.black.opacity(0.2))
                .environment(\.layoutDirection, .leftToRight)
        }.frame(width: width, height: width * 9 / 16)
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
                Section("محرك التشغيل") {
                    ForEach(IPTVPlaybackMode.allCases, id: \.rawValue) { mode in
                        Button { session.controller.chooseEngine(mode); panel = nil } label: { choice(mode.title, selected: session.engineMode == mode) }
                            .accessibilityIdentifier("ios.iptv.engine." + mode.rawValue)
                    }
                    Text(session.usesCompatibility ? "المحرك الحالي: التوافق الموسّع" : "المحرك الحالي: Apple").font(.footnote)
                    Link("VideoLAN VLCKit — LGPL 2.1 أو أحدث والمصدر", destination: URL(string: "https://code.videolan.org/videolan/VLCKit")!)
                }
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
    @State private var browsingGroup: String?
    init(session: IPTVPlayerSession, selected: @escaping () -> Void) {
        self.session = session; self.selected = selected; _browsingGroup = State(initialValue: session.group)
    }
    private var key: String { session.catalogRevision.uuidString + "|" + (browsingGroup ?? "") + "|" + query }
    var body: some View {
        let numbering = IPTVNavigation.numbers(session.channels, sourceID: session.sourceID)
        let groups = IPTVNavigation.categories(session.channels, sourceID: session.sourceID)
        return ScrollViewReader { proxy in
            HStack(alignment: .top, spacing: 0) {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        category("كل القنوات", group: nil, count: numbering.count)
                        ForEach(groups, id: \.name) { category($0.name, group: $0.name, count: $0.count) }
                    }.padding(8)
                }.frame(width: 112).background(Color.primary.opacity(0.04))
                List {
                    Section("\(visible.count) قناة") {
                        ForEach(visible) { channel in
                            Button {
                                session.group = browsingGroup
                                if channel.id != session.channel.id { session.tune(channel) }; selected()
                            } label: {
                                HStack(spacing: 10) {
                                    Text(String(numbering[channel.id] ?? 0)).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(minWidth: 18)
                                    ZStack {
                                        RoundedRectangle(cornerRadius: 8).fill(.primary.opacity(0.05))
                                        Image(systemName: "tv").foregroundStyle(.secondary)
                                        if let logo = channel.logo { AsyncImage(url: logo) { if let image = $0.image { image.resizable().scaledToFit().padding(3) } } }
                                    }.frame(width: 36, height: 32).accessibilityHidden(true)
                                    VStack(alignment: .leading, spacing: 3) { Text(channel.name).foregroundStyle(.primary).lineLimit(2); Text(channel.group).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                    Spacer(minLength: 0)
                                    if channel.id == session.channel.id { Image(systemName: "waveform").foregroundStyle(Theme.accent) }
                                }.padding(.vertical, 4)
                            }.id(channel.id).accessibilityLabel(channel.name).accessibilityIdentifier("ios.iptv.guide.channel." + channel.id)
                                .listRowBackground(channel.id == session.channel.id ? Theme.accent.opacity(0.12) : Color.clear)
                        }
                        if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.") }
                    }
                }.listStyle(.plain)
            }
            .searchable(text: $query, prompt: "ابحث عن قناة").scrollDismissesKeyboard(.interactively)
            .task(id: key) {
                let values = session.channels, source = session.sourceID, group = browsingGroup, text = query
                let result = await Task.detached { IPTVNavigation.channels(values, sourceID: source, group: group, query: text) }.value
                guard !Task.isCancelled else { return }; visible = result
                if query.isEmpty, let id = IPTVNavigation.entry(currentID: session.channel.id, in: result) { proxy.scrollTo(id, anchor: .center) }
            }
        }
        .accessibilityIdentifier("ios.iptv.guide")
    }
    private func category(_ title: String, group: String?, count: Int) -> some View {
        Button { browsingGroup = group; query = "" } label: {
            VStack(alignment: .leading, spacing: 4) { Text(title).font(.subheadline.weight(.medium)).lineLimit(3); Text(String(count)).font(.caption).foregroundStyle(.secondary) }
                .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                .background(browsingGroup == group ? Theme.accent.opacity(0.15) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain).accessibilityIdentifier("ios.iptv.guide.group." + (group ?? "all"))
    }
}
