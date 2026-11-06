import SwiftUI
import CinemaCore

/// A non-interactive corner lockup. AVKit hosts it beneath its own controls, never over their focus targets.
struct TVPlaybackInfoOverlay: View {
    let details: PlaybackOverlayDetails
    let visible: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 22) {
            Artwork(url: details.poster)
                .frame(width: 96, height: 144)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay { RoundedRectangle(cornerRadius: 10).strokeBorder(.white.opacity(0.16), lineWidth: 1) }
                .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 10) {
                Text(details.title)
                    .font(.system(size: 42, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.75)
                    .multilineTextAlignment(.leading)
                    .accessibilityIdentifier("player.infoTitle")
                if !details.facts.isEmpty {
                    HStack(spacing: 12) {
                        if !details.rating.isEmpty {
                            Text(details.rating)
                                .accessibilityLabel("التقييم \(details.rating)")
                                .accessibilityIdentifier("player.infoRating")
                        }
                        if !details.rating.isEmpty && !details.year.isEmpty {
                            Text("·").accessibilityHidden(true)
                        }
                        if !details.year.isEmpty {
                            Text(details.year)
                                .accessibilityLabel("سنة الإنتاج \(details.year)")
                                .accessibilityIdentifier("player.infoYear")
                        }
                    }
                    .font(.system(size: 22, weight: .regular))
                    .foregroundStyle(.white.opacity(0.75))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                }
                if !details.episode.isEmpty {
                    Text(details.episode)
                        .font(.system(size: 20, weight: .regular))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(20)
        .background {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(LinearGradient(colors: [.black.opacity(0.68), .black.opacity(0.24)],
                                     startPoint: .leading, endPoint: .trailing))
        }
        .environment(\.layoutDirection, .rightToLeft)
        .allowsHitTesting(false)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.infoOverlay")
        .accessibilityHidden(!visible)
        .opacity(visible ? 1 : 0)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: visible)
    }
}
