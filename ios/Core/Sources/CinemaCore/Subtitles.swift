import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// An external subtitle file offered by the source.
public struct SubtitleTrack: Identifiable, Hashable, Sendable {
    public let url: URL
    public let language: String
    public let title: String
    public var id: String { url.absoluteString }
    public init(url: URL, language: String, title: String) { self.url = url; self.language = language; self.title = title }
}

/// One timed line of a subtitle file.
public struct SubtitleCue: Equatable, Sendable {
    public let start: Double
    public let end: Double
    public let text: String
}

public enum SubtitleCatalog {
    private static let names = ["ar": "العربية", "en": "الإنجليزية", "fr": "الفرنسية", "tr": "التركية",
                                "es": "الإسبانية", "de": "الألمانية", "ku": "الكردية", "fa": "الفارسية"]
    /// Same language recognition as Android: codes, English names and Arabic names.
    public static func languageCode(_ raw: String) -> String? {
        let s = raw.trimmingCharacters(in: .whitespaces).lowercased()
        if s.isEmpty { return nil }
        let table: [String: [String]] = [
            "ar": ["ar", "ara", "arabic", "العربية", "عربي", "عربية"],
            "en": ["en", "eng", "english", "الإنجليزية", "الانجليزية", "إنجليزي", "انجليزي", "الانكليزية", "الإنكليزية", "انكليزي", "إنكليزي"],
            "fr": ["fr", "fra", "fre", "french", "الفرنسية"], "tr": ["tr", "tur", "turkish", "التركية"],
            "es": ["es", "spa", "spanish", "الإسبانية"], "de": ["de", "deu", "ger", "german", "الألمانية"],
            "ku": ["ku", "kur", "kurdish", "الكردية"]
        ]
        for (code, values) in table where values.contains(s) { return code }
        if s.range(of: "^[a-z]{2}$", options: .regularExpression) != nil { return s }
        return nil
    }
    public static func displayName(_ code: String, fallback: String) -> String { names[code] ?? (fallback.isEmpty ? code : fallback) }

    /// Cinemana lists its files under `translations` in the title information.
    public static func cinemana(_ value: Any?) -> [SubtitleTrack] {
        let rows = (value as? [[String: Any]]) ?? []
        var seen = Set<String>()
        return rows.compactMap { row -> SubtitleTrack? in
            let raw = DawwirliClient.text(row, "file", "url")
            guard !raw.isEmpty, let url = URL(string: raw, relativeTo: URL(string: "https://cnth2.shabakaty.cc/"))?.absoluteURL,
                  url.scheme == "https", url.host != nil, seen.insert(url.absoluteString).inserted else { return nil }
            let nested = (row["language"] as? [String: Any]) ?? (row["lang"] as? [String: Any]) ?? [:]
            let candidates = [DawwirliClient.text(row, "languageCode", "language_code", "langCode", "locale"),
                              DawwirliClient.text(nested, "code", "iso", "name", "ar_name", "en_name"),
                              DawwirliClient.text(row, "language", "lang"),
                              DawwirliClient.text(row, "languageName", "language_name", "ar_name", "en_name", "name", "title", "label")]
            let code = candidates.lazy.compactMap(languageCode).first ?? "und"
            let label = DawwirliClient.text(row, "ar_name", "languageName", "name", "title", "label")
            return SubtitleTrack(url: url, language: code, title: displayName(code, fallback: label.isEmpty ? "ترجمة" : label))
        }
    }

    /// WebVTT or SubRip text to cues. Anything else gives no cues.
    public static func cues(_ content: String) -> [SubtitleCue] {
        let text = content.replacingOccurrences(of: "\u{FEFF}", with: "")
            .replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var cues: [SubtitleCue] = []
        for block in text.components(separatedBy: "\n\n") {
            let lines = block.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
            guard let timingIndex = lines.firstIndex(where: { $0.contains("-->") }) else { continue }
            let parts = lines[timingIndex].components(separatedBy: "-->")
            guard parts.count == 2, let start = time(parts[0]), let end = time(parts[1].split(separator: " ").first.map(String.init) ?? ""),
                  end > start else { continue }
            let body = lines[(timingIndex + 1)...].joined(separator: "\n")
                .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
                .replacingOccurrences(of: "\\{[^}]*\\}", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !body.isEmpty { cues.append(SubtitleCue(start: start, end: end, text: body)) }
        }
        return cues.sorted { $0.start < $1.start }
    }

    /// "01:02:03,456", "01:02:03.456" or "02:03.456" to seconds.
    static func time(_ raw: String) -> Double? {
        let value = raw.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        let parts = value.split(separator: ":")
        guard (2...3).contains(parts.count) else { return nil }
        var seconds = 0.0
        for part in parts {
            guard let number = Double(part), number >= 0 else { return nil }
            seconds = seconds * 60 + number
        }
        return seconds
    }

    /// The cue on screen at a time, by binary search over sorted cues.
    public static func cue(at time: Double, in cues: [SubtitleCue]) -> SubtitleCue? {
        var low = 0, high = cues.count - 1, found: Int?
        while low <= high {
            let mid = (low + high) / 2
            if cues[mid].start <= time { found = mid; low = mid + 1 } else { high = mid - 1 }
        }
        guard let index = found else { return nil }
        // Overlapping cues: the latest started one that is still on.
        for candidate in stride(from: index, through: max(0, index - 3), by: -1) where cues[candidate].end > time {
            return cues[candidate]
        }
        return nil
    }

    /// Downloads a subtitle file from the source's network, at most 2 MB.
    public static func download(_ track: SubtitleTrack) async throws -> Data {
        let host = track.url.host?.lowercased() ?? ""
        let root = ["shabakaty.cc", "shabakaty.com", "vodu.me", "dawwirli.watch"].first { SourceNetwork.belongs(host, to: $0) } ?? host
        let data = try await SourceHTTP.read(track.url, root: root)
        guard data.count <= 2 * 1024 * 1024 else { throw CinemaError.invalidResponse }
        return data
    }
}

extension SourceClient {
    /// External subtitle files of the played title (episode or movie), when the source has any.
    public func subtitles(_ media: Media) async -> [SubtitleTrack] {
        do {
            switch try ContentSource.owner(of: media.id) {
            case .cinemana: return try await CinemanaClient().subtitles(media.id)
            case .vodu: return try await VoduClient().subtitles(media.id)
            case .dawwirli: return []
            }
        } catch { return [] }
    }
}
