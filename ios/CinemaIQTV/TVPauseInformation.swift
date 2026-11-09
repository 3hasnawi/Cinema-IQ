import SwiftUI
import CinemaCore

/// Text-only overlay: the native AVKit controls retain all focus, scrubbing and accessibility behavior.
struct TVPauseInformation: View {
    let media: Media
    let series: Media?
    let info: OMDbInfo?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(series?.title ?? media.title)
                .font(.system(size: 58, weight: .bold))
                .lineLimit(2)
            Spacer(minLength: 0)
            if media.episode > 0 {
                Text(["الموسم \(media.season) · الحلقة \(media.episode)", media.episodeTitle]
                    .filter { !$0.isEmpty }.joined(separator: " — "))
                    .font(.system(size: 32, weight: .semibold)).lineLimit(2)
            }
            FactsLine(facts: DisplayFormat.facts(media, imdb: info?.imdbRating))
            if !media.overview.isEmpty {
                Text(media.overview).font(.callout).foregroundStyle(.white.opacity(0.85))
                    .lineLimit(3).multilineTextAlignment(.leading)
            }
        }
        .frame(maxWidth: 920, maxHeight: .infinity, alignment: .leading)
        .padding(.top, 75).padding(.bottom, 220).padding(.horizontal, 85)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(alignment: .leading) {
            LinearGradient(colors: [.black.opacity(0.62), .clear], startPoint: .leading, endPoint: .trailing)
                .frame(maxWidth: 1120)
        }
        .accessibilityElement(children: .combine)
    }
}
