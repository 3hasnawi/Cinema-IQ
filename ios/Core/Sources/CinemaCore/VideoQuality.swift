import Foundation

/// A preferred resolution, not a promise that a provider has a rendition at that resolution.
public enum VideoQuality: String, CaseIterable, Sendable {
    case automatic = "auto", p360 = "360", p480 = "480", p720 = "720", p1080 = "1080", p2160 = "2160"

    public var height: Int? { Int(rawValue) }
    public var title: String {
        switch self {
        case .automatic: return "تلقائي"
        case .p2160: return "4K · 2160p"
        default: return rawValue + "p"
        }
    }

    /// Prefer an adaptive/source-default endpoint. Otherwise choose the highest known rendition within the
    /// preference, or the lowest available when all renditions exceed it. Preserve provider order for ties.
    public func select(from streams: [StreamChoice]) -> StreamChoice? {
        let automatic = streams.first { Self.isAutomaticLabel($0.quality) }
        guard let height else {
            return automatic ?? streams.first(where: Self.looksLikeHLS) ?? streams.first
        }
        if let automatic { return automatic }
        let known = streams.compactMap { stream in Self.resolution(stream.quality).map { (stream, $0) } }
        if let best = known.filter({ $0.1 <= height }).max(by: { $0.1 < $1.1 }) { return best.0 }
        return known.min(by: { $0.1 < $1.1 })?.0 ?? streams.first(where: Self.looksLikeHLS) ?? streams.first
    }

    public static func looksLikeHLS(_ stream: StreamChoice) -> Bool {
        if stream.url.pathExtension.lowercased() == "m3u8" { return true }
        return (URLComponents(url: stream.url, resolvingAgainstBaseURL: false)?.queryItems ?? []).contains {
            ["ext", "format", "type", "mime"].contains($0.name.lowercased()) &&
            ($0.value?.lowercased().contains("m3u8") == true || $0.value?.lowercased().contains("mpegurl") == true)
        }
    }

    public static func resolution(_ label: String) -> Int? {
        let text = label.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if text.contains("4k") || text.contains("uhd") { return 2160 }
        if text.contains("full hd") || text.contains("fullhd") || text.contains("fhd") { return 1080 }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        if let pair = try? NSRegularExpression(pattern: "[0-9]{3,5}\\s*[x×*]\\s*([0-9]{3,5})"),
           let match = pair.firstMatch(in: text, range: range), let part = Range(match.range(at: 1), in: text),
           let height = Int(text[part]), (144...4320).contains(height) { return height }
        if let pattern = try? NSRegularExpression(pattern: "(?<![0-9])(144|240|360|480|540|576|720|1080|1440|2160|4320)(?:p|\\b)"),
           let match = pattern.firstMatch(in: text, range: range), let part = Range(match.range(at: 1), in: text) {
            return Int(text[part])
        }
        return text == "hd" ? 720 : (text == "sd" ? 480 : nil)
    }

    public static func isAutomaticLabel(_ label: String) -> Bool {
        ["auto", "automatic", "adaptive", "تلقائي"].contains(label.lowercased().trimmingCharacters(in: .whitespacesAndNewlines))
    }
}
