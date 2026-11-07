import Foundation

/// Stores a quality preference, never a signed stream URL or a title-specific stream identifier.
public enum PlaybackQualityPreference {
    public static let key = "tv.playback.quality.v1"
    public static let automatic = "auto"
    public static let options: [(value: String, title: String)] = [
        (automatic, "تلقائي"), ("2160", "4K · 2160p"), ("1080", "1080p"),
        ("720", "720p"), ("480", "480p"), ("360", "360p")
    ]

    public static func height(_ label: String) -> Int? {
        let text = label.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if text.range(of: "(?<![a-z0-9])(?:4k|uhd)(?![a-z0-9])", options: .regularExpression) != nil { return 2160 }
        for value in [4320, 2160, 1440, 1080, 720, 576, 540, 480, 360, 240, 144] {
            if text.range(of: "(?<![0-9])\(value)(?:p|i)?(?![0-9])", options: .regularExpression) != nil { return value }
        }
        for (pattern, value) in [("(?:full[ -]?hd|fhd)", 1080), ("hd", 720), ("sd", 480)] {
            if text.range(of: "(?<![a-z0-9])\(pattern)(?![a-z0-9])", options: .regularExpression) != nil { return value }
        }
        return nil
    }

    public static func value(for stream: StreamChoice) -> String {
        if let height = height(stream.quality) { return String(height) }
        let label = normalized(stream.quality)
        if isAdaptive(stream) || ["", "auto", "automatic", "تلقائي"].contains(label) { return automatic }
        return "label:" + label
    }

    public static func title(_ value: String) -> String {
        if value.hasPrefix("label:") { return String(value.dropFirst(6)) }
        return options.first { $0.value == value }?.title ?? height(value).map { "\($0)p" } ?? "تلقائي"
    }

    /// Prefer an exact match, then the nearest lower resolution. Falling back never overwrites the preference.
    /// An adaptive HLS master can apply the requested cap even when no separate file has that resolution.
    public static func choose(_ streams: [StreamChoice], preferred: String) -> StreamChoice? {
        if preferred.hasPrefix("label:") {
            let wanted = String(preferred.dropFirst(6))
            return streams.first { normalized($0.quality) == wanted } ?? streams.first
        }
        guard let wanted = height(preferred) else {
            return streams.first(where: isAdaptive) ?? streams.first
        }
        let known = streams.compactMap { stream in height(stream.quality).map { (stream, $0) } }
        if let exact = known.first(where: { $0.1 == wanted }) { return exact.0 }
        if let adaptive = streams.first(where: isAdaptive) { return adaptive }
        if let lower = known.filter({ $0.1 < wanted }).max(by: { $0.1 < $1.1 }) { return lower.0 }
        return known.min(by: { $0.1 < $1.1 })?.0 ?? streams.first
    }

    private static func normalized(_ text: String) -> String {
        String(text.lowercased().split(whereSeparator: \.isWhitespace).joined(separator: " ").prefix(60))
    }

    public static func isAdaptive(_ stream: StreamChoice) -> Bool {
        stream.url.pathExtension.lowercased() == "m3u8" && height(stream.quality) == nil
    }
}
