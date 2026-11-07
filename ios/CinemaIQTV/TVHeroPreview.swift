import SwiftUI
import AVFoundation
import CinemaCore

/// The preview owns no viewing progress and never shares the main player's AVPlayer or audio-session lifecycle.
@MainActor final class TVHeroPreview: ObservableObject {
    enum Status { case idle, loading, unavailable, ready, failed }
    let player = AVPlayer()
    @Published private(set) var status: Status = .idle
    @Published private(set) var frameReady = false
    @Published private(set) var paused = false
    @Published private(set) var ended = false
    @Published private(set) var hasRendered = false
    private var observation: NSKeyValueObservation?
    private var endToken: NSObjectProtocol?
    private var failureToken: NSObjectProtocol?
    private var requestID = UUID()

    var visible: Bool { frameReady && status == .ready }
    var canControl: Bool { status == .ready && hasRendered }

    func load(_ media: Media, muted: Bool) async {
        stop()
        let request = requestID
        status = .loading
        do { try await Task.sleep(for: .seconds(2)) } catch { return }
        guard !Task.isCancelled, request == requestID else { return }
        let url: URL?
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-tv.testHeroPreview"), media.id == "ext:demo:sintel" {
            // Synthetic six-second fixture for deterministic lifecycle/focus acceptance tests, never a catalog trailer.
            url = Bundle.main.url(forResource: "HeroPreviewTest", withExtension: "mp4")
        } else {
            url = await SourceClient().trailer(media)
        }
        #else
        url = await SourceClient().trailer(media)
        #endif
        guard !Task.isCancelled, request == requestID else { return }
        guard let url else { status = .unavailable; return }
        let item = AVPlayerItem(url: url)
        player.isMuted = muted
        player.actionAtItemEnd = .pause
        // Limit background browsing bandwidth independently of full-screen playback quality.
        item.preferredMaximumResolution = CGSize(width: 1280, height: 720)
        player.replaceCurrentItem(with: item)
        observation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, request == self.requestID, self.player.currentItem === item else { return }
                if item.status == .failed { self.fail(request) }
                else if item.status == .readyToPlay { self.status = .ready }
            }
        }
        endToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, request == self.requestID else { return }
                // One preview per dwell. Leave artwork visible rather than looping in the background forever.
                self.player.pause(); self.frameReady = false; self.paused = true; self.ended = true
            }
        }
        failureToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.fail(request) }
        }
        player.play()
        do { try await Task.sleep(for: .seconds(15)) } catch { return }
        guard request == requestID, !Task.isCancelled, !hasRendered else { return }
        fail(request)
    }

    func didRender(_ item: AVPlayerItem?, ready: Bool) {
        guard item != nil, player.currentItem === item else { return }
        frameReady = ready
        if ready { hasRendered = true }
    }

    func toggle() {
        guard canControl else { return }
        if paused {
            if let item = player.currentItem, item.duration.seconds.isFinite,
               player.currentTime().seconds >= item.duration.seconds - 0.2 {
                player.seek(to: .zero)
            }
            frameReady = true; ended = false
            player.play()
        } else { player.pause() }
        paused.toggle()
    }

    func stop() {
        requestID = UUID()
        player.pause()
        observation = nil
        if let endToken { NotificationCenter.default.removeObserver(endToken) }; endToken = nil
        if let failureToken { NotificationCenter.default.removeObserver(failureToken) }; failureToken = nil
        player.replaceCurrentItem(with: nil)
        frameReady = false; paused = false; ended = false; hasRendered = false; status = .idle
    }

    private func fail(_ request: UUID) {
        guard request == requestID else { return }
        stop()
        status = .failed
    }
}

/// An AVPlayerLayer, not a second focusable transport UI over the hero's remote controls.
struct TVHeroVideoSurface: UIViewRepresentable {
    let player: AVPlayer
    let rendered: (AVPlayerItem?, Bool) -> Void

    final class Surface: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var videoLayer: AVPlayerLayer { layer as! AVPlayerLayer }
        override var canBecomeFocused: Bool { false }
    }
    final class Coordinator {
        var observation: NSKeyValueObservation?
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.isUserInteractionEnabled = false
        view.videoLayer.player = player
        view.videoLayer.videoGravity = .resizeAspectFill
        context.coordinator.observation = view.videoLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { layer, _ in
            let item = layer.player?.currentItem
            let ready = layer.isReadyForDisplay
            Task { @MainActor in rendered(item, ready) }
        }
        return view
    }
    func updateUIView(_ uiView: Surface, context: Context) {
        if uiView.videoLayer.player !== player { uiView.videoLayer.player = player }
    }
    static func dismantleUIView(_ uiView: Surface, coordinator: Coordinator) {
        coordinator.observation = nil
        uiView.videoLayer.player = nil
    }
}
