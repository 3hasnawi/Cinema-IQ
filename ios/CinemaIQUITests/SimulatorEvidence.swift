import Foundation
import XCTest

/// Keep real simulator evidence even when an interrupted xcodebuild cannot finish its xcresult bundle.
/// The workflow copies this UI test runner's Caches directory after the test step.
enum SimulatorEvidence {
    static func screenshot(_ capture: XCUIScreenshot, test: String, name: String) {
        guard let file = destination(test: test, name: name, fileExtension: "png") else { return }
        try? capture.pngRepresentation.write(to: file, options: .atomic)
    }

    static func hierarchy(_ text: String, test: String) {
        guard let file = destination(test: test, name: "hierarchy", fileExtension: "txt") else { return }
        try? text.write(to: file, atomically: true, encoding: .utf8)
    }

    private static func destination(test: String, name: String, fileExtension suffix: String) -> URL? {
        let manager = FileManager.default
        guard let cache = manager.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let directory = cache.appendingPathComponent("CinemaUITestEvidence", isDirectory: true)
        do {
            try manager.createDirectory(at: directory, withIntermediateDirectories: true)
            let filename = "\(safe(test, limit: 120))--\(safe(name, limit: 80))--\(UUID().uuidString.prefix(8)).\(suffix)"
            return directory.appendingPathComponent(filename)
        } catch {
            // Evidence persistence must not replace the actual UI assertion with an I/O failure.
            return nil
        }
    }

    private static func safe(_ value: String, limit: Int) -> String {
        String(value.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? String($0) : "_" }
            .joined().prefix(limit))
    }
}
