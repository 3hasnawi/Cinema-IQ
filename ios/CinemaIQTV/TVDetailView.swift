import SwiftUI
import CinemaCore

/// A title's product page in the Apple TV app's style: its artwork fills the screen, the title, facts and buttons
/// sit at the bottom with the story and credits beside them; below them (blurred art) come the season picker with
/// the season's episodes, then the cast. Plain vertical scrolling: focus moves row by row and the page follows it.
struct TVDetailView: View {
    let initial: Media
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @EnvironmentObject private var router: TVRouter
    @State private var pickingSeason = false
    @State private var media: Media?
    @State private var seasons: [Season] = []
    @State private var seasonID = 0
    @State private var error: String?
    @State private var loading = true
    @State private var omdb: OMDbInfo?
    /// OMDb's ratings and first-shown dates, by season.
    @State private var omdbSeasons: [Int: OMDbSeason] = [:]
    @State private var belowFold = false
    @State private var showStory = false
    @FocusState private var focus: DetailFocus?

    enum DetailFocus: Hashable { case play, list, story, seasons }

    private var current: Media { media ?? initial }
    private var episodes: [Media] { seasons.first { $0.id == seasonID }?.episodes ?? [] }
    private var queue: [Media] { seasons.flatMap(\.episodes) }
    private var target: Media? {
        if current.episode > 0 || (!current.isSeries && seasons.isEmpty) { return current }
        return PlayTarget.pick(seasons, library: library)
    }
    private var facts: [String] {
        var values = DisplayFormat.facts(current, imdb: omdb?.imdbRating)
        if current.isSeries && !seasons.isEmpty { values.append(seasons.count == 1 ? "موسم واحد" : "\(seasons.count) مواسم") }
        if let rated = omdb?.rated, !rated.isEmpty, rated != "N/A" { values.append(rated) }
        return values
    }
    private var story: String { current.overview.isEmpty ? (omdb?.plot ?? "") : current.overview }
    private var actors: [String] {
        (omdb?.actors ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && $0 != "N/A" }
    }
    private var artwork: String { current.backdrop.isEmpty ? (omdb?.largePoster ?? current.poster) : current.backdrop }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                ShowcaseBackground(url: artwork, belowFold: belowFold)
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 50) {
                        header
                            .frame(height: max(400, proxy.size.height * 0.86), alignment: .bottomLeading)
                            .onScrollVisibilityChange(threshold: 0.6) { visible in
                                if belowFold == visible { belowFold = !visible }
                            }
                        if !seasons.isEmpty { episodesSection }
                        if !actors.isEmpty { castSection }
                    }
                    .padding(.bottom, 80)
                }
                .scrollClipDisabled()
                .scrollIndicators(.hidden)
            }
        }
        .defaultFocus($focus, .play)
        .fullScreenCover(isPresented: $showStory) { storyPage }
        .task { await load() }
        // Back on the season button once the season list closes.
        .onChange(of: router.popup == nil) { _, closed in
            guard closed, pickingSeason else { return }
            pickingSeason = false
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(80))
                focus = .seasons
            }
        }
    }

    // MARK: Above the fold

    private var header: some View {
        VStack(alignment: .leading, spacing: 26) {
            Spacer(minLength: 0)
            Text(current.title)
                .font(.system(size: 76, weight: .bold))
                .lineLimit(2).minimumScaleFactor(0.7)
                .frame(maxWidth: 1300, alignment: .leading)
            if let error { Text(error).font(.callout).foregroundStyle(.red) }
            HStack(alignment: .top, spacing: 56) {
                VStack(spacing: 14) {
                    Button { if let target { player.play(target, queue: target.episode > 0 ? queue : []) } } label: {
                        Label(loading && current.isSeries && seasons.isEmpty ? "جارٍ التحميل…" : PlayTarget.label(target, library: library),
                              systemImage: "play.fill")
                            .font(.callout.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    // Not disabled while the seasons load, so focus starts here and not on the story.
                    .focused($focus, equals: .play)
                    Button { library.toggle(current) } label: {
                        Label(library.isFavorite(current) ? "في قائمتي" : "أضف إلى قائمتي",
                              systemImage: library.isFavorite(current) ? "checkmark" : "plus")
                            .font(.callout.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .focused($focus, equals: .list)
                }
                .frame(width: 440)
                VStack(alignment: .leading, spacing: 14) {
                    FactsLine(facts: facts)
                    if !story.isEmpty {
                        Button { showStory = true } label: {
                            Text(story)
                                .font(.callout).foregroundStyle(.white.opacity(0.85))
                                .lineLimit(4).multilineTextAlignment(.leading)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(14)
                        }
                        .buttonStyle(.plain)
                        .focused($focus, equals: .story)
                    }
                }
                .frame(maxWidth: 820, alignment: .leading)
                VStack(alignment: .leading, spacing: 12) {
                    credit("بطولة", actors.prefix(3).joined(separator: "، "))
                    credit("إخراج", omdb?.director ?? "")
                    credit("النوع", omdb?.genre ?? "")
                }
                .frame(maxWidth: 380, alignment: .leading)
            }
            .focusSection()
            if loading && seasons.isEmpty && current.isSeries { ProgressView() }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func credit(_ title: String, _ value: String) -> some View {
        if !value.isEmpty && value != "N/A" {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption.weight(.bold)).foregroundStyle(.white.opacity(0.55))
                Text(value).font(.caption).foregroundStyle(.white.opacity(0.85)).lineLimit(2)
            }
        }
    }

    private var storyPage: some View {
        ZStack {
            ShowcaseBackground(url: artwork, belowFold: true)
            VStack(spacing: 30) {
                Text(current.title).font(.title2.bold())
                Text(story).font(.body).multilineTextAlignment(.center).frame(maxWidth: 1200)
            }
            .padding(80)
        }
    }

    // MARK: Below the fold

    private var episodesSection: some View {
        VStack(alignment: .leading, spacing: 22) {
            // The season is chosen from a glass list; the episodes row below always belongs to it.
            HStack(spacing: 28) {
                Button(action: openSeasons) {
                    HStack(spacing: 14) {
                        Text(seasonTitle(seasons.first { $0.id == seasonID } ?? seasons[0]))
                            .font(.callout.weight(.semibold))
                        if seasons.count > 1 {
                            Image(systemName: "chevron.down").font(.caption.weight(.bold))
                        }
                    }
                    .padding(.horizontal, 8)
                }
                .tvGlassButton()
                .focused($focus, equals: .seasons)
                Text(episodes.count == 1 ? "حلقة واحدة" : "\(episodes.count) حلقات")
                    .font(.caption).foregroundStyle(.white.opacity(0.6))
                Spacer(minLength: 0)
            }
            .focusSection()
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(episodes) { episode in
                        EpisodeLockup(episode: episode, seriesTitle: current.title, rating: omdbSeasons[seasonID]?.ratings[episode.episode],
                                      aired: episode.airDate.isEmpty ? (omdbSeasons[seasonID]?.released[episode.episode] ?? "") : episode.airDate) {
                            player.play(episode, queue: queue)
                        }
                    }
                }
                .padding(.vertical, 12)
            }
            .id(seasonID)
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
            .focusSection()
        }
        .task(id: seasonID) {
            guard seasonID > 0, omdbSeasons[seasonID] == nil, let client = OMDb.shared else { return }
            let found = await client.seasonDetails(for: current, season: seasonID)
            if !found.ratings.isEmpty || !found.released.isEmpty { omdbSeasons[seasonID] = found }
        }
    }

    /// The seasons in the glass popup: each with its year and number of episodes; the chosen one has a checkmark.
    private func openSeasons() {
        guard seasons.count > 1 else { return }
        pickingSeason = true
        router.popup = TVPopup(title: current.title, options: seasons.map { season in
            let count = season.episodes.count
            return (id: String(season.id), title: seasonTitle(season) + "  —  " + (count == 1 ? "حلقة واحدة" : "\(count) حلقات"))
        }, selected: String(seasonID)) { chosen in
            if let id = Int(chosen) { seasonID = id }
        }
    }

    /// "الموسم 2 · 2019": the year of its earliest episode, from the source or OMDb.
    private func seasonTitle(_ season: Season) -> String {
        let omdbYear = omdbSeasons[season.id]?.released.values.compactMap { AirDate.year($0) }.min()
        guard let year = AirDate.year(of: season) ?? omdbYear else { return "الموسم \(season.id)" }
        return "الموسم \(season.id) · \(year)"
    }

    private var castSection: some View {
        TVShelf(title: "طاقم العمل") {
            ForEach(actors, id: \.self) { name in
                Button {} label: {
                    Circle()
                        .fill(TVTheme.raised)
                        .overlay {
                            Text(initials(name)).font(.system(size: 44, weight: .semibold)).foregroundStyle(.white.opacity(0.85))
                        }
                        .frame(width: 180, height: 180)
                        .hoverEffect(.highlight)
                    Text(name).font(.caption).lineLimit(1).frame(width: 220)
                }
                .buttonStyle(.borderless)
                .buttonBorderShape(.circle)
            }
        }
    }

    private func initials(_ name: String) -> String {
        name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined()
    }

    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let (detail, found) = try await SourceClient().detail(initial)
            media = detail
            seasons = found
            if !found.contains(where: { $0.id == seasonID }) {
                let opened: Int? = initial.episode > 0 && found.contains(where: { $0.id == initial.season }) ? initial.season : nil
                seasonID = opened ?? target?.season ?? found.first?.id ?? 0
            }
            if omdb == nil { omdb = await OMDb.shared?.info(for: detail) }
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Episode lockup: 16:9 still with progress, then "episode N", its name and length with the IMDb rating.
struct EpisodeLockup: View {
    let episode: Media
    let seriesTitle: String
    let rating: String?
    /// First shown, as the source or OMDb gives it.
    var aired = ""
    let action: () -> Void
    @EnvironmentObject private var library: LibraryStore
    private var name: String {
        let title = episode.episodeTitle.isEmpty ? episode.title : episode.episodeTitle
        return title == seriesTitle ? "" : title
    }
    var body: some View {
        Button(action: action) {
            Artwork(url: episode.backdrop.isEmpty ? episode.poster : episode.backdrop)
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay(alignment: .bottom) {
                    let fraction = library.fraction(episode)
                    if fraction > 0 && library.progress[episode.id]?.completed != true {
                        ProgressStrip(fraction: fraction).clipShape(Capsule()).padding(.horizontal, 18).padding(.bottom, 16)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if library.progress[episode.id]?.completed == true {
                        Image(systemName: "checkmark.circle.fill").font(.title3).foregroundStyle(.white).padding(14)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .hoverEffect(.highlight)
                .containerRelativeFrame(.horizontal, count: 4, spacing: 40, alignment: .leading)
            VStack(alignment: .leading, spacing: 3) {
                Text("الحلقة \(episode.episode)").font(.caption2.weight(.bold)).foregroundStyle(.secondary)
                Text(name.isEmpty ? "الحلقة \(episode.episode)" : name).font(.caption).lineLimit(1)
                Text([DisplayFormat.duration(episode.duration), rating.map { "IMDb \($0)" } ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                let date = AirDate.display(aired)
                if !date.isEmpty {
                    Label(date, systemImage: "calendar").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .containerRelativeFrame(.horizontal, count: 4, spacing: 40, alignment: .leading)
        }
        .buttonStyle(.borderless)
    }
}
