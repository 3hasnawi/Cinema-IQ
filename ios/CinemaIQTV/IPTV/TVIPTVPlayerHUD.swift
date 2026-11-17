import SwiftUI
import CinemaCore

enum TVIPTVStyle {
    static let focus = Color(red: 0.82, green: 0.20, blue: 0.42)
    static let panel = Color(red: 0.09, green: 0.10, blue: 0.16)
}
struct TVIPTVFlatButtonStyle: ButtonStyle {
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundStyle(.white)
            .background(focused ? TVIPTVStyle.focus : .clear, in: RoundedRectangle(cornerRadius: 14))
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.35)
    }
}
struct TVIPTVTrackButtonStyle: ButtonStyle {
    @Environment(\.isFocused) private var focused
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundStyle(.white)
            .background(.white.opacity(focused ? 0.20 : configuration.isPressed ? 0.10 : 0), in: RoundedRectangle(cornerRadius: 20))
    }
}
struct TVIPTVLogo: View {
    let channel: IPTVChannel
    var body: some View {
        Group {
            if let url = channel.logo {
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFit() }
                    else { Image(systemName: "tv").resizable().scaledToFit().padding(20).foregroundStyle(.white.opacity(0.7)) }
                }
            } else { Image(systemName: "tv").resizable().scaledToFit().padding(20).foregroundStyle(.white.opacity(0.7)) }
        }.accessibilityHidden(true)
    }
}

/// Same physical order, typography scale and compact HUD actions as Android TV 4.39.
struct TVIPTVPlayerHUD: View {
    @ObservedObject var session: IPTVPlayerSession
    @EnvironmentObject private var store: IPTVStore
    let programmes: IPTVNowNext
    let focus: FocusState<String?>.Binding
    let channels: () -> Void
    let tracks: (String) -> Void
    let iconBounds: (String, CGRect) -> Void
    let interact: () -> Void
    var body: some View {
        VStack(spacing: 24) {
            HStack(spacing: 24) {
                TVIPTVLogo(channel: session.channel).frame(width: 168, height: 168)
                VStack(alignment: .leading, spacing: 6) {
                    Text(session.channel.name).font(.system(size: 40, weight: .bold)).lineLimit(1)
                        .accessibilityIdentifier("tv.iptv.player.title")
                    Text("\(session.numbers[session.channel.id] ?? 0) · \(session.status) · \(session.channel.group)")
                        .font(.system(size: 24)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                }.frame(maxWidth: 780, alignment: .leading).layoutPriority(1)
                VStack(spacing: 32) {
                    Image(systemName: "chevron.up"); Image(systemName: "chevron.down")
                }.font(.system(size: 20, weight: .semibold)).frame(width: 32).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 10) {
                    if programmes.current == nil && programmes.next == nil {
                        Text("لا تتوفر معلومات").foregroundStyle(.white.opacity(0.7))
                    } else {
                        programme("الآن", programmes.current)
                        programme("التالي", programmes.next)
                    }
                }.font(.system(size: 26)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 24).accessibilityIdentifier("tv.iptv.player.programmes")
            }.allowsHitTesting(false)
            Rectangle().fill(.white.opacity(0.22)).frame(height: 1)
            HStack(spacing: 18) {
                button("القنوات", id: "channels", symbol: "tv", run: channels)
                button("المفضلة", id: "favorite", symbol: store.channelCollection.favorites.contains(session.channel.id) ? "heart.fill" : "heart") {
                    store.toggleFavorite(session.channel)
                }.accessibilityValue(store.channelCollection.favorites.contains(session.channel.id) ? "مضافة إلى المفضلة" : "غير مضافة")
                button("الصوت", id: "audio", symbol: "headphones") { tracks("audio") }
                button(session.videoHeight > 0 ? "\(session.videoHeight)p" : "الجودة", id: "quality", symbol: "chart.bar.xaxis") { tracks("quality") }
                button("الترجمة", id: "subtitles", symbol: "captions.bubble") { tracks("subtitles") }
                button("السابقة", id: "last", symbol: "arrow.left") { session.previous() }
                    .disabled(session.previousID == nil)
                Spacer(minLength: 0)
            }.focusSection()
                .onMoveCommand { direction in
                    if direction == .up || direction == .down {
                        session.surf(direction == .up ? -1 : 1); interact(); focus.wrappedValue = "surface"
                    }
                }
        }
        .padding(.horizontal, 60).padding(.top, 56).padding(.bottom, 36)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.63), .black.opacity(0.91)], startPoint: .top, endPoint: .bottom))
        .environment(\.layoutDirection, .rightToLeft)
    }
    private func programme(_ prefix: String, _ value: IPTVProgramme?) -> some View {
        let formatter = DateFormatter(); formatter.dateFormat = "HH:mm"
        let text = value.map { "\(prefix)  \(formatter.string(from: $0.start))–\(formatter.string(from: $0.end))  \($0.title)" } ?? "\(prefix): لا تتوفر معلومات"
        return Text(text).foregroundStyle(.white.opacity(prefix == "الآن" ? 1 : 0.7))
    }
    private func button(_ title: String, id: String, symbol: String, run: @escaping () -> Void) -> some View {
        Button { interact(); run() } label: {
            HStack(spacing: 16) {
                Image(systemName: symbol).font(.system(size: 39)).frame(width: 46, height: 46)
                    .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .named("tv.iptv.player.space")) }) { iconBounds(id, $0) }
                Text(title).font(.system(size: 26, weight: .bold)).lineLimit(1)
            }.padding(.horizontal, 26).padding(.vertical, 16).frame(minHeight: 88)
        }.buttonStyle(TVIPTVFlatButtonStyle()).focusEffectDisabled()
            .focused(focus, equals: "control." + id).accessibilityLabel(id == "quality" ? "الجودة: " + title : title)
            .accessibilityIdentifier("tv.iptv.player." + id)
    }
}
