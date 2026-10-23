import SwiftUI
import CinemaCore

/// A title's page: full-screen artwork, the title at the top, facts, story and actions at the bottom; for a series,
/// season tabs and the episodes row below (Down from the actions).
struct TVDetailView: View {
    let initial: Media
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @State private var media: Media?
    @State private var seasons: [Season] = []
    @State private var seasonID = 0
    @State private var error: String?
    @State private var loading = true
    @State private var omdb: OMDbInfo?
    @State private var episodeRatings: [Int: [Int: String]] = [:]

    private var current: Media { media ?? initial }
    private var episodes: [Media] { seasons.first { $0.id == seasonID }?.episodes ?? [] }
    private var queue: [Media] { seasons.flatMap(\.episodes) }

    /// What Play starts: an opened episode or movie itself; for a series the episode in progress, else the one
    /// after the last finished, else the first.
    private var target: Media? {
        if current.episode > 0 || (!current.isSeries && seasons.isEmpty) { return current }
        let all = queue
        let watched = all.compactMap { episode in library.progress[episode.id].map { (episode, $0) } }
            .sorted { $0.1.updatedAt > $1.1.updatedAt }
        if let latest = watched.first {
            if !latest.1.completed { return latest.0 }
            if let index = all.firstIndex(where: { $0.id == latest.0.id }), all.indices.contains(index + 1) { return all[index + 1] }
        }
        return all.first
    }
    private var playLabel: String {
        guard let target else { return "تشغيل" }
        let started = library.progress[target.id].map { !$0.completed && $0.seconds > 0 } ?? false
        if target.episode > 0 { return started ? "تابع · م\(target.season) ح\(target.episode)" : "شاهد الحلقة \(target.episode)" }
        return started ? "تابع المشاهدة" : "شاهد الآن"
    }
    private var facts: [String] {
        var values = DisplayFormat.facts(current, imdb: omdb?.imdbRating)
        if current.isSeries && !seasons.isEmpty { values.append(seasons.count == 1 ? "موسم واحد" : "\(seasons.count) مواسم") }
        if let rated = omdb?.rated, !rated.isEmpty { values.append(rated) }
        return values
    }
    private var story: String { current.overview.isEmpty ? (omdb?.plot ?? "") : current.overview }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Artwork(url: current.backdrop.isEmpty ? (omdb?.largePoster ?? current.poster) : current.backdrop)
                .ignoresSafeArea()
            LinearGradient(colors: [TVTheme.ink.opacity(0.95), TVTheme.ink.opacity(0.6), .clear], startPoint: .leading, endPoint: .trailing)
                .ignoresSafeArea()
            LinearGradient(colors: [.clear, .clear, TVTheme.ink], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 26) {
                    Text(current.title).font(.system(size: 80, weight: .heavy)).lineLimit(2).frame(maxWidth: 1100, alignment: .leading)
                    Spacer(minLength: 220)
                    Text(facts.joined(separator: "  •  ")).font(.headline).foregroundStyle(.white.opacity(0.9))
                    if !story.isEmpty {
                        Text(story).font(.body).foregroundStyle(.white.opacity(0.85)).lineLimit(3).frame(maxWidth: 1000, alignment: .leading)
                    }
                    if let actors = omdb?.actors, !actors.isEmpty {
                        Text("بطولة: " + actors).font(.callout).foregroundStyle(TVTheme.muted).lineLimit(1)
                    }
                    if let error { Text(error).foregroundStyle(.red) }
                    HStack(spacing: 24) {
                        Button { if let target { player.play(target, queue: target.episode > 0 ? queue : []) } } label: {
                            Label(playLabel, systemImage: "play.fill")
                        }
                        .disabled(target == nil)
                        Button { library.toggle(current) } label: {
                            Label("قائمتي", systemImage: library.isFavorite(current) ? "checkmark" : "plus")
                        }
                    }
                    .focusSection()
                    if loading && seasons.isEmpty { ProgressView() }
                    if !seasons.isEmpty { episodesSection }
                }
                .padding(.horizontal, 90).padding(.top, 60).padding(.bottom, 80)
            }
        }
        .background(TVTheme.ink)
        .task { await load() }
    }

    private var episodesSection: some View {
        VStack(alignment: .leading, spacing: 24) {
            ScrollView(.horizontal) {
                HStack(spacing: 16) {
                    ForEach(seasons) { season in
                        Button { seasonID = season.id } label: {
                            Text("الموسم \(season.id)").fontWeight(season.id == seasonID ? .bold : .regular)
                        }
                        .buttonStyle(SeasonTabStyle(selected: season.id == seasonID))
                    }
                }
                .padding(.vertical, 12)
            }
            .scrollClipDisabled()
            .focusSection()
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 44) {
                    ForEach(episodes) { episode in
                        EpisodeCard(episode: episode, seriesTitle: current.title, rating: episodeRatings[seasonID]?[episode.episode]) {
                            player.play(episode, queue: queue)
                        }
                    }
                }
                .padding(.vertical, 30)
            }
            .scrollClipDisabled()
            .focusSection()
        }
        .padding(.top, 30)
        .task(id: seasonID) {
            guard seasonID > 0, episodeRatings[seasonID] == nil, let client = OMDb.shared else { return }
            let found = await client.episodeRatings(for: current, season: seasonID)
            if !found.isEmpty { episodeRatings[seasonID] = found }
        }
    }

    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let (detail, found) = try await SourceClient().detail(initial)
            media = detail
            seasons = found
            if omdb == nil { omdb = await OMDb.shared?.info(for: detail) }
            if !found.contains(where: { $0.id == seasonID }) {
                let opened: Int? = initial.episode > 0 && found.contains(where: { $0.id == initial.season }) ? initial.season : nil
                seasonID = opened ?? target?.season ?? found.first?.id ?? 0
            }
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Season tab: a 14 % white pill when selected, white with dark text when focused.
struct SeasonTabStyle: ButtonStyle {
    let selected: Bool
    func makeBody(configuration: Configuration) -> some View {
        SeasonTab(configuration: configuration, selected: selected)
    }
    private struct SeasonTab: View {
        let configuration: ButtonStyleConfiguration
        let selected: Bool
        @Environment(\.isFocused) private var focused
        var body: some View {
            configuration.label
                .font(.callout)
                .foregroundStyle(focused ? Color.black : (selected ? Color.white : TVTheme.muted))
                .padding(.horizontal, 30).padding(.vertical, 12)
                .background(Capsule().fill(focused ? Color.white : (selected ? Color.white.opacity(0.16) : .clear)))
                .scaleEffect(focused ? 1.06 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// 16:9 episode card with progress, then "episode N", its name, duration and IMDb rating.
struct EpisodeCard: View {
    let episode: Media
    let seriesTitle: String
    let rating: String?
    let action: () -> Void
    @EnvironmentObject private var library: LibraryStore
    private var name: String {
        let title = episode.episodeTitle.isEmpty ? episode.title : episode.episodeTitle
        return title == seriesTitle ? "" : title
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Button(action: action) {
                Artwork(url: episode.backdrop.isEmpty ? episode.poster : episode.backdrop)
                    .frame(width: 400, height: 225)
                    .overlay(alignment: .bottom) {
                        let fraction = library.fraction(episode)
                        if fraction > 0 { ProgressStrip(fraction: fraction) }
                    }
                    .overlay(alignment: .topTrailing) {
                        if library.progress[episode.id]?.completed == true {
                            Image(systemName: "checkmark.circle.fill").font(.title3).padding(12)
                        }
                    }
            }
            .buttonStyle(.card)
            Text("الحلقة \(episode.episode)").font(.headline)
            Text([name, DisplayFormat.duration(episode.duration), rating.map { "IMDb \($0)" } ?? ""]
                .filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.caption).foregroundStyle(TVTheme.muted).lineLimit(1)
        }
        .frame(width: 400, alignment: .leading)
    }
}
