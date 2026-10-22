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
                    if !current.overview.isEmpty { overview }
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
                    .overlay { Artwork(url: current.backdrop.isEmpty ? current.poster : current.backdrop) }
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

    private var chips: [String] {
        var values = [current.isSeries ? "مسلسل" : "فيلم"]
        if current.episode > 0 { values.append("الموسم \(current.season) • الحلقة \(current.episode)") }
        values += DisplayFormat.facts(current)
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
            Text(current.overview)
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
                            ForEach(seasons) { season in Text("الموسم \(season.id)").tag(season.id) }
                        }
                    } label: {
                        Label("الموسم \(seasonID)", systemImage: "chevron.down").font(.subheadline.weight(.semibold))
                    }
                    .menuStyle(.button)
                    .secondaryAction()
                } else {
                    Text("الموسم \(seasonID)").font(.subheadline).foregroundStyle(Theme.muted)
                }
            }
            LazyVStack(spacing: 14) {
                ForEach(episodes) { episode in
                    EpisodeRow(episode: episode, seriesTitle: current.title,
                               fraction: library.fraction(episode),
                               completed: library.progress[episode.id]?.completed == true) { play(episode) }
                }
            }
        }
    }

    private func play(_ media: Media) {
        player.play(media, queue: media.episode > 0 ? queue : [])
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
        return [length, state].filter { !$0.isEmpty }.joined(separator: " · ")
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
