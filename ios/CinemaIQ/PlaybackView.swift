import SwiftUI
import AVKit
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

@MainActor final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    @Published var error: String?
    @Published var ended = false
    /// Seconds left in the current item, refreshed every second; nil while the length is unknown.
    @Published var remaining: Double?
    private var ticks = 0
    private var timer: Any?
    private var endToken: NSObjectProtocol?
    private var failureToken: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var save: ((Double, Double, Bool) -> Void)?
    func open(_ media: Media, url: URL, resume: Double, save: @escaping (Double, Double, Bool) -> Void) {
        close(); self.save = save; error = nil; ended = false; remaining = nil; ticks = 0
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
            let item = AVPlayerItem(url: url)
            player.replaceCurrentItem(with: item)
            statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                if item.status == .failed { Task { @MainActor in self?.error = "تعذّر تشغيل هذه النسخة. قد تكون صيغة الفيديو أو الصوت غير مدعومة على iOS." } }
            }
            endToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in guard let self, self.player.currentItem === item else { return }; self.ended = true; self.persist(completed: true); self.player.pause() }
            }
            failureToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.error = "انقطع تشغيل الفيديو. تحقق من الاتصال وأعد فتح العمل." }
            }
            timer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1, preferredTimescale: 600), queue: .main) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
            if resume.isFinite && resume > 0 { player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
            player.play()
        } catch { self.error = error.localizedDescription }
    }
    private func tick() {
        guard let item = player.currentItem else { remaining = nil; return }
        let position = player.currentTime().seconds, duration = item.duration.seconds
        remaining = position.isFinite && duration.isFinite && duration > 0 ? max(0, duration - position) : nil
        ticks += 1
        if ticks % 5 == 0 { persist() }
    }
    func persist(completed: Bool = false) {
        guard let item = player.currentItem else { return }
        let position = player.currentTime().seconds, duration = item.duration.seconds
        guard position.isFinite, duration.isFinite, duration > 0 else { return }
        save?(position, duration, completed || ended)
    }
    func close() {
        persist(); player.pause()
        if let timer { player.removeTimeObserver(timer) }; timer = nil
        if let endToken { NotificationCenter.default.removeObserver(endToken) }; endToken = nil
        if let failureToken { NotificationCenter.default.removeObserver(failureToken) }; failureToken = nil
        statusObservation = nil; player.replaceCurrentItem(with: nil); save = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
struct NativePlayer: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController(); controller.player = player
        controller.showsPlaybackControls = true
        controller.allowsPictureInPicturePlayback = false
        return controller
    }
    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: ()) { controller.player = nil }
}
struct PlaybackView: View {
    let selection: PlaybackSelection
    @State private var active: Media?
    @StateObject private var controller = PlaybackController()
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @State private var previousIdleSetting = false
    @State private var streams: [StreamChoice] = []
    @State private var loading = false
    @State private var resolutionError: String?
    private var current: Media { active ?? selection.media }
    private var next: Media? {
        guard let index = selection.queue.firstIndex(where: { $0.id == current.id }), selection.queue.indices.contains(index + 1) else { return nil }
        return selection.queue[index + 1]
    }
    private func open(_ media: Media, stream: StreamChoice, resume: Double) {
        controller.open(media, url: stream.url, resume: resume) { seconds, duration, completed in
            library.save(media, seconds: seconds, duration: duration, completed: completed)
        }
        if phase != .active { controller.player.pause() }
    }
    /// Without credits times from the source, the next episode is offered for the last 15 seconds (as on Android).
    private var offerNext: Bool {
        guard next != nil, !controller.ended, let left = controller.remaining else { return false }
        return left <= 15
    }
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            NativePlayer(player: controller.player).ignoresSafeArea()
            VStack {
                HStack(spacing: 10) {
                    Button { dismiss() } label: { Image(systemName: "xmark").font(.headline) }
                        .secondaryAction()
                        .buttonBorderShape(.circle)
                        .accessibilityLabel("إغلاق المشغل")
                    VStack(alignment: .leading, spacing: 0) {
                        Text(current.title).font(.subheadline.bold()).lineLimit(1)
                        if current.episode > 0 {
                            Text("الموسم \(current.season) • الحلقة \(current.episode)").font(.caption).foregroundStyle(.white.opacity(0.7))
                        }
                    }
                    Spacer()
                    if streams.count > 1 {
                        Menu {
                            ForEach(streams) { stream in
                                Button(stream.quality) { open(current, stream: stream, resume: controller.player.currentTime().seconds) }
                            }
                        } label: {
                            Label("الجودة", systemImage: "slider.horizontal.3").font(.subheadline.weight(.semibold))
                        }
                        .menuStyle(.button)
                        .secondaryAction()
                    }
                }
                .padding(.horizontal)
                .padding(.top, 8)
                Spacer()
                if offerNext, let next {
                    HStack {
                        Spacer()
                        Button { active = next } label: {
                            Label("الحلقة التالية", systemImage: "forward.end.fill").font(.headline)
                        }
                        .primaryAction()
                        .controlSize(.large)
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 90)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
            }
            .animation(.smooth, value: offerNext)
            if loading {
                ProgressView("جلب رابط التشغيل…")
                    .padding()
                    .glass(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .allowsHitTesting(false)
            }
            if let error = resolutionError ?? controller.error {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(Theme.accent)
                    Text(error).multilineTextAlignment(.center)
                    Button("إغلاق") { dismiss() }.primaryAction()
                }
                .padding(24)
                .glass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .padding(32)
            } else if controller.ended {
                VStack(spacing: 16) {
                    Text("انتهت المشاهدة").font(.title3.bold())
                    if let next {
                        Button { active = next } label: {
                            Label("الحلقة \(next.episode)", systemImage: "play.fill").font(.headline)
                        }
                        .primaryAction()
                    }
                    Button("إغلاق") { dismiss() }.secondaryAction()
                }
                .controlSize(.large)
                .padding(24)
                .glass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            }
        }
        .task(id: current.id) {
            let media = current
            controller.close(); streams = []; loading = true; resolutionError = nil
            do {
                let result = try await SourceClient().streams(media)
                try Task.checkCancellation()
                streams = result
                let prior = library.progress[media.id]
                open(media, stream: result[0], resume: prior?.completed == false ? prior?.seconds ?? 0 : 0)
            } catch { if !Task.isCancelled { resolutionError = error.localizedDescription } }
            if !Task.isCancelled { loading = false }
        }
        .onAppear { previousIdleSetting = UIApplication.shared.isIdleTimerDisabled; UIApplication.shared.isIdleTimerDisabled = true }
        .onChange(of: phase) { _, value in
            if value != .active { controller.persist(); controller.player.pause(); UIApplication.shared.isIdleTimerDisabled = previousIdleSetting }
            else { UIApplication.shared.isIdleTimerDisabled = true }
        }
        .onDisappear { controller.close(); UIApplication.shared.isIdleTimerDisabled = previousIdleSetting }
    }
}
