import SwiftUI
import CinemaCore

/// The touch equivalent of the TV episode panel; opening it keeps the current movie clock alive.
struct MovieEpisodesSheet: View {
    let episodes: [Media]
    let currentID: String
    let fraction: (Media) -> Double
    let select: (Media) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.contentLayout) private var layout
    @State private var selectedSeason: Int?
    private var seasons: [Int] { Array(Set(episodes.map(\.season))).sorted() }
    private var season: Int { selectedSeason ?? episodes.first(where: { $0.id == currentID })?.season ?? seasons.first ?? 1 }
    private var cardWidth: CGFloat { min(layout.spacious ? 280 : 230, max(180, layout.size.width - 48)) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if seasons.count > 1 {
                        ScrollView(.horizontal) {
                            HStack {
                                ForEach(seasons, id: \.self) { value in
                                    Button { selectedSeason = value } label: {
                                        Text("الموسم \(value)").font(.subheadline.weight(.semibold))
                                            .padding(.horizontal, 16).frame(minHeight: 44)
                                            .foregroundStyle(season == value ? Color.black : Color.white)
                                            .background(season == value ? Color.white : Color.white.opacity(0.12), in: Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 20)
                        }
                        .scrollIndicators(.hidden)
                    }
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal) {
                            LazyHStack(alignment: .top, spacing: 14) {
                                ForEach(episodes.filter { $0.season == season }) { episode in
                                    Button { select(episode) } label: {
                                        VStack(alignment: .leading, spacing: 7) {
                                            ArtworkTile(url: episode.backdrop.isEmpty ? episode.poster : episode.backdrop,
                                                        aspect: 16.0 / 9.0, corner: 14)
                                                .overlay(alignment: .bottom) {
                                                    if fraction(episode) > 0 { ProgressBar(fraction: fraction(episode)).padding(8) }
                                                }
                                                .overlay(alignment: .topTrailing) {
                                                    if episode.id == currentID {
                                                        Text("يُعرض الآن").font(.caption2.bold())
                                                            .padding(7).glass(in: Capsule()).padding(7)
                                                    }
                                                }
                                            Text("الحلقة \(episode.episode)").font(.subheadline.bold())
                                            if !episode.episodeTitle.isEmpty { Text(episode.episodeTitle).font(.footnote).lineLimit(1) }
                                            if !episode.overview.isEmpty {
                                                Text(episode.overview).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                            }
                                        }
                                        .frame(width: cardWidth, alignment: .leading)
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityIdentifier("ios.player.episode.\(episode.id)")
                                    .id(episode.id)
                                }
                            }
                            .padding(.horizontal, 20).padding(.bottom, 20)
                        }
                        .scrollIndicators(.hidden)
                        .onAppear { proxy.scrollTo(currentID, anchor: .center) }
                        .onChange(of: season) { _, value in
                            if let first = episodes.first(where: { $0.season == value }) {
                                proxy.scrollTo(first.id, anchor: .leading)
                            }
                        }
                    }
                }
                .padding(.top, 12)
            }
            .background(Theme.canvas)
            .navigationTitle("الحلقات").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("تم") { dismiss() }.accessibilityIdentifier("ios.player.episodes.done") } }
            .accessibilityIdentifier("ios.player.episodes.sheet")
        }
    }
}
