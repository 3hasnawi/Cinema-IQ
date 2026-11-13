import SwiftUI
import AVKit
import CinemaCore

/// Live TV uses the existing PlaybackController/AVPlayer. Live streams do not create movie resume entries.
struct IPTVPlaybackView: View {
    let initial: IPTVChannel
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @StateObject private var controller = PlaybackController()
    @State private var current: IPTVChannel?
    @State private var retrying = false
    @State private var message: String?
    @State private var oldIdleSetting = false
    @State private var retryTask: Task<Void, Never>?
    private var channel: IPTVChannel { current ?? initial }
    private var status: String { controller.buffering ? "جارٍ التحميل…" : controller.playing ? "بث مباشر" : "متوقف مؤقتًا" }
    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            VideoPlayer(player: controller.player).ignoresSafeArea()
                .environment(\.layoutDirection, .leftToRight)
            HStack(spacing: 12) {
                Button { dismiss() } label: { Image(systemName: "xmark").font(.headline).padding(12) }
                    .buttonStyle(.plain).background(.ultraThinMaterial, in: Circle())
                    .accessibilityLabel("إغلاق المشغل").accessibilityIdentifier("ios.iptv.player.close")
                VStack(alignment: .leading, spacing: 3) {
                    Text(channel.name).font(.headline).lineLimit(1).accessibilityIdentifier("ios.iptv.player.title")
                    Text(status).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("ios.iptv.player.status")
                }
                Spacer(minLength: 8)
                RoutePicker().frame(width: 40, height: 40)
            }
            .padding(16).background(.black.opacity(0.25))
            if let problem = message ?? controller.error {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                    Text(problem).multilineTextAlignment(.center)
                    HStack {
                        Button("إعادة المحاولة", action: retry).disabled(retrying).primaryAction()
                            .accessibilityIdentifier("ios.iptv.player.retry")
                        Button("إغلاق") { dismiss() }.secondaryAction()
                    }
                }
                .padding(24).frame(maxWidth: 500)
                .glass(in: RoundedRectangle(cornerRadius: 24))
                .frame(maxWidth: .infinity, maxHeight: .infinity).padding(24)
            } else if retrying || controller.buffering {
                ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity).allowsHitTesting(false)
            }
        }
        .onAppear {
            oldIdleSetting = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
            open(initial)
        }
        .onDisappear {
            retryTask?.cancel(); controller.close()
            UIApplication.shared.isIdleTimerDisabled = oldIdleSetting
        }
        .onChange(of: phase) { _, value in
            if value != .active { controller.player.pause(); UIApplication.shared.isIdleTimerDisabled = oldIdleSetting }
            else { UIApplication.shared.isIdleTimerDisabled = true }
        }
        .persistentSystemOverlays(.hidden)
    }
    private func open(_ channel: IPTVChannel) {
        current = channel; message = nil
        var media = Media(id: channel.id, title: channel.name)
        media.overview = channel.group
#if DEBUG
        guard let url = UITestSupport.playbackURL(or: channel.url) else {
            message = "تعذّر العثور على فيديو الاختبار المحلي."; return
        }
#else
        let url = channel.url
#endif
        controller.open(media, url: url, resume: 0) { _, _, _ in }
    }
    private func retry() {
        guard !retrying else { return }
        retrying = true; message = nil; controller.close()
        retryTask = Task { @MainActor in
            defer { retrying = false }
            do {
                let fresh = try await store.refreshed(channel)
                try Task.checkCancellation(); open(fresh)
            } catch { if !Task.isCancelled { message = store.safeMessage(error) } }
        }
    }
}
