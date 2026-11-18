import SwiftUI
import CinemaCore

/// A real SwiftUI field keeps the keyboard accessory available inside the guide's sheet.
struct IPTVSearchInput: View {
    @Binding var text: String
    let prompt: String
    let focused: FocusState<Bool>.Binding
    let identifier: String
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
            TextField(prompt, text: $text)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .submitLabel(.search).focused(focused).onSubmit { focused.wrappedValue = false }
                .frame(minHeight: 44).accessibilityIdentifier(identifier)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary).frame(width: 44, height: 44) }
                    .buttonStyle(.plain).accessibilityLabel("مسح البحث").accessibilityIdentifier(identifier + ".clear")
            }
        }.padding(.horizontal, 12).background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Subscription artwork keeps its own shape rather than being placed in an artificial tile.
struct IPTVChannelLogo: View {
    let channel: IPTVChannel
    var body: some View {
        Group {
            if let url = channel.logo {
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFit() }
                    else { placeholder }
                }
            } else { placeholder }
        }.accessibilityHidden(true)
    }
    private var placeholder: some View {
        Image(systemName: PhoneTelevisionSymbol.name).resizable().scaledToFit()
            .padding(10).foregroundStyle(.secondary)
    }
}

struct IPTVProgrammeSummary: View {
    let programmes: IPTVNowNext
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            if programmes.current == nil && programmes.next == nil {
                Text("لا تتوفر معلومات").foregroundStyle(.secondary)
            } else {
                programme("الآن", programmes.current)
                programme("التالي", programmes.next)
            }
        }.font(.caption2).lineLimit(1)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("ios.iptv.player.programmes")
    }
    private func programme(_ prefix: String, _ item: IPTVProgramme?) -> some View {
        let format = DateFormatter(); format.dateFormat = "HH:mm"
        let text = item.map { "\(prefix)  \u{2066}\(format.string(from: $0.start))–\(format.string(from: $0.end))\u{2069}  \($0.title)" }
            ?? "\(prefix): لا تتوفر معلومات"
        return Text(text).foregroundStyle(prefix == "الآن" ? .primary : .secondary)
    }
}

/// iOS supplies the anchored arrow and dismissal gesture; the content remains compact and scrollable.
struct IPTVTouchTrackPopup<Content: View>: View {
    let title: String
    let rows: Int
    let window: CGSize
    let close: () -> Void
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(title).font(.headline)
                Spacer(minLength: 8)
                Button("تم", action: close).font(.subheadline.weight(.semibold))
                    .accessibilityIdentifier("ios.iptv.tracks.done")
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 6, content: content)
            }.scrollIndicators(.hidden)
        }
        .padding(16)
        .frame(width: min(360, max(240, window.width - 32)),
               height: min(max(150, window.height * 0.72), max(150, CGFloat(rows) * 50 + 88)))
        .environment(\.layoutDirection, .rightToLeft)
        .presentationCompactAdaptation(.popover)
        .presentationBackground(.ultraThinMaterial)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("ios.iptv.track.popup")
    }
}
