import SwiftUI
import CinemaCore

/// Cinema iQ colours, the same as the iPhone app.
enum TVTheme {
    static let ink = Color(red: 0x1E / 255, green: 0x17 / 255, blue: 0x22 / 255)
    static let panel = Color(red: 0x2B / 255, green: 0x22 / 255, blue: 0x30 / 255)
    static let raised = Color(red: 0x3F / 255, green: 0x32 / 255, blue: 0x45 / 255)
    static let accent = Color(red: 1, green: 0x8A / 255, blue: 0x2B / 255)
    static let muted = Color(red: 0xB5 / 255, green: 0xA9 / 255, blue: 0xBC / 255)
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
