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
    public var quality = ""
    public init(id: String, title: String) { self.id = id; self.title = title }
    public var isSupported: Bool { (try? ContentSource.owner(of: id)) != nil }
    public var recentKey: String { "recent/" + (seriesID.isEmpty ? id : seriesID) }
    public var subtitle: String { episode > 0 ? "الموسم \(season) · الحلقة \(episode)" : [year, duration, quality].filter { !$0.isEmpty }.joined(separator: " · ") }
    enum CodingKeys: String, CodingKey {
        case id = "nb", title = "ar_title", poster = "imgMediumThumbObjUrl", backdrop = "imgObjUrl"
        case overview = "ar_content", year, duration, rating = "stars", kind, season, episode = "episodeNummer"
        case seriesID = "seriesId", episodeTitle, quality = "contentQuality"
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
        seriesID = text(.seriesID); episodeTitle = text(.episodeTitle); quality = text(.quality)
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
public enum CinemaError: LocalizedError {
    case invalidID, invalidResponse, http(Int), unsupportedVideo, unsupportedSource, noStreams
    public var errorDescription: String? {
        switch self {
        case .invalidID, .invalidResponse: return "تعذّر قراءة بيانات العمل من المصدر."
        case .http: return "تعذّر الاتصال بالمصدر. تأكد من أن شبكتك تدعمه وأعد المحاولة."
        case .unsupportedVideo: return "صيغة هذه النسخة غير مدعومة بالمشغّل الأولي للآيفون. جرّب عملاً بصيغة MP4."
        case .noStreams: return "لم يوفّر المصدر رابط تشغيل متاحاً لهذا العمل."
        case .unsupportedSource: return "مصدر هذا العمل غير معروف."
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
