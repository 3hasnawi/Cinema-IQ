import SwiftUI
import UIKit

/// Keep the entire hero artwork visible on both tall phone and wide tablet frames.
/// A dimmed copy fills the unused space while the foreground keeps its original aspect ratio.
struct HeroArtwork: View {
    let urls: [String]
    /// Portrait phones fill the whole hero with a poster. Only its top and bottom edges are cropped, and
    /// the dimmed copy is not needed because no side bands remain.
    let fillsFrame: Bool

    init(urls: [String], fillsFrame: Bool = false) {
        self.urls = urls
        self.fillsFrame = fillsFrame
    }

    init(url: String, fallbackURL: String) {
        urls = fallbackURL == url ? [url] : [url, fallbackURL]
        fillsFrame = false
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Theme.ink
#if DEBUG
                if Self.fixtureEnabled {
                    fitted(Image(uiImage: Self.portraitFixture), size: geometry.size)
                } else {
                    remote(size: geometry.size)
                }
#else
                remote(size: geometry.size)
#endif
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .allowsHitTesting(false)
#if DEBUG
        // Identify the deterministic fixture. The pixel test measures the carousel's clipped viewport,
        // since SwiftUI can report an image's pre-clipping bounds to accessibility on iOS 18.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Portrait artwork fixture")
        .accessibilityIdentifier("ios.artwork.fixture.bounds")
        .accessibilityHidden(!Self.fixtureEnabled)
#else
        .accessibilityHidden(true)
#endif
    }

    private func remote(size: CGSize) -> some View {
        CatalogArtworkImage(urls: urls, size: size) { phase in
            if let image = phase.image {
                if fillsFrame {
                    image.resizable().scaledToFill()
                        .frame(width: size.width, height: size.height)
                        .clipped()
                } else {
                    fitted(image, size: size)
                }
            }
        }
    }

    private func fitted(_ image: Image, size: CGSize) -> some View {
        ZStack {
            image.resizable().scaledToFill()
                .frame(width: size.width, height: size.height)
                .clipped().blur(radius: 24).opacity(0.28)
            image.resizable().scaledToFit().frame(width: size.width, height: size.height)
        }
    }

#if DEBUG
    static let fixtureEnabled = ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-hero-artwork")
    /// Tall, colored-edge artwork makes accidental aspect-fill cropping observable in real pixels.
    private static let portraitFixture = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 1200)).image { context in
        UIColor(white: 0.12, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 0, width: 600, height: 1200))
        UIColor.magenta.setFill(); context.fill(CGRect(x: 0, y: 0, width: 600, height: 120))
        UIColor.green.setFill(); context.fill(CGRect(x: 0, y: 1080, width: 600, height: 120))
    }
#endif
}
