import SwiftUI

/// Cinema's controls for a decoder AVKit cannot drive. The native engine retains its original AVKit transport.
struct TVCompatibilityTransport<Options: View>: View {
    @ObservedObject var controller: PlaybackController
    let close: () -> Void
    @ViewBuilder let options: () -> Options
    @Namespace private var focusScope
    @Environment(\.resetFocus) private var resetFocus
    @State private var controls = true
    @State private var interaction = 0
    @FocusState private var focus: String?
    var body: some View {
        ZStack(alignment: .bottom) {
            PlaybackVideoSurface(controller: controller).ignoresSafeArea().allowsHitTesting(false)
            Button { controls = true; focus = "play"; interaction += 1 } label: { Color.clear.contentShape(Rectangle()) }
                .buttonStyle(TVArtworkButtonStyle()).focused($focus, equals: "surface")
                .prefersDefaultFocus(true, in: focusScope)
                .accessibilityLabel("عرض أدوات التشغيل").accessibilityValue(controller.buffering ? "جارٍ التحميل" : controller.playing ? "تشغيل" : "متوقف مؤقتًا").accessibilityIdentifier("tv.compatibility.surface")
                .onMoveCommand { direction in
                    if direction == .left { controller.skip(-10) }
                    else if direction == .right { controller.skip(10) }
                    else { controls = true; focus = "play" }
                    interaction += 1
                }
            if controls || !controller.playing {
                VStack(spacing: 24) {
                    ProgressView(value: controller.position, total: max(1, controller.duration)).tint(.white)
                        .environment(\.layoutDirection, .leftToRight)
                    HStack {
                        Text(clock(controller.position)); Spacer(); Text(clock(controller.duration))
                    }.font(.system(size: 22)).monospacedDigit().environment(\.layoutDirection, .leftToRight)
                    HStack(spacing: 24) {
                        Button { controller.skip(-10) } label: { Image(systemName: "gobackward.10") }.accessibilityLabel("رجوع 10 ثوانٍ")
                        Button { controller.togglePlay(); interaction += 1 } label: { Image(systemName: controller.playing ? "pause.fill" : "play.fill") }
                            .focused($focus, equals: "play").accessibilityLabel(controller.playing ? "إيقاف مؤقت" : "تشغيل")
                            .accessibilityIdentifier("tv.compatibility.play")
                        Button { controller.skip(10) } label: { Image(systemName: "goforward.10") }.accessibilityLabel("تقديم 10 ثوانٍ")
                        options()
                        Button("إخفاء") { controls = false; focus = "surface" }
                    }.buttonStyle(.bordered).buttonBorderShape(.capsule).focusSection()
                        .environment(\.layoutDirection, .leftToRight)
                }
                .padding(30).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
                .padding(.horizontal, 80).padding(.bottom, 42)
            }
        }
        .focusScope(focusScope)
        .defaultFocus($focus, "surface")
        .task { await Task.yield(); focus = "surface"; resetFocus(in: focusScope) }
        .onPlayPauseCommand { controller.togglePlay(); controls = true; interaction += 1 }
        .onExitCommand { if controls && focus != "surface" { controls = false; focus = "surface" } else { close() } }
        .task(id: "\(controller.playing)|\(focus ?? "")|\(interaction)") {
            guard controller.playing, focus == "surface" else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }; controls = false
        }
    }
    private func clock(_ seconds: Double) -> String {
        let value = seconds.isFinite ? max(0, Int(seconds)) : 0
        return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60) : String(format: "%d:%02d", value / 60, value % 60)
    }
}
