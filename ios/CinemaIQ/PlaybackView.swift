import SwiftUI
import AVKit
import CinemaCore

@MainActor final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    @Published var error: String?
    @Published var ended = false
    private var timer: Any?
    private var endToken: NSObjectProtocol?
    private var failureToken: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var save: ((Double, Double, Bool) -> Void)?
    func open(_ media: Media, url: URL, resume: Double, save: @escaping (Double, Double, Bool) -> Void) {
        close(); self.save = save; error = nil; ended = false
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
            timer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 600), queue: .main) { [weak self] _ in
                Task { @MainActor in self?.persist() }
            }
            if resume.isFinite && resume > 0 { player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
            player.play()
        } catch { self.error = error.localizedDescription }
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
    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            NativePlayer(player: controller.player).ignoresSafeArea()
            Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.largeTitle).foregroundStyle(.white).shadow(radius: 4).padding() }.accessibilityLabel("إغلاق المشغل")
            VStack { HStack { Spacer(); if !streams.isEmpty { Menu {
                ForEach(streams) { stream in Button(stream.quality) { open(current, stream: stream, resume: controller.player.currentTime().seconds) } }
            } label: { Label("الجودة", systemImage: "slider.horizontal.3").padding().background(.ultraThinMaterial, in: Capsule()) } } }; Spacer() }.padding()
            if loading { ProgressView("جلب رابط التشغيل…").padding().background(.black.opacity(0.7)).frame(maxWidth: .infinity, maxHeight: .infinity).allowsHitTesting(false) }
            if let error = resolutionError ?? controller.error {
                VStack(spacing: 20) { Text(error); Button("إغلاق") { dismiss() } }.padding().frame(maxWidth: .infinity, maxHeight: .infinity).background(.black.opacity(0.85))
            } else if controller.ended {
                VStack(spacing: 20) {
                    Text("انتهت المشاهدة")
                    if let next { Button("الحلقة التالية") { active = next }.buttonStyle(.borderedProminent) }
                    Button("إغلاق") { dismiss() }
                }.frame(maxWidth: .infinity, maxHeight: .infinity).background(.black.opacity(0.8))
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
