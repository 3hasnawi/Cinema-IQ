import SwiftUI
import AVKit
import CinemaCore

/// Live TV uses the existing shared PlaybackController and AVKit's native remote controls.
struct TVIPTVPlayerView: View {
    let initial: IPTVChannel
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @StateObject private var controller = PlaybackController()
    @State private var current: IPTVChannel?
    @State private var message: String?
    @State private var retrying = false
    @State private var retryTask: Task<Void, Never>?
    @AppStorage(TVPlaybackPreference.qualityKey) private var preferredQuality = VideoQuality.automatic.rawValue
    @AppStorage(SubtitlePreference.languageKey) private var subtitleLanguage = "ar"
    private var channel: IPTVChannel { current ?? initial }
    private var failure: String? { (message ?? controller.error)?.replacingOccurrences(of: "على iOS", with: "على Apple TV") }
    private var status: String { controller.buffering ? "جارٍ التحميل…" : controller.playing ? "بث مباشر" : "متوقف مؤقتًا" }
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if failure == nil {
                TVIPTVSystemPlayer(player: controller.player, title: channel.name, status: status).ignoresSafeArea()
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("tv.iptv.player.native").accessibilityValue(status)
                if !controller.playing || controller.buffering {
                VStack {
                    HStack {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(channel.name).font(.title2.bold()).accessibilityIdentifier("tv.iptv.player.title")
                            Text(status).font(.callout).foregroundStyle(.secondary).accessibilityIdentifier("tv.iptv.player.status")
                        }
                        Spacer()
                    }
                    Spacer()
                }
                .padding(64).allowsHitTesting(false)
                }
                if controller.buffering || retrying { ProgressView().controlSize(.large).allowsHitTesting(false) }
            }
            if let failure {
                VStack(spacing: 28) {
                    Text(channel.name).font(.title2.bold())
                    Text(failure).multilineTextAlignment(.center).foregroundStyle(.secondary)
                    HStack(spacing: 32) {
                        Button(retrying ? "جارٍ الاتصال…" : "إعادة المحاولة", action: retry).disabled(retrying)
                        Button("إغلاق") { dismiss() }.accessibilityIdentifier("tv.iptv.player.close")
                    }
                }
                .padding(60).frame(maxWidth: 1200)
                .background(TVTheme.panel, in: RoundedRectangle(cornerRadius: 30)).focusSection()
            }
        }
        .onAppear { open(initial) }
        .onDisappear { retryTask?.cancel(); controller.close() }
        .onChange(of: phase) { _, value in if value != .active { controller.player.pause() } }
        .onExitCommand { dismiss() }
    }
    private func open(_ channel: IPTVChannel) {
        current = channel; message = nil
        var media = Media(id: channel.id, title: channel.name); media.overview = channel.group
        var url = channel.url
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--cinema-tv-ui-testing-iptv-player") {
            guard let fixture = Bundle.main.url(forResource: "UIPlayerFixture", withExtension: "mp4") else {
                message = "تعذّر العثور على فيديو الاختبار المحلي."; return
            }
            url = fixture
        }
#endif
        // A live channel never writes a movie watch-history/resume entry.
        controller.resetSubtitles(preferred: subtitleLanguage == "off" ? nil : subtitleLanguage)
        controller.open(media, url: url, resume: 0) { _, _, _ in }
        let quality = VideoQuality(rawValue: preferredQuality) ?? .automatic
        controller.player.currentItem?.preferredMaximumResolution = quality.height.map {
            CGSize(width: Double($0) * 16 / 9, height: Double($0))
        } ?? .zero
        controller.player.currentItem?.preferredPeakBitRate = 0
    }
    private func retry() {
        guard !retrying else { return }
        retrying = true; message = nil; controller.close()
        retryTask = Task { @MainActor in
            defer { retrying = false }
            do { let fresh = try await store.refreshed(channel); try Task.checkCancellation(); open(fresh) }
            catch { if !Task.isCancelled { message = store.safeMessage(error) } }
        }
    }
}

/// A live AVKit bridge keeps native embedded audio/subtitle options enabled; movie episode menus are irrelevant.
private struct TVIPTVSystemPlayer: UIViewControllerRepresentable {
    let player: AVPlayer
    let title: String
    let status: String
    final class Coordinator { var item: ObjectIdentifier? }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let view = AVPlayerViewController()
        view.player = player
        view.view.backgroundColor = .black
        view.view.semanticContentAttribute = .forceLeftToRight
        view.transportBarIncludesTitleView = false
        view.view.accessibilityIdentifier = "tv.iptv.player.native"
        return view
    }
    func updateUIViewController(_ view: AVPlayerViewController, context: Context) {
        if view.player !== player { view.player = player }
        view.view.accessibilityValue = status
        guard let item = player.currentItem, context.coordinator.item != ObjectIdentifier(item) else { return }
        context.coordinator.item = ObjectIdentifier(item)
        let name = AVMutableMetadataItem()
        name.identifier = .commonIdentifierTitle; name.value = title as NSString; name.extendedLanguageTag = "und"
        item.externalMetadata = [name]
    }
}
