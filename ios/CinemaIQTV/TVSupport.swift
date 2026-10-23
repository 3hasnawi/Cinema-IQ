import SwiftUI
import CinemaCore

/// Apple TV style: near-black pages under the title's own artwork; Cinema iQ's orange only for progress.
enum TVTheme {
    static let ink = Color(red: 0.035, green: 0.03, blue: 0.045)
    static let panel = Color(white: 0.13)
    static let raised = Color(white: 0.2)
    static let accent = Color(red: 1, green: 0x8A / 255, blue: 0x2B / 255)
    static let muted = Color.white.opacity(0.62)
}

/// The chosen content source; the same setting key as on the iPhone.
enum SourcePreference {
    static let key = "ios.contentSource.v1"
    static let fallback = ContentSource.dawwirli
    static func source(_ raw: String) -> ContentSource { ContentSource(rawValue: raw) ?? fallback }
}

/// Subtitle settings, stored with the iPhone app's keys and values (sizes are iPhone points; the TV doubles them).
enum SubtitlePreference {
    static let sizeKey = "ios.subtitle.size.v1"
    static let languageKey = "ios.subtitle.language.v1"
    static let backgroundKey = "ios.subtitle.background.v1"
    static let sizes: [(String, Double)] = [("صغير", 16), ("متوسط", 20), ("كبير", 24), ("كبير جداً", 28)]
    static let languages: [(String, String)] = [("العربية", "ar"), ("الإنجليزية", "en"), ("بدون ترجمة", "off")]
    static let backgrounds: [(String, Double)] = [("بدون", 0), ("خفيفة", 0.25), ("متوسطة", 0.5), ("داكنة", 0.75)]
    /// On a TV seen from the sofa the text is drawn at twice the iPhone size.
    static let tvScale = 2.0
}

enum DisplayFormat {
    /// With an OMDb rating, IMDb's score replaces the source's stars.
    static func facts(_ media: Media, imdb: String? = nil) -> [String] {
        var facts: [String] = []
        if !media.year.isEmpty { facts.append(media.year) }
        if let imdb, !imdb.isEmpty { facts.append("IMDb \(imdb)") }
        else if let value = Double(media.rating), value > 0 { facts.append("★ \(media.rating)") }
        let length = duration(media.duration)
        if !length.isEmpty { facts.append(length) }
        if !media.quality.isEmpty { facts.append(media.quality) }
        return facts
    }
    /// Seconds, milliseconds or h:mm:ss from the sources become "1 س 45 د"; other text is shown as it is.
    static func duration(_ raw: String) -> String {
        let text = raw.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return "" }
        var seconds: Int?
        if text.contains(":") {
            let parts = text.split(separator: ":").compactMap { Int($0) }
            if (2...3).contains(parts.count) { seconds = parts.reduce(0) { $0 * 60 + $1 } }
        } else if let value = Double(text) {
            seconds = Int(value >= 86_400 ? value / 1000 : value)
        }
        guard let seconds, seconds > 0 else { return text }
        let minutes = max(1, seconds / 60)
        let hours = minutes / 60, rest = minutes % 60
        if hours == 0 { return "\(rest) د" }
        return rest == 0 ? "\(hours) س" : "\(hours) س \(rest) د"
    }
}

/// OMDb client, set up from the key in Info.plist; nil when no key is configured.
enum OMDb {
    static let shared: OMDbClient? = {
        guard let key = Bundle.main.object(forInfoDictionaryKey: "OMDbAPIKey") as? String, !key.isEmpty else { return nil }
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("omdb-cache.json")
        return OMDbClient(key: key, cacheURL: cache)
    }()
}

/// Remote artwork filling its frame, with a quiet placeholder while it loads or when there is none.
struct Artwork: View {
    let url: String
    var body: some View {
        Rectangle().fill(TVTheme.raised)
            .overlay {
                if let address = URL(string: url), !url.isEmpty {
                    AsyncImage(url: address) { phase in
                        if let image = phase.image { image.resizable().scaledToFill() }
                    }
                }
            }
            .clipped()
    }
}

/// Thin accent progress strip, drawn from the leading edge.
struct ProgressStrip: View {
    let fraction: Double
    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Rectangle().fill(.white.opacity(0.25))
                Rectangle().fill(TVTheme.accent).frame(width: proxy.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 8)
    }
}

/// Subtitle line drawn over the video.
struct SubtitleText: View {
    let text: String
    let size: Double
    let background: Double
    var body: some View {
        Text(text)
            .font(.system(size: size * SubtitlePreference.tvScale, weight: .semibold))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .shadow(color: .black.opacity(background < 0.3 ? 1 : 0.6), radius: 3)
            .padding(.horizontal, 20).padding(.vertical, 8)
            .background(.black.opacity(background), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// Error with a retry button.
struct ErrorState: View {
    let message: String
    let retry: () -> Void
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 54)).foregroundStyle(TVTheme.accent)
            Text(message).multilineTextAlignment(.center).foregroundStyle(TVTheme.muted).frame(maxWidth: 900)
            Button("إعادة المحاولة", action: retry)
        }
        .frame(maxWidth: .infinity)
        .padding(60)
    }
}

/// Per-tab navigation paths and the selected tab, so any page can open a title or return a tab to its root.
@MainActor final class TVRouter: ObservableObject {
    @Published var tab = TVTab.home
    @Published var paths: [TVTab: [Media]] = [:]
    func path(_ tab: TVTab) -> Binding<[Media]> {
        Binding(get: { self.paths[tab] ?? [] }, set: { self.paths[tab] = $0 })
    }
    func open(_ media: Media) { paths[tab, default: []].append(media) }
}

/// What Play starts for a series: the episode in progress, else the one after the last finished, else the first.
@MainActor enum PlayTarget {
    static func pick(_ seasons: [Season], library: LibraryStore) -> Media? {
        let all = seasons.flatMap(\.episodes)
        let watched = all.compactMap { episode in library.progress[episode.id].map { (episode, $0) } }
            .sorted { $0.1.updatedAt > $1.1.updatedAt }
        if let latest = watched.first {
            if !latest.1.completed { return latest.0 }
            if let index = all.firstIndex(where: { $0.id == latest.0.id }), all.indices.contains(index + 1) { return all[index + 1] }
        }
        return all.first
    }
    static func label(_ target: Media?, library: LibraryStore) -> String {
        guard let target else { return "تشغيل" }
        let started = library.progress[target.id].map { !$0.completed && $0.seconds > 0 } ?? false
        if target.episode > 0 { return started ? "تابع م\(target.season) ح\(target.episode)" : "شاهد م\(target.season) ح\(target.episode)" }
        return started ? "تابع المشاهدة" : "تشغيل"
    }
}

/// Portrait lockup (system borderless style): the poster lifts with highlight and parallax, the title below moves
/// out of its way. In a shelf it sizes itself to `perRow` posters across the screen; in a grid it fills its column.
struct PosterLockup: View {
    let media: Media
    var perRow: Int? = 6
    var body: some View {
        NavigationLink(value: media) {
            Artwork(url: media.poster.isEmpty ? media.backdrop : media.poster)
                .aspectRatio(2 / 3, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .hoverEffect(.highlight)
                .modifier(RowWidth(perRow: perRow))
            Text(media.title)
                .font(.caption).lineLimit(1)
                .modifier(RowWidth(perRow: perRow))
        }
        .buttonStyle(.borderless)
    }
}

/// Landscape lockup of continue watching: plays straight away; long press removes it.
struct ContinueLockup: View {
    let media: Media
    var perRow = 4
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerPresenter
    var body: some View {
        Button { player.play(media) } label: {
            Artwork(url: media.backdrop.isEmpty ? media.poster : media.backdrop)
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay(alignment: .bottom) {
                    ProgressStrip(fraction: library.fraction(media))
                        .clipShape(Capsule())
                        .padding(.horizontal, 18).padding(.bottom, 16)
                }
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .hoverEffect(.highlight)
                .modifier(RowWidth(perRow: perRow))
            VStack(alignment: .leading, spacing: 2) {
                Text(media.title).font(.caption).lineLimit(1)
                Text(media.episode > 0 ? "الموسم \(media.season) · الحلقة \(media.episode)" : remaining)
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .modifier(RowWidth(perRow: perRow))
        }
        .buttonStyle(.borderless)
        .contextMenu {
            Button("إزالة من تابع المشاهدة", systemImage: "xmark", role: .destructive) { library.remove(media) }
        }
    }
    private var remaining: String {
        guard let progress = library.progress[media.id], progress.duration > progress.seconds else { return "" }
        let minutes = Int((progress.duration - progress.seconds) / 60)
        return minutes < 1 ? "أقل من دقيقة" : "متبقٍ \(minutes) د"
    }
}

/// A lockup's width: `perRow` across the scroll view (aligned to its safe area), or the column's width.
struct RowWidth: ViewModifier {
    let perRow: Int?
    func body(content: Content) -> some View {
        if let perRow {
            content.containerRelativeFrame(.horizontal, count: perRow, spacing: 40, alignment: .leading)
        } else {
            content
        }
    }
}

/// Titled horizontal shelf of lockups.
struct TVShelf<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.title3.weight(.semibold))
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 40) { content }
                    .padding(.vertical, 12)
            }
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
        }
        .focusSection()
    }
}

/// Full-screen artwork behind a page: clear at the top above the fold, blurred all over below it.
struct ShowcaseBackground: View {
    let url: String
    let belowFold: Bool
    var body: some View {
        ZStack {
            TVTheme.ink
            Artwork(url: url)
                .id(url)
                .transition(.opacity)
            LinearGradient(colors: [.black.opacity(0.75), .black.opacity(0.25), .clear], startPoint: .bottom, endPoint: .center)
            Rectangle()
                .fill(.regularMaterial)
                .mask {
                    LinearGradient(stops: [
                        .init(color: .black, location: 0.2),
                        .init(color: .black.opacity(belowFold ? 1 : 0.45), location: 0.38),
                        .init(color: .black.opacity(belowFold ? 1 : 0), location: 0.55)
                    ], startPoint: .bottom, endPoint: .top)
                }
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.5), value: belowFold)
        .animation(.easeInOut(duration: 0.6), value: url)
    }
}

/// Scrolling snaps at the fold: either the whole showcase, or the shelves from the top of the screen.
struct FoldSnapping: ScrollTargetBehavior {
    var aboveFold: Bool
    var showcaseHeight: CGFloat
    func updateTarget(_ target: inout ScrollTarget, context: TargetContext) {
        if aboveFold && target.rect.minY < showcaseHeight * 0.3 { return }
        if !aboveFold && target.rect.minY > showcaseHeight { return }
        let snapToHide = (showcaseHeight * 0.7)...showcaseHeight
        if aboveFold || snapToHide.contains(target.rect.origin.y) {
            target.rect.origin.y = showcaseHeight
        } else {
            target.rect.origin.y = 0
        }
    }
}

/// Year · rating · length · quality, as on the Apple TV app's product pages.
struct FactsLine: View {
    let facts: [String]
    var body: some View {
        Text(facts.joined(separator: "  ·  "))
            .font(.callout.weight(.medium))
            .foregroundStyle(.white.opacity(0.85))
            .lineLimit(1)
    }
}
