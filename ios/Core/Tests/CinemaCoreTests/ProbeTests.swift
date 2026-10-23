import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
final class ProbeTests: XCTestCase {
    func testProbe() async throws {
        let urls = [
            "https://upload.wikimedia.org/wikipedia/commons/7/70/Tos-poster.png",
            "https://upload.wikimedia.org/wikipedia/commons/0/0c/ElephantsDreamPoster.jpg",
            "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg",
            "https://orange.blender.org/wp-content/themes/orange/images/media/gallery/s1_proog.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/e/e8/Elephants_Dream_s5_both.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/a/a8/Tears_of_Steel_poster.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/b/b5/Big_buck_bunny_poster_big.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Big_buck_bunny_poster_big.jpg/1280px-Big_buck_bunny_poster_big.jpg",
            "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4",
            "https://download.blender.org/peach/trailer/trailer_480p.mov",
        ]
        var lines: [String] = []
        for (i, raw) in urls.enumerated() {
            var r = URLRequest(url: URL(string: raw)!); r.setValue("bytes=0-0", forHTTPHeaderField: "Range"); r.timeoutInterval = 15
            r.setValue("CinemaIQ-probe/1.0", forHTTPHeaderField: "User-Agent")
            do {
                let (_, resp) = try await URLSession.shared.data(for: r)
                let h = resp as! HTTPURLResponse
                lines.append("\(i+1)=\(h.statusCode)\((h.value(forHTTPHeaderField: "Content-Type") ?? "-").prefix(5))")
            } catch { lines.append("\(i+1)=ERR") }
        }
        XCTFail("PROBE " + lines.joined(separator: " | "))
    }
}
