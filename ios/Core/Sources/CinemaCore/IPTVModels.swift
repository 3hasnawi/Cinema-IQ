import Foundation

public enum IPTVError: Error, LocalizedError, Equatable {
    case invalidURL, insecureConnection, invalidServer, missingCredentials, invalidPlaylist
    case invalidResponse, invalidCredentials, inactiveSubscription, noChannels, tooLarge, redirected
    case http(Int), network(Int)
    public var errorDescription: String? {
        switch self {
        case .invalidURL: return "أدخل رابط HTTP أو HTTPS صالحًا، دون بيانات دخول داخل اسم الخادم."
        case .insecureConnection: return "هذا الرابط يستخدم HTTP. فعّل السماح بالاتصال غير المشفّر لهذا المصدر أو استخدم HTTPS."
        case .invalidServer: return "أدخل عنوان خادم Xtream فقط، دون get.php أو player_api.php أو معاملات الرابط."
        case .missingCredentials: return "أدخل اسم المستخدم وكلمة المرور للاشتراك."
        case .invalidPlaylist: return "الرابط لا يحتوي قائمة M3U صالحة. تحقق من رابط قائمة القنوات."
        case .invalidResponse: return "أعاد خادم IPTV بيانات غير صالحة. تحقق من عنوان الخادم."
        case .invalidCredentials: return "اسم المستخدم أو كلمة المرور غير صحيحين."
        case .inactiveSubscription: return "الاشتراك منتهي أو غير مفعّل. راجع مزوّد الاشتراك."
        case .noChannels: return "لم يوفّر المصدر قنوات بروابط متوافقة."
        case .tooLarge: return "قائمة القنوات أكبر من الحد المدعوم. جرّب قائمة أصغر."
        case .redirected: return "نقل المصدر طلب القائمة إلى خادم مختلف. استخدم رابط القائمة المباشر."
        case .http(401), .http(403): return "رفض خادم IPTV الاتصال. تحقق من الاشتراك وبيانات الدخول والشبكة."
        case .http(404): return "لم يجد الخادم قائمة القنوات. تحقق من الرابط."
        case .http(429): return "المصدر يطلب الانتظار لكثرة الطلبات. أعد المحاولة لاحقًا."
        case .http: return "تعذّر الاتصال بخادم IPTV. أعد المحاولة بعد قليل."
        case .network(let code):
            if [-1200, -1201, -1202, -1203, -1204, -1205, -1206].contains(code) { return "تعذّر التحقق من الاتصال الآمن بخادم IPTV." }
            if code == -1001 { return "انتهت مهلة الاتصال بخادم IPTV."
            }
            return "تعذّر الاتصال بخادم IPTV. تحقق من الشبكة وعنوان الخادم."
        }
    }
}

/// Credentials are encoded only by the app's Keychain vault, never by library/cloud storage.
public struct IPTVSource: Codable, Identifiable, Sendable, Equatable, CustomStringConvertible {
    public enum Kind: String, Codable, Sendable { case playlist, xtream }
    public enum Output: String, Codable, CaseIterable, Sendable { case hls = "m3u8", transportStream = "ts" }
    public let id: String
    public var name: String
    public var kind: Kind
    public var address: URL
    public var username: String
    public var password: String
    public var output: Output
    public var allowHTTP: Bool
    public init(id: String = UUID().uuidString, name: String, kind: Kind, address: URL,
                username: String = "", password: String = "", output: Output = .hls, allowHTTP: Bool = false) throws {
        _ = try IPTVURLPolicy.validate(address, allowHTTP: allowHTTP)
        if kind == .xtream {
            guard address.query == nil, !["get.php", "player_api.php"].contains(address.lastPathComponent.lowercased()) else { throw IPTVError.invalidServer }
            guard !username.isEmpty, !password.isEmpty else { throw IPTVError.missingCredentials }
        }
        self.id = id; self.name = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        if self.name.isEmpty { self.name = kind == .playlist ? "قائمة قنوات" : "اشتراك Xtream" }
        self.kind = kind; self.address = address; self.username = username; self.password = password
        self.output = output; self.allowHTTP = allowHTTP
    }
    public var description: String { "IPTVSource(id: \(id), kind: \(kind.rawValue))" }
}

public struct IPTVChannel: Identifiable, Sendable, Equatable {
    public let id: String
    public let sourceID: String
    public let name: String
    public let group: String
    public let tvgID: String
    public let logo: URL?
    public let url: URL
    public init(id: String, sourceID: String, name: String, group: String, tvgID: String = "", logo: URL? = nil, url: URL) {
        self.id = id; self.sourceID = sourceID; self.name = name; self.group = group
        self.tvgID = tvgID; self.logo = logo; self.url = url
    }
}
public struct IPTVCatalog: Sendable {
    public let channels: [IPTVChannel]
    public var groups: [String] { Array(Set(channels.map(\.group))).sorted { $0.localizedStandardCompare($1) == .orderedAscending } }
    public init(channels: [IPTVChannel]) { self.channels = channels }
}
public enum IPTVURLPolicy {
    @discardableResult public static func validate(_ url: URL, allowHTTP: Bool) throws -> URL {
        guard let scheme = url.scheme?.lowercased(), ["https", "http"].contains(scheme),
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil, url.fragment == nil,
              url.port.map({ (1...65535).contains($0) }) ?? true else { throw IPTVError.invalidURL }
        if scheme == "http" && !allowHTTP { throw IPTVError.insecureConnection }
        return url
    }
    public static func stream(_ raw: String, relativeTo base: URL, allowHTTP: Bool) -> URL? {
        let raw = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty,
              let url = URL(string: raw, relativeTo: base)?.absoluteURL else { return nil }
        return try? validate(url, allowHTTP: allowHTTP)
    }
    static func sameOrigin(_ from: URL, _ to: URL) -> Bool {
        guard from.host?.lowercased() == to.host?.lowercased() else { return false }
        let origin = from.scheme?.lowercased(), destination = to.scheme?.lowercased()
        let a = from.port ?? (origin == "https" ? 443 : 80)
        let b = to.port ?? (destination == "https" ? 443 : 80)
        return (origin == destination && a == b) || (origin == "http" && destination == "https" && b == 443)
    }
    /// Stable, opaque IDs from public channel metadata, never from credential-bearing stream URLs.
    static func identifier(source: String, metadata: String) -> String {
        var hash: UInt64 = 14695981039346656037
        for byte in metadata.utf8 { hash = (hash ^ UInt64(byte)) &* 1099511628211 }
        return source + ":" + String(hash, radix: 16)
    }
}
