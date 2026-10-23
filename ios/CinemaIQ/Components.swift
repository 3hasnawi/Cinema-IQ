import SwiftUI
import CinemaCore

// MARK: Navigation

/// A title opened from a card. The key ties the card to the page for the zoom transition, so the same title on
/// two shelves zooms from the card that was tapped.
struct DetailRoute: Hashable {
    let media: Media
    let key: String
}

private struct ZoomNamespaceKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

extension EnvironmentValues {
    var zoomNamespace: Namespace.ID? {
        get { self[ZoomNamespaceKey.self] }
        set { self[ZoomNamespaceKey.self] = newValue }
    }
}

extension View {
    @ViewBuilder func zoomSource(_ id: String, in namespace: Namespace.ID?) -> some View {
        if let namespace { self.matchedTransitionSource(id: id, in: namespace) } else { self }
    }
}

/// A navigation stack whose cards open title pages with the system zoom transition.
struct RoutedStack<Root: View>: View {
    @Namespace private var zoom
    private let root: Root
    init(@ViewBuilder root: () -> Root) { self.root = root() }
    var body: some View {
        NavigationStack {
            root.navigationDestination(for: DetailRoute.self) { route in
                DetailView(initial: route.media).navigationTransition(.zoom(sourceID: route.key, in: zoom))
            }
        }
        .environment(\.zoomNamespace, zoom)
    }
}

// MARK: Source choice

enum SourcePreference {
    static let key = "ios.contentSource.v1"
    static let fallback = ContentSource.dawwirli
    static func source(_ raw: String) -> ContentSource { ContentSource(rawValue: raw) ?? fallback }
}

struct SourceMenu: View {
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    /// Observed so the menu lists providers added or removed in Settings.
    @EnvironmentObject private var providers: ProviderStore
    var body: some View {
        Menu {
            Picker("مصدر المحتوى", selection: $sourceName) {
                ForEach(ContentSource.builtIn + ([ExternalProvider.demo] + providers.providers).map { .external($0.id) }) { source in
                    Text(source.title).tag(source.rawValue)
                }
            }
        } label: {
            Label(SourcePreference.source(sourceName).title, systemImage: "antenna.radiowaves.left.and.right")
                .labelStyle(.titleAndIcon)
                .font(.subheadline.weight(.semibold))
        }
        .accessibilityLabel("مصدر المحتوى")
    }
}

// MARK: Artwork and cards

struct Artwork: View {
    let url: String
    var body: some View {
        AsyncImage(url: URL(string: url), transaction: Transaction(animation: .easeOut(duration: 0.25))) { phase in
            switch phase {
            case .success(let image): image.resizable().scaledToFill()
            case .failure: placeholder(icon: "film")
            default: placeholder(icon: nil)
            }
        }
    }
    private func placeholder(icon: String?) -> some View {
        Rectangle().fill(Theme.panel).overlay {
            if let icon { Image(systemName: icon).font(.title2).foregroundStyle(Theme.muted.opacity(0.6)) }
        }
    }
}

/// Artwork filling a rounded frame of a fixed aspect ratio; its width comes from the parent.
struct ArtworkTile: View {
    let url: String
    var aspect: CGFloat = 2.0 / 3.0
    var corner: CGFloat = 16
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: corner, style: .continuous)
        Color.clear
            .aspectRatio(aspect, contentMode: .fit)
            .overlay { Artwork(url: url) }
            .clipShape(shape)
            .contentShape(shape)
    }
}

struct RatingBadge: View {
    let rating: String
    var body: some View {
        if let value = Double(rating), value > 0 {
            Text("★ \(rating)")
                .font(.caption2.weight(.semibold))
                .padding(.horizontal, 7).padding(.vertical, 3)
                .glass(in: Capsule())
        }
    }
}

/// Poster with the title only underneath.
struct PosterCard: View {
    let media: Media
    let routeKey: String
    var width: CGFloat? = 118
    @Environment(\.zoomNamespace) private var zoom
    var body: some View {
        NavigationLink(value: DetailRoute(media: media, key: routeKey)) {
            VStack(alignment: .leading, spacing: 6) {
                ArtworkTile(url: media.poster.isEmpty ? media.backdrop : media.poster)
                    .overlay(alignment: .topLeading) { RatingBadge(rating: media.rating).padding(6) }
                    .zoomSource(routeKey, in: zoom)
                Text(media.title)
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(width: width)
        }
        .buttonStyle(.plain)
    }
}

struct ProgressBar: View {
    let fraction: Double
    var body: some View {
        Capsule().fill(.white.opacity(0.28)).frame(height: 4)
            .overlay(alignment: .leading) {
                GeometryReader { geometry in
                    Capsule().fill(Theme.accent).frame(width: geometry.size.width * min(max(fraction, 0), 1))
                }
            }
            .accessibilityHidden(true)
    }
}

/// Continue-watching card: wide artwork with the title, the season and episode in a light line, and progress.
struct ContinueCard: View {
    let media: Media
    let fraction: Double
    let action: () -> Void
    let remove: () -> Void
    var body: some View {
        Button(action: action) {
            ArtworkTile(url: media.backdrop.isEmpty ? media.poster : media.backdrop, aspect: 16.0 / 9.0, corner: 18)
                .overlay {
                    LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .center, endPoint: .bottom)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .overlay(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(media.title).font(.subheadline.bold()).lineLimit(1)
                        if media.episode > 0 {
                            Text("الموسم \(media.season) • الحلقة \(media.episode)")
                                .font(.caption).foregroundStyle(.white.opacity(0.7))
                        }
                        ProgressBar(fraction: fraction).padding(.top, 6)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                }
                .frame(width: 250)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive, action: remove) { Label("إزالة من تابع المشاهدة", systemImage: "xmark.circle") }
        }
    }
}

struct SectionHeader: View {
    let title: String
    var body: some View {
        Text(title).font(.title3.bold()).padding(.horizontal, 18)
    }
}

struct PageDots: View {
    let count: Int
    let index: Int
    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { dot in
                Capsule()
                    .fill(dot == index ? Theme.accent : .white.opacity(0.4))
                    .frame(width: dot == index ? 20 : 7, height: 7)
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .glass(in: Capsule())
        .animation(.smooth, value: index)
        .accessibilityHidden(true)
    }
}

struct ErrorState: View {
    let message: String
    let retry: () -> Void
    var body: some View {
        ContentUnavailableView {
            Label("تعذّر التحميل", systemImage: "wifi.exclamationmark")
        } description: {
            Text(message)
        } actions: {
            Button("إعادة المحاولة", action: retry).primaryAction()
        }
    }
}

/// Year, rating, length and quality in one quiet line.
struct MetaLine: View {
    let media: Media
    var imdb: String? = nil
    var body: some View {
        Text(DisplayFormat.facts(media, imdb: imdb).joined(separator: " · "))
            .font(.subheadline)
            .foregroundStyle(Theme.muted)
            .lineLimit(1)
    }
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

/// OMDb client of the app, set up from the key in Info.plist; nil when no key is configured.
enum OMDb {
    static let shared: OMDbClient? = {
        guard let key = Bundle.main.object(forInfoDictionaryKey: "OMDbAPIKey") as? String, !key.isEmpty else { return nil }
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("omdb-cache.json")
        return OMDbClient(key: key, cacheURL: cache)
    }()
}
