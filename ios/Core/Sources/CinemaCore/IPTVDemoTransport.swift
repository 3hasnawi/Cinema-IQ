#if DEBUG
import Foundation

/// Dummy hosts/accounts only. A separate Keychain service is used by IPTVStore in this mode.
public struct IPTVDemoTransport: IPTVTransport {
    public init() {}
    public func data(from url: URL) async throws -> Data {
        try Task.checkCancellation()
        guard ["iptv.example", "xtream.example"].contains(url.host ?? "") else { throw IPTVError.invalidURL }
        if url.host == "iptv.example" {
            return Data("""
            #EXTM3U
            #EXTINF:-1 tvg-id=news group-title="أخبار",قناة الأخبار
            https://media.example/news.m3u8
            #EXTINF:-1 tvg-id=sport group-title="رياضة",قناة الرياضة
            https://media.example/sport.m3u8
            #EXTINF:-1 tvg-id=documentary group-title="وثائقيات",قناة الوثائقيات
            https://media.example/documentary.m3u8
            #EXTINF:-1 tvg-id=kids group-title="أطفال",قناة الأطفال
            https://media.example/kids.m3u8
            #EXTINF:-1 tvg-id=entertainment group-title="ترفيه",قناة الترفيه
            https://media.example/entertainment.m3u8
            #EXTINF:-1 tvg-id=culture group-title="ثقافة",قناة الثقافة
            https://media.example/culture.m3u8
            """.utf8)
        }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let action = items.first { $0.name == "action" }?.value
        switch action {
        case "get_live_categories": return Data(#"[{"category_id":"1","category_name":"أخبار"},{"category_id":"2","category_name":"رياضة"}]"#.utf8)
        case "get_live_streams": return Data(#"[{"stream_id":101,"name":"Xtream News","category_id":"1"},{"stream_id":102,"name":"Xtream Sport","category_id":"2"}]"#.utf8)
        default:
            let valid = items.first { $0.name == "username" }?.value == "demo" && items.first { $0.name == "password" }?.value == "demo"
            return Data((valid ? #"{"user_info":{"auth":1,"status":"Active"}}"# : #"{"user_info":{"auth":0}}"#).utf8)
        }
    }
}
#endif
