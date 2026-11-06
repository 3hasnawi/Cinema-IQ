import Foundation

/// The default receiver fetches original URLs itself. No cookies, tokens or custom headers are forwarded.
public enum CastMediaPolicy {
    public static func valid(_ url: URL) -> Bool {
        ["http", "https"].contains(url.scheme?.lowercased() ?? "") &&
        !(url.host ?? "").isEmpty && url.user == nil && url.password == nil
    }
    public static func mime(_ url: URL) -> String? {
        guard valid(url) else { return nil }
        let ext = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first(where: { $0.name == "ext" })?.value ?? url.pathExtension.lowercased()
        switch ext.lowercased() {
        case "m3u8": return "application/x-mpegURL"
        case "mpd": return "application/dash+xml"
        case "mkv", "avi", "mov": return nil
        default: return "video/mp4"
        }
    }
    public static func subtitle(_ track: SubtitleTrack) -> Bool {
        valid(track.url) && track.url.pathExtension.lowercased() == "vtt"
    }
}
