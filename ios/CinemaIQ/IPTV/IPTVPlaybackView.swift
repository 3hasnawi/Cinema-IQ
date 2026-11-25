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
    /// False while another root tab is selected. The player and its polling must stop then, not keep decoding.
    @Environment(\.iptvTabVisible) private var tabVisible
    @Environment(\.verticalSizeClass) private var verticalSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var session: IPTVPlayerSession
    @State private var controls = true
    @State private var fullscreen: Bool
    @State private var browserSection: IPTVChannelSection
    @State private var browserQuery = ""
    @State private var searching = false
    @FocusState private var searchFocused: Bool
    @State private var panel: Panel?
    @State private var idleSetting = false
    @State private var interaction = 0
    @State private var viewport = CGSize.zero
    @State private var programmes = IPTVNowNext()
    private enum Panel: String, Identifiable { case channels, quality, audio, subtitles; var id: String { rawValue } }
    init(initial: IPTVChannel, group: String? = nil, startInBrowser: Bool = false, onReturn: @escaping (IPTVChannel, String?) -> Void = { _, _ in }) {
        self.onReturn = onReturn; self.startInBrowser = startInBrowser; self.initialGroup = group
        _fullscreen = State(initialValue: !startInBrowser)
        _browserSection = State(initialValue: group.map(IPTVChannelSection.group) ?? .all)
        let preference = VideoQuality(rawValue: UserDefaults.standard.string(forKey: PhonePlaybackPreference.qualityKey) ?? "auto") ?? .automatic
        _session = StateObject(wrappedValue: IPTVPlayerSession(initial: initial, group: group,
            preferredMaximumHeight: preference.height, automaticallyReconnect: true))
    }
    private func close() {
        searchFocused = false
        if startInBrowser && fullscreen { fullscreen = false; controls = true; return }
        onReturn(session.channel, session.group == session.channel.group ? session.group : nil); dismiss()
    }
    private func chooseInBrowser(_ channel: IPTVChannel) {
        searchFocused = false
        session.section = browserSection
        session.group = browserSection.group
        if channel.id == session.channel.id { fullscreen = true; controls = true; interaction += 1 }
        else { session.tune(channel) }
    }
    // Task identities are built here so the body's modifier chain stays within the type checker's budget.
    private var controlsKey: String {
        "\(interaction)|\(panel?.rawValue ?? "")|\(session.playing)|\(controls)|\(fullscreen)|\(phase)|\(tabVisible)|\(searchFocused)"
    }
    private var programmeKey: String {
        "\(session.channel.id)|\(phase)|\(tabVisible)|\(session.channels.isEmpty)"
    }
    var body: some View {
        let phoneGuide = !startInBrowser && panel == .channels && UIDevice.current.userInterfaceIdiom == .phone
        return screen(phoneGuide: phoneGuide)
        .onAppear {
            idleSetting = UIApplication.shared.isIdleTimerDisabled; UIApplication.shared.isIdleTimerDisabled = true
            session.attach(store); session.setActive(phase == .active && tabVisible)
        }
        .onDisappear { searchFocused = false; session.close(); UIApplication.shared.isIdleTimerDisabled = idleSetting }
        .onChange(of: searching) { _, visible in searchFocused = visible }
        .onChange(of: initialGroup) { _, group in browserSection = group.map(IPTVChannelSection.group) ?? .all; browserQuery = "" }
        .onChange(of: browserSection) { _, value in searchFocused = false; session.section = value; session.group = value.group }
        .onChange(of: session.section) { _, value in
            guard browserSection != value else { return }
            searchFocused = false; browserQuery = ""; browserSection = value
        }
        .onChange(of: fullscreen) { _, value in if value { searchFocused = false }; controls = true; interaction += 1 }
        .onChange(of: session.channel.id) { _, _ in controls = true; programmes = IPTVNowNext(); interaction += 1 }
        .onChange(of: verticalSize) { _, _ in controls = true; interaction += 1 }
        .onChange(of: phase) { _, value in
            session.setActive(value == .active && tabVisible)
            if value != .active { searchFocused = false; UIApplication.shared.isIdleTimerDisabled = idleSetting }
            else { UIApplication.shared.isIdleTimerDisabled = true }
        }
        .onChange(of: tabVisible) { _, visible in
            session.setActive(phase == .active && visible)
            if !visible { searchFocused = false }
        }
        .task(id: controlsKey) {
            guard controls, fullscreen, panel == nil, session.playing, session.problem == nil, phase == .active, tabVisible, !searchFocused else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            if !UIAccessibility.isVoiceOverRunning { controls = false }
        }
        .task(id: programmeKey) {
            programmes = IPTVNowNext()
            // The guide poll runs once a minute; it stops as soon as this player is hidden.
            guard phase == .active, tabVisible else { return }
            do { try await Task.sleep(for: .milliseconds(750)) } catch { return }
            while !Task.isCancelled {
                do {
                    let value = try await session.programmes()
                    guard !Task.isCancelled else { return }; programmes = value
                } catch { if Task.isCancelled { return }; programmes = IPTVNowNext() }
                do { try await Task.sleep(for: .seconds(60)) } catch { return }
            }
        }
        .sheet(isPresented: panelBinding(.channels), onDismiss: panelDismissed) {
            NavigationStack {
                IPTVLiveGuide(session: session) { panel = nil }
                .navigationTitle("القنوات").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("تم") { panel = nil } } }
            }
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .presentationBackground(.ultraThinMaterial).environment(\.layoutDirection, .rightToLeft)
        }
        .persistentSystemOverlays(.hidden)
    }
    /// The player surface, guide and panels, without the lifecycle modifiers below.
    private func screen(phoneGuide: Bool) -> some View {
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
                // Preserve the field and scroll identities when the keyboard changes available height.
                let arrangement = wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
                arrangement {
                    inlinePreview(width: previewWidth).frame(maxHeight: wide ? .infinity : nil, alignment: .top)
                    browserPanels
                }.environment(\.layoutDirection, .leftToRight)
            }
            if fullscreen && !phoneGuide && (controls || session.problem != nil || !session.playing) {
                VStack(spacing: 16) {
                    HStack(spacing: 12) {
                        Button(action: close) { Image(systemName: "xmark").padding(12) }
                            .accessibilityLabel("إغلاق المشغل").accessibilityIdentifier("ios.iptv.player.close")
                        IPTVChannelLogo(channel: session.channel).frame(width: 64, height: 64)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.channel.name).font(.headline).lineLimit(1).accessibilityIdentifier("ios.iptv.player.title")
                            Text(session.status).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("ios.iptv.player.status")
                            IPTVProgrammeSummary(programmes: programmes)
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
                            control("slider.horizontal.3", "الجودة", id: "quality", tracks: .quality) { showPanel(.quality) }
                            control("waveform", "الصوت", id: "audio", tracks: .audio) { showPanel(.audio) }
                            control("captions.bubble", "الترجمة", id: "subtitles", tracks: .subtitles) { showPanel(.subtitles) }
                            control(store.channelCollection.favorites.contains(session.channel.id) ? "heart.fill" : "heart", "المفضلة", id: "favorite") {
                                store.toggleFavorite(session.channel)
                            }.accessibilityValue(store.channelCollection.favorites.contains(session.channel.id) ? "محدد" : "غير محدد")
                            control("arrow.uturn.backward", "آخر قناة", id: "last") { session.previous(); interaction += 1 }
                                .disabled(session.previousID == nil)
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
            .onGeometryChange(for: CGSize.self, of: { $0.size }) { viewport = $0 }
        }
        .background(Color.black.ignoresSafeArea())
        // Keep the video/browser arrangement stable when a portrait keyboard reduces safe-area height.
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .toolbar(fullscreen ? .hidden : .visible, for: .navigationBar, .tabBar)
        .navigationBarBackButtonHidden(fullscreen)
        .buttonStyle(.plain)
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
                    .submitLabel(.search).focused($searchFocused).onSubmit { searchFocused = false }
                    .padding(12).background(Theme.panel)
                    .accessibilityIdentifier("ios.iptv.browser.search")
            }
            IPTVBrowserPanels(session: session, section: $browserSection, query: $browserQuery, choose: chooseInBrowser)
        }.environment(\.layoutDirection, .rightToLeft)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    if searching {
                        Spacer(); Button("تم") { searchFocused = false }.accessibilityIdentifier("ios.iptv.browser.search.done")
                    }
                }
            }
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
    private func showPanel(_ value: Panel) {
        searchFocused = false; controls = true; interaction += 1
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { panel = value }
    }
    private func panelBinding(_ value: Panel) -> Binding<Bool> {
        Binding(get: { panel == value }, set: { visible in
            if !visible && panel == value { panel = nil; panelDismissed() }
        })
    }
    private func panelDismissed() { controls = true; interaction += 1 }
    private func trackRows(_ value: Panel) -> Int {
        switch value {
        case .quality: return session.qualities.count + 5
        case .audio: return max(1, session.audio.count)
        case .subtitles: return session.subtitles.count + 1
        case .channels: return 1
        }
    }
    @ViewBuilder private func trackOptions(_ value: Panel) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if value == .quality {
                Button { session.chooseQuality(nil); panel = nil } label: { choice("تلقائي", selected: !session.prefersHighestQuality && session.quality == nil && session.maximumHeight == nil) }
                    .accessibilityIdentifier("ios.iptv.quality.auto")
                    .accessibilityValue(!session.prefersHighestQuality && session.quality == nil && session.maximumHeight == nil ? "محدد" : "غير محدد")
                Button { session.preferHighestQuality(); panel = nil } label: { choice("تفضيل أعلى جودة متاحة", selected: session.prefersHighestQuality) }
                    .accessibilityIdentifier("ios.iptv.quality.highest")
                    .accessibilityValue(session.prefersHighestQuality ? "محدد" : "غير محدد")
                ForEach(session.qualities) { quality in Button { session.chooseQuality(quality); panel = nil } label: { choice(quality.title, selected: !session.prefersHighestQuality && session.quality?.id == quality.id) } }
                if let height = session.maximumHeight { Text("الحد الأقصى الحالي: \(height)p").font(.footnote) }
                Menu {
                    ForEach(IPTVPlaybackMode.allCases, id: \.rawValue) { mode in
                        Button { session.controller.chooseEngine(mode); panel = nil } label: {
                            Label(mode.title, systemImage: session.engineMode == mode ? "checkmark" : "play.rectangle")
                        }
                            .accessibilityIdentifier("ios.iptv.engine." + mode.rawValue)
                    }
                    Text(session.usesCompatibility ? "المحرك الحالي: التوافق الموسّع" : "المحرك الحالي: Apple").font(.footnote)
                    Link("VideoLAN VLCKit — LGPL 2.1 أو أحدث والمصدر", destination: URL(string: "https://code.videolan.org/videolan/VLCKit")!)
                } label: { HStack { Text("محرك التشغيل"); Spacer(); Image(systemName: "chevron.down") }.padding(10).frame(minHeight: 44) }
                Text("تُعرض الجودات التي يعلنها البث فقط. التلقائي يتكيّف مع الشبكة، وأعلى جودة يزيل الحدود الأقل مع الحفاظ على الصوت والترجمة.")
                    .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 10)
            } else if value == .audio {
                ForEach(session.audio) { option in Button { session.chooseAudio(option.id); panel = nil } label: { choice(option.title, selected: session.audioID == option.id) } }
                if session.audio.isEmpty { Text("لا تتوفر مسارات صوت إضافية لهذا البث.") }
            } else {
                Button { session.chooseSubtitle(nil); panel = nil } label: { choice("إيقاف الترجمة", selected: session.subtitleID == nil) }
                ForEach(session.subtitles) { option in Button { session.chooseSubtitle(option.id); panel = nil } label: { choice(option.title, selected: session.subtitleID == option.id) } }
            }
        }
    }
    private func choice(_ text: String, selected: Bool) -> some View {
        HStack { Text(text).multilineTextAlignment(.leading); Spacer(minLength: 8); if selected { Image(systemName: "checkmark").foregroundStyle(Theme.accent) } }
            .padding(10).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .background(selected ? Theme.accent.opacity(0.12) : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
    }
    @ViewBuilder private func control(_ symbol: String, _ label: String, id: String, tracks: Panel? = nil, action: @escaping () -> Void) -> some View {
        let button = Button { interaction += 1; action() } label: { Image(systemName: symbol).font(.title3).frame(width: 38, height: 38) }
            .accessibilityLabel(label).accessibilityIdentifier("ios.iptv.player." + id)
        if let tracks {
            button.popover(isPresented: panelBinding(tracks), arrowEdge: .bottom) {
                IPTVTouchTrackPopup(title: title(tracks), rows: trackRows(tracks), window: viewport, close: { panel = nil; panelDismissed() }) {
                    trackOptions(tracks)
                }.onDisappear(perform: panelDismissed)
            }
        } else { button }
    }
}

private struct IPTVLiveGuide: View {
    @ObservedObject var session: IPTVPlayerSession
    @EnvironmentObject private var store: IPTVStore
    let selected: () -> Void
    @State private var query = ""
    @State private var visible: [IPTVChannel] = []
    @State private var browsingSection: IPTVChannelSection
    @State private var groups: [(name: String, count: Int)] = []
    @FocusState private var searchFocused: Bool
    private struct FilterKey: Equatable {
        let revision: UUID; let section: IPTVChannelSection; let query: String; let collection: IPTVChannelCollection
    }
    init(session: IPTVPlayerSession, selected: @escaping () -> Void) {
        self.session = session; self.selected = selected; _browsingSection = State(initialValue: session.section)
    }
    private var key: FilterKey { .init(revision: session.catalogRevision, section: browsingSection, query: query, collection: store.channelCollection) }
    var body: some View {
        ScrollViewReader { proxy in
          VStack(spacing: 0) {
            IPTVSearchInput(text: $query, prompt: "ابحث عن قناة", focused: $searchFocused, identifier: "ios.iptv.guide.search")
                .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 4)
            HStack(alignment: .top, spacing: 0) {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        category(.recent)
                        category(.favorites)
                        category(.all, count: session.numbers.count)
                        ForEach(groups, id: \.name) { category(.group($0.name), count: $0.count) }
                    }.padding(8)
                }.frame(width: 112).background(Color.primary.opacity(0.04))
                List {
                    Section("\(visible.count) قناة") {
                        ForEach(visible) { channel in
                            Button {
                                searchFocused = false; session.section = browsingSection; session.group = browsingSection.group
                                if channel.id != session.channel.id { session.tune(channel) }; selected()
                            } label: {
                                HStack(spacing: 10) {
                                    Text(String(session.numbers[channel.id] ?? 0)).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(minWidth: 18)
                                    IPTVChannelLogo(channel: channel).frame(width: 46, height: 46)
                                    VStack(alignment: .leading, spacing: 3) { Text(channel.name).foregroundStyle(.primary).lineLimit(2); Text(channel.group).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                    Spacer(minLength: 0)
                                    if channel.id == session.channel.id { Image(systemName: "waveform").foregroundStyle(Theme.accent) }
                                }.padding(.vertical, 4)
                            }.id(channel.id).accessibilityLabel(channel.name).accessibilityIdentifier("ios.iptv.guide.channel." + channel.id)
                                .listRowBackground(channel.id == session.channel.id ? Theme.accent.opacity(0.12) : Color.clear)
                        }
                        if visible.isEmpty { Text("لا توجد قنوات تطابق البحث أو التصنيف.") }
                    }
                }.listStyle(.plain).accessibilityIdentifier("ios.iptv.guide")
            }
          }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer(); Button("تم") { searchFocused = false }.accessibilityIdentifier("ios.iptv.guide.search.done")
                }
            }
            .onDisappear { searchFocused = false }
            .task(id: key) {
                let values = session.channels, source = session.sourceID, section = browsingSection, text = query, collection = store.channelCollection
                let result = await Task.detached { collection.channels(values, sourceID: source, section: section, query: text) }.value
                guard !Task.isCancelled else { return }; visible = result
                if query.isEmpty, let id = IPTVNavigation.entry(currentID: session.channel.id, in: result) { proxy.scrollTo(id, anchor: .center) }
            }
        }
        .task(id: session.catalogRevision) {
            let values = session.channels, source = session.sourceID
            let result = await Task.detached { IPTVNavigation.categories(values, sourceID: source) }.value
            guard !Task.isCancelled else { return }; groups = result
        }
    }
    private func category(_ section: IPTVChannelSection, count: Int? = nil) -> some View {
        Button { searchFocused = false; browsingSection = section; query = "" } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(section.title).font(.subheadline.weight(.medium)).lineLimit(3)
                if let count { Text(String(count)).font(.caption).foregroundStyle(.secondary) }
            }
                .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                .background(browsingSection == section ? Theme.accent.opacity(0.15) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain).accessibilityIdentifier("ios.iptv.guide.group." + (section == .all ? "all" : section.group ?? section.title))
    }
}
