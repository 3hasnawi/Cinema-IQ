import SwiftUI
import GoogleCast
import CinemaCore

/// Only included in the iPhone target. The Apple TV player continues to use AVPlayer directly.
@MainActor final class CastSender: NSObject, ObservableObject, GCKSessionManagerListener, GCKRequestDelegate {
    @Published var connected = false
    @Published var active = false
    @Published var error: String?
    @Published var pending = false
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var playing = false
    @Published var finished = false
    @Published var quality: String?
    @Published var subtitle: Int = 0
    @Published var showControls = false
    var media: Media?
    var streams: [StreamChoice] = []
    var tracks: [SubtitleTrack] = []
    var localPosition: () -> Double = { 0 }
    var didLoad: () -> Void = {}
    var didDisconnect: (Double, Bool) -> Void = { _, _ in }
    var save: (Double, Double, Bool) -> Void = { _, _, _ in }
    private var timer: Timer?
    private var loadRequest: GCKRequest?
    private var pendingQuality: String?
    private var pendingSubtitle: Int?
    private var subtitleRequest: GCKRequest?
    private var manager: GCKSessionManager { GCKCastContext.sharedInstance().sessionManager }
    private var client: GCKRemoteMediaClient? { manager.currentCastSession?.remoteMediaClient }
    var castTracks: [SubtitleTrack] { tracks.filter(CastMediaPolicy.subtitle) }

    static func configure() {
        let discovery = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: discovery)
        options.startDiscoveryAfterFirstTapOnCastButton = true
        GCKCastContext.setSharedInstanceWith(options)
    }
    func start() {
        manager.add(self)
        connected = manager.currentCastSession?.connectionState == .connected
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
    }
    func open(_ stream: StreamChoice, at: Double, autoplay: Bool = true) {
        guard let client, let media else { return }
        guard let mime = CastMediaPolicy.mime(stream.url) else {
            error = "هذه الصيغة غير مدعومة بالكاست. جرّب جودة أخرى."; return
        }
        let metadata = GCKMediaMetadata(metadataType: .movie)
        metadata.setString(media.title, forKey: kGCKMetadataKeyTitle)
        let info = GCKMediaInformationBuilder(contentURL: stream.url)
        info.contentType = mime; info.streamType = .buffered; info.metadata = metadata
        info.customData = ["cinemaID": media.id]
        info.mediaTracks = castTracks.enumerated().compactMap { index, track in
            GCKMediaTrack(identifier: index + 1, contentIdentifier: track.url.absoluteString,
                          contentType: "text/vtt", type: .text, textSubtype: .subtitles,
                          name: track.title, languageCode: track.language, customData: nil)
        }
        let request = GCKMediaLoadRequestDataBuilder()
        request.mediaInformation = info.build(); request.startTime = max(0, at)
        request.autoplay = NSNumber(value: autoplay)
        request.activeTrackIDs = subtitle > 0 ? [NSNumber(value: subtitle)] : []
        pending = true; error = nil; pendingQuality = stream.id
        loadRequest = client.loadMedia(with: request.build())
        loadRequest?.delegate = self
    }
    func connectCurrent() {
        guard connected, let stream = streams.first(where: { $0.id == quality }) ?? streams.first else { return }
        let id = (client?.mediaStatus?.mediaInformation?.customData as? [String: Any])?["cinemaID"] as? String
        if id == media?.id { active = true; didLoad(); poll() }
        else { open(stream, at: localPosition()) }
    }
    func requestDidComplete(_ request: GCKRequest) {
        if request === loadRequest {
            pending = false; active = true; finished = false; quality = pendingQuality; didLoad(); poll()
        } else if request === subtitleRequest { subtitle = pendingSubtitle ?? 0 }
    }
    func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        if request === loadRequest { pending = false }
        self.error = "تعذّر إرسال الطلب إلى الكاست. تحقق من شبكة المصدر أو جرّب جودة أخرى."
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
        connected = true; showControls = true; connectCurrent()
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) {
        connected = true; showControls = true; connectCurrent()
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) {
        let wasActive = active
        connected = false; active = false; pending = false
        if wasActive { didDisconnect(position, finished) }
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKSession, withError error: Error) {
        self.error = "تعذّر الاتصال بجهاز الكاست."; showControls = true
    }
    private func poll() {
        guard let client, let status = client.mediaStatus,
              let id = (status.mediaInformation?.customData as? [String: Any])?["cinemaID"] as? String,
              id == media?.id else { return }
        position = max(0, client.approximateStreamPosition())
        duration = status.mediaInformation?.streamDuration ?? 0
        playing = status.playerState == .playing
        if status.playerState == .idle && status.idleReason == .finished {
            if !finished { finished = true; save(duration, duration, true) }
        } else if status.playerState == .idle && status.idleReason == .error {
            error = "تعذّر تشغيل الرابط على الكاست. يجب أن يكون الجهاز على الشبكة الداعمة للمصدر."
        } else if active && duration > 0 { save(position, duration, false) }
    }
    func seek(_ seconds: Double) {
        let options = GCKMediaSeekOptions()
        options.interval = max(0, duration > 0 ? min(seconds, duration) : seconds)
        client?.seek(with: options).delegate = self
    }
    func toggle() { if playing { client?.pause().delegate = self } else { client?.play().delegate = self } }
    func selectSubtitle(_ id: Int) {
        pendingSubtitle = id
        subtitleRequest = client?.setActiveTrackIDs(id == 0 ? [] : [NSNumber(value: id)])
        subtitleRequest?.delegate = self
    }
    func disconnect() { manager.endSessionAndStopCasting(true) }
    func close() {
        manager.remove(self); timer?.invalidate(); timer = nil
        if active { poll(); client?.stop() }
        loadRequest?.delegate = nil; subtitleRequest?.delegate = nil
        active = false; loadRequest = nil; subtitleRequest = nil
        didLoad = {}; didDisconnect = { _, _ in }; save = { _, _, _ in }
    }
}

struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton {
        let button = GCKUICastButton(frame: .zero)
        button.tintColor = .white
        button.accessibilityLabel = "Google Cast"
        return button
    }
    func updateUIView(_ view: GCKUICastButton, context: Context) {}
}

struct CastControls: View {
    @ObservedObject var cast: CastSender
    var body: some View {
        NavigationStack {
            Form {
                Text(cast.media?.title ?? "Google Cast")
                Text("يجب أن يكون جهاز الكاست على الشبكة الداعمة للمصدر.")
                if cast.pending { ProgressView() }
                if let error = cast.error { Text(error).foregroundStyle(.red) }
                if cast.active {
                    if cast.duration > 0 {
                        Slider(value: Binding(get: { min(cast.position, cast.duration) }, set: { cast.seek($0) }), in: 0...cast.duration)
                    }
                    HStack {
                        Button("−10 ثوانٍ") { cast.seek(cast.position - 10) }
                        Button(cast.playing ? "إيقاف مؤقت" : "تشغيل") { cast.toggle() }
                        Button("+10 ثوانٍ") { cast.seek(cast.position + 10) }
                    }.buttonStyle(.borderless)
                }
                Section("الجودة") {
                    ForEach(cast.streams) { stream in
                        Button((stream.id == cast.quality ? "✓ " : "") + stream.quality) {
                            cast.open(stream, at: cast.active ? cast.position : cast.localPosition(), autoplay: !cast.active || cast.playing)
                        }.disabled(cast.pending)
                    }
                }
                Section("الترجمة (WebVTT)") {
                    Button("بدون ترجمة") { cast.selectSubtitle(0) }
                    ForEach(Array(cast.castTracks.enumerated()), id: \.element.id) { index, track in
                        Button((cast.subtitle == index + 1 ? "✓ " : "") + track.title) { cast.selectSubtitle(index + 1) }
                    }
                    if cast.castTracks.count < cast.tracks.count {
                        Text("بعض الترجمات تحتاج تحويلًا إلى WebVTT على خادم مصرح به قبل إرسالها.")
                    }
                }
                Button("إيقاف الكاست", role: .destructive) { cast.disconnect() }
            }
            .navigationTitle("Google Cast")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("تم") { cast.showControls = false } } }
        }.environment(\.layoutDirection, .rightToLeft)
    }
}
