import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

public struct IPTVProgramme: Codable, Sendable, Equatable {
    public let title: String
    public let start: Date
    public let end: Date
    public init(title: String, start: Date, end: Date) { self.title = title; self.start = start; self.end = end }
}
public struct IPTVNowNext: Sendable, Equatable {
    public let current: IPTVProgramme?
    public let next: IPTVProgramme?
    public init(current: IPTVProgramme? = nil, next: IPTVProgramme? = nil) { self.current = current; self.next = next }
}
public enum IPTVGuideParser {
    public static func nowNext(_ programmes: [IPTVProgramme], now: Date) -> IPTVNowNext {
        IPTVNowNext(current: programmes.filter { $0.start <= now && $0.end > now }.max { $0.start < $1.start },
                    next: programmes.filter { $0.start > now }.min { $0.start < $1.start })
    }
    public static func xtream(_ data: Data) throws -> [IPTVProgramme] {
        guard data.count <= 2 * 1024 * 1024 else { throw IPTVError.tooLarge }
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let rows = object?["epg_listings"] as? [[String: Any]] ?? []
        return rows.prefix(100).compactMap { row in
            let raw = (row["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let decoded = Data(base64Encoded: raw).flatMap { String(data: $0, encoding: .utf8) }
            let title = String((decoded.flatMap { $0.unicodeScalars.allSatisfy { $0.value >= 32 || $0.value == 10 } ? $0 : nil } ?? raw).prefix(200))
            let start = epoch(row["start_timestamp"]) ?? date(row["start"] as? String ?? "", xml: false)
            let end = epoch(row["stop_timestamp"]) ?? date(row["end"] as? String ?? row["stop"] as? String ?? "", xml: false)
            guard !title.isEmpty, let start, let end, end > start else { return nil }
            return IPTVProgramme(title: title, start: start, end: end)
        }
    }
    private static func epoch(_ value: Any?) -> Date? {
        let seconds = (value as? NSNumber)?.doubleValue ?? (value as? String).flatMap(Double.init)
        guard let seconds, seconds.isFinite, seconds > 0 else { return nil }
        return Date(timeIntervalSince1970: seconds > 100_000_000_000 ? seconds / 1000 : seconds)
    }
    fileprivate static func date(_ text: String, xml: Bool) -> Date? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let format = DateFormatter(); format.locale = Locale(identifier: "en_US_POSIX"); format.isLenient = false
        format.dateFormat = xml ? (value.contains(" ") ? "yyyyMMddHHmmss Z" : "yyyyMMddHHmmss") : "yyyy-MM-dd HH:mm:ss"
        if xml { format.timeZone = TimeZone(secondsFromGMT: 0) }
        return format.date(from: value)
    }
    public static func xmltv(_ data: Data, now: Date) throws -> [String: [IPTVProgramme]] {
        guard data.count <= 20 * 1024 * 1024 else { throw IPTVError.tooLarge }
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16),
              text.range(of: "<!DOCTYPE", options: .caseInsensitive) == nil,
              text.range(of: "<!ENTITY", options: .caseInsensitive) == nil else { throw IPTVError.invalidResponse }
        let delegate = XMLTVReader(now: now)
        let parser = XMLParser(data: Data(text.utf8)); parser.shouldResolveExternalEntities = false; parser.delegate = delegate
        guard parser.parse() else { throw IPTVError.invalidResponse }
        return delegate.programmes
    }
}

private final class XMLTVReader: NSObject, XMLParserDelegate {
    let now: Date
    var programmes: [String: [IPTVProgramme]] = [:]
    private var retained = 0
    private var id = "", title = ""
    private var start: Date?, end: Date?
    private var reading = false, arabic = false
    init(now: Date) { self.now = now }
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        if elementName == "programme" {
            id = String((attributes["channel"] ?? "").prefix(8192)); title = ""; arabic = false
            start = IPTVGuideParser.date(attributes["start"] ?? "", xml: true)
            end = IPTVGuideParser.date(attributes["stop"] ?? "", xml: true)
        } else if elementName == "title" {
            let preferArabic = (attributes["lang"] ?? "").lowercased().hasPrefix("ar")
            reading = title.isEmpty || (preferArabic && !arabic)
            if reading && preferArabic { title = ""; arabic = true }
        }
    }
    func parser(_ parser: XMLParser, foundCharacters value: String) { if reading && title.count < 200 { title += value.prefix(200 - title.count) } }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
        if elementName == "title" { reading = false }
        if elementName == "programme" {
            let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
            if !id.isEmpty, !name.isEmpty, let start, let end, end > start, end > now, start < now.addingTimeInterval(86_400),
               programmes[id] != nil || (programmes.count < 20_000 && retained < 50_000) {
                var entries = programmes[id] ?? []
                if retained < 50_000 || entries.count >= 6 {
                    entries.append(IPTVProgramme(title: name, start: start, end: end)); retained += 1
                    entries.sort { $0.start < $1.start }
                    if entries.count > 6 { entries.removeLast(); retained -= 1 }
                    programmes[id] = entries
                }
            }
            id = ""; reading = false
        }
    }
    func parser(_ parser: XMLParser, resolveExternalEntityName name: String, systemID: String?) -> Data? { parser.abortParsing(); return nil }
}

/// Optional, bounded provider metadata. Shared in-flight work prevents repeated XMLTV downloads while surfing.
public actor IPTVGuideClient {
    private let transport: any IPTVTransport
    private struct Entry { let at: Date; let ttl: TimeInterval; let values: [String: [IPTVProgramme]] }
    private var cache: [String: Entry] = [:]
    private var pending: [String: Task<[String: [IPTVProgramme]], Error>] = [:]
    private var notBefore: [String: Date] = [:]
    public init(transport: any IPTVTransport) { self.transport = transport }
    public func load(source: IPTVSource, channel: IPTVChannel, epgURL: URL?, now: Date = Date()) async throws -> IPTVNowNext {
        guard channel.sourceID == source.id else { return IPTVNowNext() }
        let sourceKey = IPTVURLPolicy.identifier(source: source.id, metadata: source.address.absoluteString + "|" + source.username + "|" + source.password + "|" + String(source.allowHTTP))
        guard notBefore[sourceKey].map({ $0 > now }) != true else { return IPTVNowNext() }
        let key: String, channelKey: String, url: URL, ttl: TimeInterval, isXML: Bool
        if source.kind == .xtream {
            let stream = channel.url.deletingPathExtension().lastPathComponent
            guard stream.range(of: "^[1-9][0-9]{0,18}$", options: .regularExpression) != nil else { return IPTVNowNext() }
            guard var parts = URLComponents(url: try IPTVClient.apiURL(source: source, action: "get_short_epg"), resolvingAgainstBaseURL: false) else { return IPTVNowNext() }
            parts.queryItems = (parts.queryItems ?? []) + [URLQueryItem(name: "stream_id", value: stream), URLQueryItem(name: "limit", value: "6")]
            parts.percentEncodedQuery = parts.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
            guard let endpoint = parts.url else { return IPTVNowNext() }
            url = endpoint; channelKey = stream; key = sourceKey + ":" + stream; ttl = 60; isXML = false
        } else {
            guard !channel.tvgID.isEmpty, let epgURL else { return IPTVNowNext() }
            url = try IPTVURLPolicy.validate(epgURL, allowHTTP: source.allowHTTP)
            channelKey = channel.tvgID; key = sourceKey + ":xml:" + url.absoluteString; ttl = 600; isXML = true
        }
        if let entry = cache[key], now >= entry.at && now.timeIntervalSince(entry.at) < entry.ttl {
            return IPTVGuideParser.nowNext(entry.values[channelKey] ?? [], now: now)
        }
        let task: Task<[String: [IPTVProgramme]], Error>
        if let existing = pending[key] { task = existing }
        else {
            let transport = self.transport
            task = Task {
                let data = try await transport.data(from: url)
                return try isXML ? IPTVGuideParser.xmltv(data, now: now) : [channelKey: IPTVGuideParser.xtream(data)]
            }
            pending[key] = task
        }
        defer { pending[key] = nil }
        do {
            let values = try await task.value
            cache[key] = Entry(at: now, ttl: ttl, values: values)
            while cache.count > 48, let oldest = cache.min(by: { $0.value.at < $1.value.at })?.key { cache[oldest] = nil }
            try Task.checkCancellation()
            return IPTVGuideParser.nowNext(values[channelKey] ?? [], now: now)
        } catch is CancellationError { throw CancellationError() }
        catch {
            cache[key] = Entry(at: now, ttl: 60, values: [:])
            if case IPTVError.http(429) = error {
                notBefore[sourceKey] = now.addingTimeInterval(60)
                notBefore = notBefore.filter { $0.value > now }
            }
            return IPTVNowNext()
        }
    }
}
