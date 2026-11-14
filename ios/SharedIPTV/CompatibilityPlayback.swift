import SwiftUI
import CinemaCore
#if os(tvOS)
@preconcurrency import TVVLCKit
#else
@preconcurrency import MobileVLCKit
#endif

/// Dynamic, LGPL VLCKit renderer. Transport, progress and provider authorization remain in Cinema's controller.
@MainActor final class CompatibilityPlayback: NSObject, VLCMediaPlayerDelegate {
    struct Snapshot {
        var position: Double = 0, duration: Double = 0
        var playing = false, buffering = false, ended = false, failed = false
        var audio: [MediaOption] = [], subtitles: [MediaOption] = []
        var audioID: String?, subtitleID: String?
    }
    private var engine: VLCMediaPlayer?
    private weak var surface: UIView?
    private var generation = UUID()
    private var task: Task<Void, Never>?
    private var update: ((Snapshot) -> Void)?
    private var wantsPlay = true
    private var desiredRate: Float = 1
    private var desiredSubtitle: String?
    private var subtitleWasChosen = false
    private static let shutdownQueue = DispatchQueue(label: "cinema.playback.shutdown", qos: .utility)
    // VLC's stop can block while a stream is closing. No UI or delegate accesses this retired instance.
    private final class RetiredPlayer: @unchecked Sendable {
        let value: VLCMediaPlayer
        init(_ value: VLCMediaPlayer) { self.value = value }
    }

    func open(url: URL, resume: Double, rate: Float, playing: Bool, audioID: String?, subtitleID: String?, maximumHeight: Int?, highest: Bool,
              update: @escaping (Snapshot) -> Void) {
        close(); let token = UUID(); generation = token
        self.update = update; wantsPlay = playing; desiredRate = rate
        diagnostic("open queued")
        subtitleWasChosen = subtitleID?.hasPrefix("vlcs:") == true; desiredSubtitle = subtitleWasChosen ? subtitleID : nil
        task = Task { @MainActor [weak self] in
            // Serialize retirement before starting the next channel; never run two live decoders together.
            await withCheckedContinuation { continuation in
                Self.shutdownQueue.async { continuation.resume() }
            }
            guard let self, !Task.isCancelled, generation == token else { return }
            diagnostic("shutdown queue drained")
            let value = VLCMediaPlayer(options: ["--quiet", "--no-video-title-show"])
            let media = VLCMedia(url: url)
            var options: [String: Any] = ["network-caching": 1000]
            if resume.isFinite && resume > 0 { options["start-time"] = resume }
            if !wantsPlay { options["start-paused"] = true }
            if let maximumHeight { options["adaptive-maxheight"] = maximumHeight }
            if highest { options["adaptive-logic"] = "highest" }
            media.addOptions(options)
            engine = value; value.delegate = self; value.drawable = surface; value.media = media
            diagnostic("media attached; drawable=\(surface != nil)")
            value.play(); value.rate = desiredRate
            diagnostic("play requested")
            var appliedTracks = false
            while !Task.isCancelled, generation == token {
                if !appliedTracks, value.isPlaying || value.state == .paused {
                    appliedTracks = true
                    if let audioID, audioID.hasPrefix("vlca:") { selectAudio(audioID) }
                    if subtitleWasChosen { selectSubtitle(desiredSubtitle) }
                }
                publish(value)
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            }
        }
    }
    func attach(_ view: UIView) {
        guard surface !== view else { return }; surface = view; engine?.drawable = view
    }
    func detach(_ view: UIView) {
        guard surface === view else { return }; engine?.drawable = nil; surface = nil
    }
    func play() { wantsPlay = true; engine?.play() }
    func pause() { wantsPlay = false; engine?.pause() }
    func seek(_ seconds: Double) {
        guard seconds.isFinite, let engine, engine.isSeekable else { return }
        engine.time = VLCTime(int: Int32(min(Double(Int32.max), max(0, seconds) * 1000)))
    }
    func setRate(_ value: Float) { desiredRate = value; engine?.rate = value }
    func selectAudio(_ id: String) {
        if let index = Int32(id.replacingOccurrences(of: "vlca:", with: "")) { engine?.currentAudioTrackIndex = index }
    }
    func selectSubtitle(_ id: String?) {
        subtitleWasChosen = true; desiredSubtitle = id
        engine?.currentVideoSubTitleIndex = id.flatMap { Int32($0.replacingOccurrences(of: "vlcs:", with: "")) } ?? -1
    }
    private func tracks(_ names: [Any]?, _ indexes: [Any]?, prefix: String) -> [MediaOption] {
        zip(names ?? [], indexes ?? []).compactMap { name, index in
            guard let index = index as? NSNumber, index.intValue >= 0 else { return nil }
            return MediaOption(id: prefix + index.stringValue, title: String(describing: name))
        }
    }
    private func publish(_ value: VLCMediaPlayer, stateOverride: VLCMediaPlayerState? = nil) {
        guard value === engine else { return }
        let state = stateOverride ?? value.state
        let running = value.isPlaying
        diagnostic("state=\(state.rawValue) time=\(value.time.intValue) audio=\(value.audioTrackIndexes.count) drawable=\(surface?.bounds.size ?? .zero) window=\(surface?.window != nil)")
        // A paused request issued during opening must survive the decoder's asynchronous ready event.
        if running && !wantsPlay { value.pause() }
        var result = Snapshot()
        result.position = max(0, Double(value.time.intValue) / 1000)
        result.duration = max(0, Double(value.media?.length.intValue ?? 0) / 1000)
        // VLCKit 3 caches its last event, including Buffering(100%) and ESAdded, even while decoding.
        // Query actual transport state so a completed buffer event cannot leave an endless spinner.
        result.buffering = wantsPlay && !running && (state == .opening || state == .buffering)
        result.playing = wantsPlay && (running || result.buffering)
        result.ended = state == .ended; result.failed = state == .error
        result.audio = tracks(value.audioTrackNames, value.audioTrackIndexes, prefix: "vlca:")
        result.subtitles = tracks(value.videoSubTitlesNames, value.videoSubTitlesIndexes, prefix: "vlcs:")
        if value.currentAudioTrackIndex >= 0 { result.audioID = "vlca:\(value.currentAudioTrackIndex)" }
        if !subtitleWasChosen && value.currentVideoSubTitleIndex != -1 { value.currentVideoSubTitleIndex = -1 }
        result.subtitleID = desiredSubtitle
        update?(result)
    }
    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        guard let value = aNotification.object as? VLCMediaPlayer else { return }
        let identity = ObjectIdentifier(value), state = value.state.rawValue
        Task { @MainActor [weak self] in
            guard let self, let engine, ObjectIdentifier(engine) == identity else { return }; publish(engine, stateOverride: VLCMediaPlayerState(rawValue: state))
        }
    }
    private func diagnostic(_ text: String) {
#if DEBUG
        guard ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-compatibility") else { return }
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let file = root.appendingPathComponent("compatibility-debug.txt")
        let existing = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        if existing.utf8.count < 100_000 { try? (existing + text + "\n").write(to: file, atomically: true, encoding: .utf8) }
#endif
    }
    func close() {
        diagnostic("close")
        generation = UUID(); task?.cancel(); task = nil; update = nil
        guard let old = engine else { return }; engine = nil
        old.delegate = nil; old.pause(); old.drawable = nil
        let retired = RetiredPlayer(old)
        Self.shutdownQueue.async { retired.value.stop() }
    }
}

/// Detaching an old renderer must not clear a newer full-screen/guide renderer of the same decoder.
struct CompatibilityVideoSurface: UIViewRepresentable {
    let playback: CompatibilityPlayback
    final class Coordinator { let playback: CompatibilityPlayback; init(_ value: CompatibilityPlayback) { playback = value } }
    func makeCoordinator() -> Coordinator { Coordinator(playback) }
    func makeUIView(context: Context) -> UIView {
        let view = UIView(); view.backgroundColor = .black; view.isUserInteractionEnabled = false
        playback.attach(view); return view
    }
    func updateUIView(_ view: UIView, context: Context) { playback.attach(view) }
    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) { coordinator.playback.detach(view) }
}

struct PlaybackVideoSurface: View {
    @ObservedObject var controller: PlaybackController
    var body: some View {
        if controller.usesCompatibility { CompatibilityVideoSurface(playback: controller.compatibility) }
        else { IPTVVideoSurface(player: controller.player) }
    }
}
