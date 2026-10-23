import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
final class ProbeTests: XCTestCase {
    func testProbe() async throws {
        let urls = [
            "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4",
            "https://download.blender.org/peach/bigbuckbunny_movies/big_buck_bunny_720p_h264.mov",
            "https://download.blender.org/durian/movies/sintel-1024-surround.mp4",
            "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov",
            "https://download.blender.org/ED/elephantsdream-720-h264-st-aac.mov",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
            "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
            "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/8/8f/Sintel_poster.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/7/70/Tos-poster.png",
            "https://upload.wikimedia.org/wikipedia/commons/0/0c/ElephantsDreamPoster.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
            "https://peach.blender.org/wp-content/uploads/title_anouncement.jpg",
            "https://durian.blender.org/wp-content/uploads/2010/06/05.8b_comp_000272.jpg",
            "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg",
            "https://orange.blender.org/wp-content/themes/orange/images/media/gallery/s1_proog.jpg",
        ]
        var lines: [String] = []
        for (i, raw) in urls.enumerated() {
            var r = URLRequest(url: URL(string: raw)!); r.setValue("bytes=0-0", forHTTPHeaderField: "Range"); r.timeoutInterval = 15
            r.setValue("CinemaIQ-probe/1.0", forHTTPHeaderField: "User-Agent")
            do {
                let (_, resp) = try await URLSession.shared.data(for: r)
                let h = resp as! HTTPURLResponse
                lines.append("\(i+1)=\(h.statusCode) \(h.value(forHTTPHeaderField: "Content-Type") ?? "-")")
            } catch { lines.append("\(i+1)=ERR") }
        }
        XCTFail("PROBE " + lines.joined(separator: " | "))
    }
}
