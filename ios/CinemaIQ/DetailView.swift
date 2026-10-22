import SwiftUI
import CinemaCore

struct PlaybackSelection: Identifiable {
    let media: Media
    let queue: [Media]
    var id: String { media.id }
}
struct DetailView: View {
    let initial: Media
    @EnvironmentObject private var library: LibraryStore
    @State private var media: Media?
    @State private var seasons: [Season] = []
    @State private var selectedSeason = 0
    @State private var error: String?
    @State private var loading = true
    @State private var playback: PlaybackSelection?
    private var current: Media { media ?? initial }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Artwork(url: current.backdrop.isEmpty ? current.poster : current.backdrop).frame(height: 280).clipped()
                VStack(alignment: .leading, spacing: 16) {
                    Text(current.title).font(.largeTitle.bold())
                    Text(current.subtitle).foregroundStyle(.secondary)
                    if !current.rating.isEmpty { Label(current.rating, systemImage: "star.fill").foregroundStyle(.yellow) }
                    HStack {
                        Button { if let playable = current.isSeries && current.episode == 0 ? seasons.flatMap(\.episodes).first : current { play(playable) } } label: { Label("تشغيل", systemImage: "play.fill").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).disabled(loading || !current.isSupported || (current.isSeries && seasons.isEmpty))
                        Button { library.toggle(current) } label: { Image(systemName: library.favorites.contains(where: { $0.id == current.id }) ? "checkmark.circle.fill" : "plus.circle").font(.title) }.accessibilityLabel("إضافة أو إزالة من قائمتي")
                    }
                    Text(current.overview)
                    if loading { ProgressView() }
                    if let error { Text(error).foregroundStyle(.orange) }
                    if !seasons.isEmpty {
                        ScrollView(.horizontal) { HStack { ForEach(seasons) { season in Button("الموسم \(season.id)") { selectedSeason = season.id }.buttonStyle(.bordered).tint(selectedSeason == season.id ? .orange : .gray) } } }
                        ScrollView(.horizontal) { LazyHStack(alignment: .top) {
                            ForEach(seasons.first(where: { $0.id == selectedSeason })?.episodes ?? []) { episode in
                                Button { play(episode) } label: {
                                    VStack(alignment: .leading) {
                                        Artwork(url: episode.poster).frame(width: 240, height: 135).clipped().clipShape(RoundedRectangle(cornerRadius: 12))
                                        Text("الحلقة \(episode.episode) · \(episode.title)").font(.headline).lineLimit(2)
                                        Text(episode.overview).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                                    }.frame(width: 240, alignment: .leading).foregroundStyle(.white)
                                }.buttonStyle(.plain)
                            }
                        } }
                    }
                }.padding(.horizontal)
            }.padding(.bottom)
        }.navigationBarTitleDisplayMode(.inline)
            .task {
                loading = true
                do { let result = try await SourceClient().detail(initial); media = result.0; seasons = result.1; selectedSeason = seasons.first?.id ?? 0 }
                catch { self.error = error.localizedDescription }
                loading = false
            }
            .fullScreenCover(item: $playback) { selection in PlaybackView(selection: selection) }
    }
    private func play(_ media: Media) {
        do { _ = try ContentSource.owner(of: media.id); playback = PlaybackSelection(media: media, queue: seasons.flatMap(\.episodes)) }
        catch { self.error = error.localizedDescription }
    }
}
