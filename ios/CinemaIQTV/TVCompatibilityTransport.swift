import SwiftUI

/// Movie-only controls when AVKit cannot decode the asset. Match the original movie layout, not live TV.
struct TVCompatibilityTransport<Options: View>: View {
    @ObservedObject var controller: PlaybackController
    let close: () -> Void
    var actionTitle: String? = nil
    var contextualAction: () -> Void = {}
    @ViewBuilder let options: (FocusState<String?>.Binding) -> Options
    @Namespace private var focusScope
    @Environment(\.resetFocus) private var resetFocus
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var controls = true
    @State private var interaction = 0
    @FocusState private var focus: String?

    private func reveal(_ target: String) { controls = true; interaction += 1; focus = target }

    var body: some View {
        ZStack(alignment: .bottom) {
            PlaybackVideoSurface(controller: controller).ignoresSafeArea().allowsHitTesting(false)
            Button { controller.togglePlay(); reveal("timeline") } label: { Color.clear.contentShape(Rectangle()) }
                .buttonStyle(TVArtworkButtonStyle()).focusEffectDisabled().focused($focus, equals: "surface")
                .prefersDefaultFocus(true, in: focusScope)
                .accessibilityLabel("عرض أدوات التشغيل")
                .accessibilityValue(controller.buffering ? "جارٍ التحميل" : controller.playing ? "تشغيل" : "متوقف مؤقتًا")
                .accessibilityIdentifier("tv.compatibility.surface")
                .onMoveCommand { direction in
                    switch direction {
                    case .left: controller.skip(-10); controls = true; interaction += 1
                    case .right: controller.skip(10); controls = true; interaction += 1
                    case .up: reveal("quality")
                    case .down: reveal("timeline")
                    default: break
                    }
                }
            if controls || !controller.playing {
                VStack(spacing: 18) {
                    HStack(spacing: 22) {
                        Spacer()
                        if let actionTitle {
                            Button(actionTitle, action: contextualAction).focused($focus, equals: "action")
                                .buttonStyle(.bordered).accessibilityIdentifier("tv.movie.compatibility.action")
                        }
                        options($focus)
                    }
                    .buttonStyle(TVCircleButtonStyle()).focusSection()
                    Button { controller.togglePlay(); interaction += 1 } label: {
                        GeometryReader { geometry in
                            let fraction = controller.duration > 0 ? min(1, max(0, controller.position / controller.duration)) : 0
                            ZStack(alignment: .leading) {
                                Capsule().fill(.white.opacity(0.25))
                                Capsule().fill(.white).frame(width: geometry.size.width * fraction)
                                Circle().fill(.white).frame(width: focus == "timeline" ? 16 : 10, height: focus == "timeline" ? 16 : 10)
                                    .offset(x: max(0, geometry.size.width * fraction - 5))
                            }.frame(height: focus == "timeline" ? 8 : 5).frame(maxHeight: .infinity)
                        }.frame(height: 30).contentShape(Rectangle())
                    }
                    .buttonStyle(TVArtworkButtonStyle()).focused($focus, equals: "timeline")
                    .accessibilityLabel("شريط التقدم").accessibilityValue(clock(controller.position))
                    .accessibilityIdentifier("tv.movie.compatibility.timeline")
                    .onMoveCommand { direction in
                        switch direction {
                        case .left: controller.skip(-10); interaction += 1
                        case .right: controller.skip(10); interaction += 1
                        case .up: reveal("quality")
                        case .down: reveal("play")
                        default: break
                        }
                    }
                    HStack(spacing: 14) {
                        Text(clock(controller.position))
                        Button { controller.togglePlay(); interaction += 1 } label: {
                            Image(systemName: controller.playing ? "pause.fill" : "play.fill")
                                .font(.system(size: 20)).frame(width: 40, height: 40)
                        }
                        .buttonStyle(TVCircleButtonStyle()).focused($focus, equals: "play")
                        .accessibilityLabel(controller.playing ? "إيقاف مؤقت" : "تشغيل")
                        .accessibilityIdentifier("tv.compatibility.play")
                        Spacer()
                        Text("−" + clock(max(0, controller.duration - controller.position)))
                    }.font(.system(size: 24, weight: .medium)).monospacedDigit()
                }
                .environment(\.layoutDirection, .leftToRight)
                .padding(.horizontal, 80).padding(.bottom, 80)
                .transition(.opacity)
            }
        }
        .ignoresSafeArea()
        .focusScope(focusScope)
        .defaultFocus($focus, "surface")
        .task {
            await Task.yield()
            guard !Task.isCancelled else { return }
            focus = "surface"; resetFocus(in: focusScope)
        }
        .onPlayPauseCommand { controller.togglePlay(); controls = true; interaction += 1 }
        .onExitCommand { if controls && focus != "surface" { controls = false; focus = "surface" } else { close() } }
        .onChange(of: actionTitle) { if actionTitle == nil && focus == "action" { focus = "surface" } }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: controls)
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
