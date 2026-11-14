import Foundation

/// Redacted failure categories. Network/auth/protected-content errors must not trigger a decoder retry.
public enum PlaybackFailure: Equatable, Sendable {
    case unsupported, unauthorized, missing, rateLimited, server, network, timeout, secureConnection, protectedContent, unknown
    public var permitsDecoderFallback: Bool { self == .unsupported }
    public var message: String {
        switch self {
        case .unsupported: return "صيغة الفيديو أو الصوت غير مدعومة في مشغل Apple. جرّب محرك التوافق الموسّع."
        case .unauthorized: return "رفض الخادم التشغيل. تحقق من الاشتراك أو حد الأجهزة، ثم حدّث الرابط."
        case .missing: return "رابط البث غير متاح أو انتهت صلاحيته. حدّث القنوات ثم أعد المحاولة."
        case .rateLimited: return "طلبات كثيرة إلى المصدر. انتظر قليلًا قبل إعادة المحاولة."
        case .server: return "خادم البث غير متاح حاليًا. أعد المحاولة لاحقًا."
        case .network: return "تعذّر الاتصال بخادم البث. تحقق من الشبكة وعنوان المصدر."
        case .timeout: return "انتهت مهلة الاتصال بخادم البث. تحقق من الشبكة وأعد المحاولة."
        case .secureConnection: return "تعذّر إنشاء اتصال آمن بخادم البث. تحقق من عنوان المصدر وشهادته."
        case .protectedContent: return "المحتوى محمي أو غير مصرّح بتشغيله على هذا الجهاز."
        case .unknown: return "تعذّر تشغيل البث. حدّث الرابط وتحقق من المصدر، أو جرّب محرك التوافق الموسّع."
        }
    }
    public static func classify(_ error: Error?, httpStatus: Int? = nil) -> Self {
        if let httpStatus {
            switch httpStatus {
            case 401, 403: return .unauthorized
            case 404, 410: return .missing
            case 429: return .rateLimited
            case 500...599: return .server
            default: break
            }
        }
        var current = error as NSError?
        var nativeUnsupported = false
        for _ in 0..<8 {
            guard let value = current else { break }
            if value.domain == NSURLErrorDomain {
                switch value.code {
                case -1001: return .timeout
                case -1003, -1004, -1005, -1006, -1009: return .network
                case -1206 ... -1200: return .secureConnection
                default: break
                }
            }
            if value.domain == "AVFoundationErrorDomain" {
                if [-11831, -11835].contains(value.code) { return .protectedContent }
                if [-11821, -11828, -11829, -11833].contains(value.code) { nativeUnsupported = true }
            }
            current = value.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return nativeUnsupported ? .unsupported : .unknown
    }
}

public enum IPTVPlaybackMode: String, CaseIterable, Sendable {
    case automatic, apple, compatibility
    public var title: String {
        switch self { case .automatic: return "تلقائي"; case .apple: return "مشغل Apple"; case .compatibility: return "توافق موسّع" }
    }
    public func startsWithCompatibility(_ url: URL) -> Bool {
        self == .compatibility || (self == .automatic && ["mkv", "avi", "ts", "m2ts", "mts", "flv", "wmv", "asf", "webm", "ogv", "vob"].contains(url.pathExtension.lowercased()))
    }
}
