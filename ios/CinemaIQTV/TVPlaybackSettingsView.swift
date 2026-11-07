import SwiftUI
import CinemaCore

struct TVPlaybackSettingsView: View {
    @EnvironmentObject private var router: TVRouter
    @AppStorage(PlaybackQualityPreference.key) private var quality = PlaybackQualityPreference.automatic
    @AppStorage("tv.hero.trailers.enabled.v1") private var trailersEnabled = true
    @AppStorage("tv.hero.trailers.muted.v1") private var trailersMuted = true
    @FocusState private var qualityFocused: Bool
    @State private var restoreQualityFocus = false

    var body: some View {
        List {
            Button {
                restoreQualityFocus = true
                router.popup = TVPopup(title: "جودة التشغيل المفضلة",
                    options: PlaybackQualityPreference.options.map { (id: $0.value, title: $0.title) },
                    selected: quality, choose: { quality = $0 })
            } label: {
                HStack {
                    Text("جودة التشغيل المفضلة")
                    Spacer()
                    Text(PlaybackQualityPreference.title(quality)).foregroundStyle(.secondary)
                }
            }
            .focused($qualityFocused)
            .accessibilityIdentifier("tv.settings.playback.quality")
            Text("نتذكّر أيضاً الجودة التي تختارها من المشغّل. إن لم تتوفر، نستخدم أقرب جودة أقل إن أمكن دون تغيير اختيارك.")
                .font(.callout).foregroundStyle(.secondary)
            Toggle("الإعلانات الدعائية في الرئيسية", isOn: $trailersEnabled)
                .accessibilityIdentifier("tv.settings.playback.trailers")
            Toggle("كتم صوت الإعلانات", isOn: $trailersMuted)
                .accessibilityIdentifier("tv.settings.playback.muted")
            Text("تُشغّل فقط الإعلانات التي يوفّرها المصدر كرابط فيديو مباشر. عند غيابها نعرض صورة العمل. يتوقف التشغيل التلقائي عند تفعيل تقليل الحركة.")
                .font(.callout).foregroundStyle(.secondary)
        }
        .navigationTitle("التشغيل")
        .task(id: router.popup?.id) {
            guard router.popup == nil, restoreQualityFocus else { return }
            do { try await Task.sleep(for: .milliseconds(300)) } catch { return }
            guard router.popup == nil else { return }
            qualityFocused = true
            restoreQualityFocus = false
        }
    }
}
