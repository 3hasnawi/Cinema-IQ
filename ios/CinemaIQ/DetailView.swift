import SwiftUI
import CinemaCore

/// Title page: large artwork that stretches when pulled, the facts in glass chips, the main play action,
/// the story and, for a series, its episodes by season.
struct DetailView: View {
    let initial: Media
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    @State private var media: Media?
    @State private var seasons: [Season] = []
    @State private var seasonID = 0
    @State private var error: String?
    @State private var loading = true
    @State private var expanded = false
    @State private var omdb: OMDbInfo?
    /// IMDb ratings of the episodes by season (season → episode → rating), from OMDb.
    @State private var episodeRatings: [Int: [Int: String]] = [:]
    /// TMDB: cast portraits, directors, IMDb ID, Arabic story and season dates.
    @State private var tmdb: TMDBWork?
    @State private var tmdbSeasons: [Int: TMDBSeason] = [:]
    /// OMDb's first-shown dates by season, the last fallback for episode dates.
    @State private var omdbSeasons: [Int: OMDbSeason] = [:]

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
        if target.episode > 0 { return started ? "استئناف الحلقة \(target.episode)" : "تشغيل الحلقة \(target.episode)" }
        return started ? "استئناف" : "تشغيل"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                VStack(alignment: .leading, spacing: 18) {
                    actions
                    if let omdb { OMDbSummary(info: omdb, showActors: tmdb?.cast.isEmpty ?? true) }
                    if !storyText.isEmpty { overview }
                    if let tmdb, !tmdb.cast.isEmpty { castRow(tmdb) }
                    if loading && seasons.isEmpty { ProgressView().frame(maxWidth: .infinity) }
                    if let error { ErrorState(message: error) { Task { await load() } } }
                    if !seasons.isEmpty { episodeSection }
                }
                .padding(.horizontal, 20)
            }
            .padding(.bottom, 30)
        }
        .background(Theme.ink)
        .ignoresSafeArea(edges: .top)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var header: some View {
        GeometryReader { geometry in
            // Pulling the page down stretches the artwork instead of showing an empty gap.
            let stretch = max(0, geometry.frame(in: .scrollView).minY)
            ZStack {
                Color.clear
                    .overlay { Artwork(url: headerImage) }
                    .clipped()
                LinearGradient(stops: [
                    .init(color: .clear, location: 0.35),
                    .init(color: Theme.ink.opacity(0.75), location: 0.75),
                    .init(color: Theme.ink, location: 1)
                ], startPoint: .top, endPoint: .bottom)
            }
            .frame(width: geometry.size.width, height: geometry.size.height + stretch)
            .offset(y: -stretch)
        }
        .frame(height: 430)
        .overlay(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 10) {
                Text(current.title)
                    .font(.largeTitle.bold())
                    .lineLimit(3)
                    .minimumScaleFactor(0.7)
                ScrollView(.horizontal) {
                    HStack(spacing: 6) {
                        ForEach(chips, id: \.self) { chip in
                            Text(chip)
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .glass(in: Capsule())
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
        }
    }

    /// OMDb's large poster when the work was found there, the source's artwork otherwise.
    private var headerImage: String {
        if let poster = omdb?.largePoster, !poster.isEmpty { return poster }
        if current.backdrop.isEmpty, let backdrop = tmdb?.backdrop, !backdrop.isEmpty { return backdrop }
        return current.backdrop.isEmpty ? current.poster : current.backdrop
    }
    /// The source's Arabic story first; OMDb's (English) plot when the source has none.
    private var storyText: String {
        if !current.overview.isEmpty { return current.overview }
        if let overview = tmdb?.overview, !overview.isEmpty { return overview }
        return omdb?.plot ?? ""
    }
    private var seriesContext: Media? { current.isSeries || current.episode > 0 ? current : nil }

    private var chips: [String] {
        var values = [current.isSeries ? "مسلسل" : "فيلم"]
        if current.episode > 0 { values.append("الموسم \(current.season) • الحلقة \(current.episode)") }
        values += DisplayFormat.facts(current, imdb: omdb?.imdbRating)
        if let omdb {
            if current.duration.isEmpty, !omdb.runtime.isEmpty { values.append(omdb.runtime.replacingOccurrences(of: " min", with: " د")) }
            if !omdb.rated.isEmpty { values.append(omdb.rated) }
        }
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }

    private var actions: some View {
        HStack(spacing: 12) {
            Button {
                if let target { play(target) }
            } label: {
                Label(playLabel, systemImage: "play.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .primaryAction()
            .disabled(target == nil || !current.isSupported)
            Button { library.toggle(current) } label: {
                Image(systemName: library.isFavorite(current) ? "checkmark" : "plus").font(.headline)
            }
            .secondaryAction()
            .buttonBorderShape(.circle)
            .accessibilityLabel(library.isFavorite(current) ? "إزالة من قائمتي" : "إضافة إلى قائمتي")
        }
        .controlSize(.large)
    }

    private var overview: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(storyText)
                .font(.subheadline)
                .foregroundStyle(Theme.muted)
                .lineLimit(expanded ? nil : 4)
            Button(expanded ? "أقل" : "المزيد") { withAnimation(.smooth) { expanded.toggle() } }
                .font(.footnote.weight(.semibold))
                .tint(Theme.accent)
        }
    }

    private var episodeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("الحلقات").font(.title3.bold())
                Spacer()
                if seasons.count > 1 {
                    Menu {
                        Picker("الموسم", selection: $seasonID) {
                            ForEach(seasons) { season in Text(seasonTitle(season)).tag(season.id) }
                        }
                    } label: {
                        Label(seasons.first { $0.id == seasonID }.map(seasonTitle) ?? "الموسم \(seasonID)", systemImage: "chevron.down")
                            .font(.subheadline.weight(.semibold))
                    }
                    .menuStyle(.button)
                    .secondaryAction()
                } else {
                    Text(seasons.first.map(seasonTitle) ?? "الموسم \(seasonID)").font(.subheadline).foregroundStyle(Theme.muted)
                }
            }
            LazyVStack(spacing: 14) {
                ForEach(episodes) { episode in
                    EpisodeRow(episode: episode, seriesTitle: current.title,
                               rating: episodeRatings[seasonID]?[episode.episode], aired: aired(episode),
                               fraction: library.fraction(episode),
                               completed: library.progress[episode.id]?.completed == true) { play(episode) }
                }
            }
            // One OMDb request per season, only once the season is shown.
            .task(id: seasonID) {
                guard seasonID > 0, episodeRatings[seasonID] == nil, let client = OMDb.shared else { return }
                let found = await client.seasonDetails(for: current, season: seasonID)
                if !found.ratings.isEmpty { episodeRatings[seasonID] = found.ratings }
                if !found.released.isEmpty { omdbSeasons[seasonID] = found }
            }
            // TMDB's dates for the chosen season, once the series is matched.
            .task(id: "\(seasonID)-\(tmdb?.id ?? 0)") {
                guard seasonID > 0, tmdbSeasons[seasonID] == nil, let work = tmdb, let client = TMDB.shared else { return }
                if let found = await client.season(seasonID, of: work) { tmdbSeasons[seasonID] = found }
            }
        }
    }

    private func play(_ media: Media) {
        player.play(media, queue: media.episode > 0 ? queue : [], series: seriesContext)
    }

    /// "الموسم 2 · 2019": the year of its earliest episode, from the source, TMDB or OMDb.
    private func seasonTitle(_ season: Season) -> String {
        let tmdbYear = tmdbSeasons[season.id].flatMap { AirDate.year($0.airDate) ?? $0.episodes.values.compactMap { AirDate.year($0.airDate) }.min() }
        let omdbYear = omdbSeasons[season.id]?.released.values.compactMap { AirDate.year($0) }.min()
        guard let year = AirDate.year(of: season) ?? tmdbYear ?? omdbYear else { return "الموسم \(season.id)" }
        return "الموسم \(season.id) · \(year)"
    }

    /// An episode's first-shown date: the source's, else TMDB's, else OMDb's.
    private func aired(_ episode: Media) -> String {
        if !episode.airDate.isEmpty { return episode.airDate }
        if let date = tmdbSeasons[seasonID]?.episodes[episode.episode]?.airDate, !date.isEmpty { return date }
        return omdbSeasons[seasonID]?.released[episode.episode] ?? ""
    }

    /// TMDB's cast in round portraits, and the directors or creators.
    private func castRow(_ work: TMDBWork) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("طاقم العمل").font(.title3.bold())
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(work.cast) { person in
                        VStack(spacing: 6) {
                            Circle().fill(Theme.panel)
                                .overlay {
                                    Text(person.name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined())
                                        .font(.headline).foregroundStyle(Theme.muted)
                                }
                                .overlay {
                                    if let url = URL(string: person.photo), !person.photo.isEmpty {
                                        AsyncImage(url: url) { phase in
                                            if let image = phase.image { image.resizable().scaledToFill() }
                                        }
                                    }
                                }
                                .clipShape(Circle())
                                .frame(width: 72, height: 72)
                            Text(person.name).font(.caption2).lineLimit(2).multilineTextAlignment(.center).frame(width: 80)
                        }
                    }
                }
            }
            .scrollIndicators(.hidden)
            let makers = work.directors.isEmpty ? work.creators : work.directors
            if !makers.isEmpty {
                Text((work.directors.isEmpty ? "صناعة: " : "إخراج: ") + makers.prefix(2).joined(separator: "، "))
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
        }
    }

    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let (detail, found) = try await SourceClient().detail(initial)
            media = detail
            seasons = found
            // OMDb and TMDB at the same time.
            async let omdbInfo: OMDbInfo? = OMDb.shared?.info(for: detail)
            async let tmdbWork: TMDBWork? = TMDB.shared?.work(for: detail)
            let (foundOMDb, foundTMDB) = await (omdbInfo, tmdbWork)
            if omdb == nil { omdb = foundOMDb }
            if tmdb == nil { tmdb = foundTMDB }
            if !found.contains(where: { $0.id == seasonID }) {
                let opened: Int? = initial.episode > 0 && found.contains(where: { $0.id == initial.season }) ? initial.season : nil
                seasonID = opened ?? found.first?.id ?? 0
            }
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }
}

private struct EpisodeRow: View {
    let episode: Media
    let seriesTitle: String
    let rating: String?
    var aired = ""
    let fraction: Double
    let completed: Bool
    let action: () -> Void
    private var name: String {
        let title = episode.episodeTitle.isEmpty ? episode.title : episode.episodeTitle
        return title == seriesTitle ? "" : title
    }
    private var status: String {
        let length = DisplayFormat.duration(episode.duration)
        let state = completed ? "شوهدت" : (fraction > 0 ? "قيد المشاهدة" : "")
        let imdb = rating.map { "IMDb \($0)" } ?? ""
        return [length, imdb, state].filter { !$0.isEmpty }.joined(separator: " · ")
    }
    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                ArtworkTile(url: episode.poster.isEmpty ? episode.backdrop : episode.poster, aspect: 16.0 / 9.0, corner: 12)
                    .frame(width: 150)
                    .overlay {
                        Image(systemName: "play.fill")
                            .font(.subheadline)
                            .padding(10)
                            .glass(in: Circle())
                    }
                    .overlay(alignment: .bottom) {
                        if completed || fraction > 0 { ProgressBar(fraction: completed ? 1 : fraction).padding(8) }
                    }
                VStack(alignment: .leading, spacing: 3) {
                    Text("الحلقة \(episode.episode)").font(.subheadline.bold())
                    if !name.isEmpty { Text(name).font(.footnote).lineLimit(1) }
                    if !status.isEmpty { Text(status).font(.caption).foregroundStyle(Theme.muted) }
                    let date = AirDate.display(aired)
                    if !date.isEmpty { Label(date, systemImage: "calendar").font(.caption2).foregroundStyle(Theme.muted) }
                    if !episode.overview.isEmpty {
                        Text(episode.overview).font(.caption).foregroundStyle(Theme.muted).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Ratings and credits from OMDb.
private struct OMDbSummary: View {
    let info: OMDbInfo
    /// Off when TMDB's cast (with portraits) is shown below.
    var showActors = true
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                if !info.imdbRating.isEmpty { score("IMDb", info.imdbRating + "/10", color: Color(red: 0.96, green: 0.77, blue: 0.09)) }
                if !info.rottenTomatoes.isEmpty { score("Rotten Tomatoes", info.rottenTomatoes, color: Color(red: 0.98, green: 0.32, blue: 0.2)) }
                if !info.metacritic.isEmpty { score("Metacritic", info.metacritic, color: Color(red: 0.4, green: 0.8, blue: 0.2)) }
            }
            VStack(alignment: .leading, spacing: 6) {
                credit("النوع", info.genre)
                credit("الإخراج", info.director)
                if showActors { credit("البطولة", info.actors) }
            }
        }
    }
    private func score(_ source: String, _ value: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.subheadline.bold()).foregroundStyle(color)
            Text(source).font(.caption2).foregroundStyle(Theme.muted)
        }
        .environment(\.layoutDirection, .leftToRight)
        .padding(.horizontal, 12).padding(.vertical, 6)
        .glass(in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
    @ViewBuilder private func credit(_ label: String, _ value: String) -> some View {
        if !value.isEmpty {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(label).font(.footnote.weight(.semibold)).foregroundStyle(Theme.muted).frame(width: 64, alignment: .leading)
                Text(value).font(.footnote).lineLimit(2)
            }
        }
    }
}
