import Foundation

public struct Media: Codable, Identifiable, Hashable, Sendable {
    public var id: String
    public var title: String
    public var poster = ""
    public var backdrop = ""
    public var overview = ""
    public var year = ""
    public var duration = ""
    public var rating = ""
    public var isSeries = false
    public var season = 1
    public var episode = 0
    public var seriesID = ""
    public var episodeTitle = ""
    /// Latin title from the source, used to look the work up in OMDb.
    public var enTitle = ""
    public var quality = ""
    /// When an episode was first shown, as the source gives it (usually "2024-05-01").
    public var airDate = ""
    public init(id: String, title: String) { self.id = id; self.title = title }
    public var isSupported: Bool { (try? ContentSource.owner(of: id)) != nil }
    /// From an external provider added in Settings (not synced to the Android apps).
    public var isExternal: Bool { id.hasPrefix("ext:") }
    public var recentKey: String { "recent/" + (seriesID.isEmpty ? id : seriesID) }
    public var subtitle: String { episode > 0 ? "الموسم \(season) · الحلقة \(episode)" : [year, duration, quality].filter { !$0.isEmpty }.joined(separator: " · ") }
    enum CodingKeys: String, CodingKey {
        case id = "nb", title = "ar_title", poster = "imgMediumThumbObjUrl", backdrop = "imgObjUrl"
        case overview = "ar_content", year, duration, rating = "stars", kind, season, episode = "episodeNummer"
        case seriesID = "seriesId", episodeTitle, quality = "contentQuality", enTitle = "en_title", airDate
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func text(_ k: CodingKeys) -> String {
            if let s = try? c.decode(String.self, forKey: k) { return s }
            if let n = try? c.decode(Int.self, forKey: k) { return String(n) }
            if let n = try? c.decode(Double.self, forKey: k) { return String(n) }
            return ""
        }
        id = text(.id); title = text(.title)
        guard !id.isEmpty else { throw CocoaError(.coderReadCorrupt) }
        poster = text(.poster); backdrop = text(.backdrop); overview = text(.overview)
        year = text(.year); duration = text(.duration); rating = text(.rating); isSeries = text(.kind) == "2"
        season = Int(text(.season)) ?? 1; episode = Int(text(.episode)) ?? 0
        seriesID = text(.seriesID); episodeTitle = text(.episodeTitle); quality = text(.quality); enTitle = text(.enTitle)
        airDate = text(.airDate)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id); try c.encode(title, forKey: .title)
        try c.encode(poster, forKey: .poster); try c.encode(backdrop, forKey: .backdrop)
        try c.encode(overview, forKey: .overview); try c.encode(year, forKey: .year)
        try c.encode(duration, forKey: .duration); try c.encode(rating, forKey: .rating)
        try c.encode(isSeries ? 2 : 1, forKey: .kind); try c.encode(season, forKey: .season)
        try c.encode(episode, forKey: .episode); try c.encode(seriesID, forKey: .seriesID)
        try c.encode(episodeTitle, forKey: .episodeTitle); try c.encode(quality, forKey: .quality)
        try c.encode(enTitle, forKey: .enTitle)
        if !airDate.isEmpty { try c.encode(airDate, forKey: .airDate) }
    }
}
public struct Shelf: Identifiable, Sendable {
    public let id: String
    public let title: String
    public let items: [Media]
    public init(id: String, title: String, items: [Media]) { self.id = id; self.title = title; self.items = items }
}
public struct Season: Identifiable, Sendable {
    public let id: Int
    public let episodes: [Media]
    public init(id: Int, episodes: [Media]) { self.id = id; self.episodes = episodes }
}
public enum CinemaError: LocalizedError, Equatable {
    case invalidID, invalidResponse, notJSON, redirected, http(Int), network(Int), unsupportedVideo, unsupportedSource, noStreams
    public var errorDescription: String? {
        switch self {
        case .invalidID, .invalidResponse: return "تعذّر قراءة بيانات العمل من المصدر."
        case .notJSON, .redirected:
            return "أعاد المصدر صفحة غير متوقعة بدل البيانات. قد تكون شبكتك تحجبه أو تحوّله لصفحة أخرى."
        case .http(404): return "لم يجد المصدر هذا المحتوى (HTTP 404)."
        case .http(429): return "المصدر يطلب التمهّل لكثرة الطلبات (HTTP 429). أعد المحاولة بعد قليل."
        case .http(401): return "يحتاج المصدر رمز وصول صالحاً (HTTP 401). راجع إعدادات المصدر."
        case .http(let code):
            return code == 403 || code == 503
                ? "رفض المصدر الطلب من شبكتك الحالية (HTTP \(code)). جرّب الشبكة التي يعمل عليها المصدر."
                : "تعذّر الاتصال بالمصدر (HTTP \(code)). أعد المحاولة."
        case .network(let code): return Self.networkMessage(code) + " (رمز \(code))"
        case .unsupportedVideo: return "صيغة هذه النسخة غير مدعومة بمشغّل الآيفون. جرّب جودة أخرى أو عملاً بصيغة MP4."
        case .noStreams: return "لم يوفّر المصدر رابط تشغيل متاحاً لهذا العمل."
        case .unsupportedSource: return "مصدر هذا العمل غير معروف."
        }
    }
    /// Plain Arabic explanations for the URL loading errors seen most with network-restricted sources.
    static func networkMessage(_ code: Int) -> String {
        switch code {
        case -1009: return "لا يوجد اتصال بالإنترنت."
        case -1001: return "انتهت مهلة الاتصال بالمصدر."
        case -1003, -1004, -1006:
            return "لا يمكن الوصول إلى خادم المصدر من شبكتك الحالية. اتصل بالشبكة التي يعمل عليها المصدر، وأوقف VPN أو «ترحيل iCloud الخاص» إن كان مفعّلاً."
        case -1200, -1201, -1202, -1203, -1204, -1205, -1206, -1022:
            return "تعذّر إنشاء اتصال آمن مع المصدر."
        default: return "تعذّر الاتصال بالمصدر."
        }
    }
}
public struct DawKey: Sendable {
    public let type: String
    public let raw: String
    public let parent: String
    public let ext: String
    public init(_ id: String) throws {
        let p = id.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard p.first == "daw" else { throw CinemaError.unsupportedSource }
        if p.count == 3 && p[1] == "series" { type = "series"; raw = p[2]; parent = ""; ext = "mp4" }
        else if p.count == 4 && p[1] == "movie" { type = "movie"; raw = p[2]; parent = ""; ext = p[3] }
        else if p.count == 5 && p[1] == "episode" { type = "episode"; parent = p[2]; raw = p[3]; ext = p[4] }
        else { throw CinemaError.invalidID }
        guard raw.range(of: "^[A-Za-z0-9_~-]+$", options: .regularExpression) != nil,
              parent.isEmpty || parent.range(of: "^[A-Za-z0-9_~-]+$", options: .regularExpression) != nil,
              ext.range(of: "^[A-Za-z0-9]{1,8}$", options: .regularExpression) != nil else { throw CinemaError.invalidID }
    }
    public var kind: String { type == "movie" ? "movie" : "series" }
    public func streamURL() throws -> URL {
        guard type != "series" else { throw CinemaError.invalidID }
        guard ["mp4", "m4v", "mov", "m3u8"].contains(ext.lowercased()) else { throw CinemaError.unsupportedVideo }
        return DawwirliClient.url("vod", ["kind": kind, "id": raw, "ext": ext])
    }
}

public enum AirDate {
    /// The year of a date such as "2024-05-01" or "2024-05-01 20:00:00"; nil when there is none.
    public static func year(_ raw: String) -> Int? {
        let digits = raw.trimmingCharacters(in: .whitespaces).prefix(4)
        guard digits.count == 4, let year = Int(digits), (1900...2100).contains(year) else { return nil }
        return year
    }
    /// The season's year: its earliest episode date.
    public static func year(of season: Season) -> Int? {
        season.episodes.compactMap { year($0.airDate) }.min()
    }
    /// "1 مايو 2024" (Latin digits, like the rest of the app); the text as it is when it is not a date.
    public static func display(_ raw: String) -> String {
        let text = raw.trimmingCharacters(in: .whitespaces)
        guard text.count >= 10 else { return text.count == 4 ? text : "" }
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.timeZone = TimeZone(identifier: "UTC")
        parser.dateFormat = "yyyy-MM-dd"
        guard let date = parser.date(from: String(text.prefix(10))) else { return "" }
        let output = DateFormatter()
        output.locale = Locale(identifier: "ar@numbers=latn")
        output.timeZone = TimeZone(identifier: "UTC")
        output.dateFormat = "d MMMM yyyy"
        return output.string(from: date)
    }
}
