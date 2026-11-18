import SwiftUI
import CinemaCore

/// Text only: pausing preserves the picture and leaves all touch targets in the movie HUD.
struct MoviePauseFacts: View {
    let media: Media
    let info: OMDbInfo?
    let showOverview: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            if media.episode > 0 {
                Text(["الموسم \(media.season) · الحلقة \(media.episode)", media.episodeTitle]
                    .filter { !$0.isEmpty }.joined(separator: " — "))
                    .font(.footnote.bold()).lineLimit(1)
            }
            let facts = DisplayFormat.facts(media, imdb: info?.imdbRating)
            if !facts.isEmpty {
                Text(facts.joined(separator: " · ")).font(.caption).lineLimit(2)
            }
            if showOverview && !media.overview.isEmpty {
                Text(media.overview).font(.caption).foregroundStyle(.white.opacity(0.8)).lineLimit(2)
            }
        }
        .multilineTextAlignment(.leading)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.65), radius: 2)
        .accessibilityIdentifier("ios.player.pausedFacts")
    }
}
