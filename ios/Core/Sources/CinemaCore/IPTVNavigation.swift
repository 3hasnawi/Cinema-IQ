import Foundation

/// The queue is source ordered and category scoped; typing in the guide never silently changes the tuning queue.
public enum IPTVNavigation {
    public static func channels(_ values: [IPTVChannel], sourceID: String, group: String? = nil, query: String = "") -> [IPTVChannel] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return values.filter { $0.sourceID == sourceID && (group == nil || $0.group == group) &&
            (text.isEmpty || $0.name.localizedStandardContains(text) || $0.group.localizedStandardContains(text)) }
    }
    public static func neighbor(of id: String, step: Int, in channels: [IPTVChannel]) -> IPTVChannel? {
        guard !channels.isEmpty else { return nil }
        guard let index = channels.firstIndex(where: { $0.id == id }) else { return channels.first }
        let offset = step % channels.count
        return channels[(index + offset + channels.count) % channels.count]
    }
    /// Reopen a guide on the playing channel; a different category/search starts at its first result.
    public static func entry(currentID: String, in channels: [IPTVChannel]) -> String? {
        channels.first(where: { $0.id == currentID })?.id ?? channels.first?.id
    }
}

public struct IPTVStreamQuality: Identifiable, Sendable, Equatable {
    public let width: Int
    public let height: Int
    public let bandwidth: Double
    public var id: String { "\(width)x\(height):\(Int(bandwidth))" }
    public var title: String { height > 0 ? "حتى \(height)p" : String(format: "حتى %.1f Mbps", bandwidth / 1_000_000) }
    public init(width: Int, height: Int, bandwidth: Double) {
        self.width = width; self.height = height; self.bandwidth = bandwidth
    }
}

/// Reports only renditions actually advertised in an authorized master manifest. No invented quality choices.
public enum IPTVStreamQualities {
    public static func parse(_ data: Data, url: URL, allowHTTP: Bool) -> [IPTVStreamQuality] {
        guard data.count <= 2 * 1024 * 1024, let text = String(data: data, encoding: .utf8),
              text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U") else { return [] }
        var pending: IPTVStreamQuality?
        var result: [String: IPTVStreamQuality] = [:]
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("#EXT-X-STREAM-INF:") {
                pending = nil
                let attributes = line.dropFirst("#EXT-X-STREAM-INF:".count).split(separator: ",")
                var width = 0, height = 0, bandwidth: Double = 0
                for attribute in attributes {
                    let pair = attribute.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
                    guard pair.count == 2 else { continue }
                    if pair[0] == "RESOLUTION" {
                        let size = pair[1].lowercased().split(separator: "x").compactMap { Int($0) }
                        if size.count == 2 { width = size[0]; height = size[1] }
                    } else if pair[0] == "BANDWIDTH" { bandwidth = Double(pair[1]) ?? 0 }
                }
                guard (0...16384).contains(width), (0...16384).contains(height), bandwidth.isFinite,
                      bandwidth > 0, bandwidth <= 1_000_000_000 else { continue }
                pending = IPTVStreamQuality(width: width, height: height, bandwidth: bandwidth)
            } else if !line.isEmpty && !line.hasPrefix("#") {
                if let quality = pending, IPTVURLPolicy.stream(line, relativeTo: url, allowHTTP: allowHTTP) != nil {
                    let key = quality.height > 0 ? "height:\(quality.height)" : quality.id
                    if result[key].map({ $0.bandwidth < quality.bandwidth }) ?? true { result[key] = quality }
                }
                pending = nil
            }
        }
        return result.values.sorted { $0.height == $1.height ? $0.bandwidth > $1.bandwidth : $0.height > $1.height }
    }
}
