import Foundation

public enum IPTVParser {
    public static let maximumChannels = 50_000
    private static let attributeRegex = try? NSRegularExpression(pattern: #"([A-Za-z0-9_-]+)\s*=\s*(?:"((?:\\.|[^"])*)"|'([^']*)'|([^\s,]+))"#)
    public static func playlist(_ data: Data, source: IPTVSource) throws -> IPTVCatalog {
        guard data.count <= 20 * 1024 * 1024 else { throw IPTVError.tooLarge }
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16) else { throw IPTVError.invalidPlaylist }
        let lines = text.replacingOccurrences(of: "\u{feff}", with: "").split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard let first = lines.first, first.uppercased().hasPrefix("#EXTM3U") else { throw IPTVError.invalidPlaylist }
        // A channel's HLS manifest is one channel, not a list of its media segments/renditions.
        if lines.prefix(40).contains(where: { $0.uppercased().hasPrefix("#EXT-X-") }) {
            return IPTVCatalog(channels: [IPTVChannel(id: source.id + ":direct", sourceID: source.id,
                name: source.name, group: "مباشر", url: source.address)])
        }
        var name = "", group = "", tvgID = "", logo: URL?
        var channels: [IPTVChannel] = [], occurrences: [String: Int] = [:]
        for line in lines.dropFirst() {
            if line.uppercased().hasPrefix("#EXTINF:") {
                guard line.count <= 16_384 else { throw IPTVError.tooLarge }
                let parts = splitInfo(String(line.dropFirst(8)))
                let attributes = attributes(parts.0)
                name = parts.1.isEmpty ? (attributes["tvg-name"] ?? "") : parts.1
                group = attributes["group-title"] ?? ""; tvgID = attributes["tvg-id"] ?? ""
                logo = attributes["tvg-logo"].flatMap { IPTVURLPolicy.stream($0, relativeTo: source.address, allowHTTP: source.allowHTTP) }
            } else if line.uppercased().hasPrefix("#EXTGRP:") {
                group = String(line.dropFirst(8))
            } else if !line.isEmpty && !line.hasPrefix("#") {
                defer { name = ""; group = ""; tvgID = ""; logo = nil }
                guard let url = IPTVURLPolicy.stream(line, relativeTo: source.address, allowHTTP: source.allowHTTP) else { continue }
                let title = clean(name, fallback: "قناة \(channels.count + 1)")
                let category = clean(group, fallback: "بدون تصنيف")
                let key = IPTVURLPolicy.identifier(source: source.id, metadata: tvgID + "|" + title + "|" + category)
                let ordinal = occurrences[key, default: 0]; occurrences[key] = ordinal + 1
                channels.append(IPTVChannel(id: key + ":\(ordinal)", sourceID: source.id, name: title,
                    group: category, tvgID: String(tvgID.prefix(512)), logo: logo, url: url))
                if channels.count > maximumChannels { throw IPTVError.tooLarge }
            }
        }
        guard !channels.isEmpty else { throw IPTVError.noChannels }
        return IPTVCatalog(channels: channels)
    }

    public static func xtreamAuthentication(_ data: Data) throws {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let user = root["user_info"] as? [String: Any] else { throw IPTVError.invalidResponse }
        guard text(user["auth"]) == "1" else { throw IPTVError.invalidCredentials }
        let status = text(user["status"]).lowercased()
        if !status.isEmpty && status != "active" { throw IPTVError.inactiveSubscription }
        if let expiry = Double(text(user["exp_date"])), expiry > 0,
           expiry < Date().timeIntervalSince1970 { throw IPTVError.inactiveSubscription }
    }

    public static func xtreamChannels(_ data: Data, categories: Data?, source: IPTVSource) throws -> IPTVCatalog {
        guard let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { throw IPTVError.invalidResponse }
        guard rows.count <= maximumChannels else { throw IPTVError.tooLarge }
        var groups: [String: String] = [:]
        if let categories, let rows = try? JSONSerialization.jsonObject(with: categories) as? [[String: Any]] {
            for row in rows { groups[text(row["category_id"])] = clean(text(row["category_name"]), fallback: "بدون تصنيف") }
        }
        var seen = Set<String>()
        let channels = rows.compactMap { row -> IPTVChannel? in
            let streamID = text(row["stream_id"])
            guard streamID.range(of: "^[1-9][0-9]*$", options: .regularExpression) != nil,
                  streamID.count < 24, seen.insert(streamID).inserted,
                  let url = try? IPTVClient.liveURL(source: source, streamID: streamID) else { return nil }
            let logo = IPTVURLPolicy.stream(text(row["stream_icon"]), relativeTo: source.address, allowHTTP: source.allowHTTP)
            return IPTVChannel(id: source.id + ":" + streamID, sourceID: source.id,
                name: clean(text(row["name"]), fallback: "قناة \(streamID)"),
                group: groups[text(row["category_id"])] ?? "بدون تصنيف", tvgID: text(row["epg_channel_id"]), logo: logo, url: url)
        }
        guard !channels.isEmpty else { throw IPTVError.noChannels }
        return IPTVCatalog(channels: channels)
    }

    private static func clean(_ value: String, fallback: String) -> String {
        let value = value.components(separatedBy: .controlCharacters).joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? fallback : String(value.prefix(512))
    }
    private static func text(_ value: Any?) -> String {
        if let value = value as? String { return value }
        if let value = value as? NSNumber { return value.stringValue }
        return ""
    }
    /// The EXTINF separator is the first comma outside quoted attribute values.
    private static func splitInfo(_ line: String) -> (String, String) {
        var quote: Character?, escaped = false
        for index in line.indices {
            let character = line[index]
            if escaped { escaped = false; continue }
            if character == "\\" { escaped = true; continue }
            if let active = quote { if character == active { quote = nil } }
            else if character == "\"" || character == "'" { quote = character }
            else if character == "," {
                return (String(line[..<index]), String(line[line.index(after: index)...]).trimmingCharacters(in: .whitespaces))
            }
        }
        return (line, "")
    }
    private static func attributes(_ text: String) -> [String: String] {
        guard let regex = attributeRegex else { return [:] }
        let string = text as NSString
        var result: [String: String] = [:]
        for match in regex.matches(in: text, range: NSRange(location: 0, length: string.length)) {
            let key = string.substring(with: match.range(at: 1)).lowercased()
            if let index = (2...4).first(where: { match.range(at: $0).location != NSNotFound }) {
                result[key] = string.substring(with: match.range(at: index)).replacingOccurrences(of: "\\\"", with: "\"")
            }
        }
        return result
    }
}
