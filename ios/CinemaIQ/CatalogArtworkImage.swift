import SwiftUI
import ImageIO

/// Decode only enough pixels for the current viewport. Reopening a tab can reuse its thumbnails.
actor CatalogThumbnails {
    static let shared = CatalogThumbnails()
    private let images = NSCache<NSString, CGImage>()

    private init() {
        images.totalCostLimit = 48 * 1024 * 1024
    }

    func image(at url: URL, pixels: Int) async throws -> CGImage {
        let key = "\(pixels):\(url.absoluteString)" as NSString
        try Task.checkCancellation()
        if let image = images.object(forKey: key) { return image }
        let request = URLRequest(url: url, timeoutInterval: 10)
        let (data, response) = try await URLSession.shared.data(for: request)
        try Task.checkCancellation()
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        if let image = images.object(forKey: key) { return image }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: pixels
              ] as CFDictionary) else { throw URLError(.cannotDecodeContentData) }
        images.setObject(image, forKey: key, cost: image.bytesPerRow * image.height)
        return image
    }
}

/// A small remote picture (cast portraits, channel logos) that shares the decoded-image cache with the posters
/// instead of downloading again every time its row scrolls back into view.
struct CachedRemoteImage<Placeholder: View>: View {
    let url: URL?
    var pixels: Int = 256
    var contentMode: ContentMode = .fit
    @ViewBuilder let placeholder: () -> Placeholder
    @Environment(\.displayScale) private var scale
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: scale)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            image = nil
            guard let url else { return }
            image = try? await CatalogThumbnails.shared.image(at: url, pixels: pixels)
        }
    }
}

struct CatalogArtworkImage<Content: View>: View {
    let urls: [String]
    let size: CGSize
    @ViewBuilder let content: (AsyncImagePhase) -> Content
    @Environment(\.displayScale) private var scale
    @State private var displayed: (url: URL, image: CGImage)?
    @State private var failed = false

    private struct Request: Equatable {
        let urls: [URL]
        let pixels: Int
    }

    var body: some View {
        // Buckets share decoded images across small changes to the window or tab bar.
        let longest = max(size.width, size.height) * scale
        let pixels = [256, 512, 1024, 1536, 2048].first { CGFloat($0) >= longest } ?? 2048
        let request = Request(urls: urls.compactMap { URL(string: $0) }.filter { $0.scheme != nil }, pixels: pixels)
        // Empty artwork phases can produce an EmptyView; keep the loader mounted independently.
        Color.clear.overlay {
            if let displayed, request.urls.contains(displayed.url) {
                content(.success(Image(decorative: displayed.image, scale: scale)))
            } else if failed || request.urls.isEmpty {
                content(.failure(URLError(.resourceUnavailable)))
            } else {
                content(.empty)
            }
        }
        .task(id: request) {
            failed = false
            for url in request.urls {
                do {
                    let image = try await CatalogThumbnails.shared.image(at: url, pixels: request.pixels)
                    try Task.checkCancellation()
                    displayed = (url, image)
                    return
                } catch {
                    if Task.isCancelled { return }
                }
            }
            if !Task.isCancelled { displayed = nil; failed = true }
        }
    }
}
