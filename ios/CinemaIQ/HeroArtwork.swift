import SwiftUI
import UIKit

/// Keep the entire hero artwork visible on both tall phone and wide tablet frames.
/// A dimmed copy fills the unused space while the foreground keeps its original aspect ratio.
struct HeroArtwork: View {
    let url: String
    var fallbackURL = ""

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
        AsyncImage(url: url.isEmpty ? nil : URL(string: url), transaction: Transaction(animation: .easeOut(duration: 0.25))) { phase in
            if let image = phase.image { fitted(image, size: size) }
            else if !fallbackURL.isEmpty && fallbackURL != url {
                AsyncImage(url: URL(string: fallbackURL)) { fallback in
                    if let image = fallback.image { fitted(image, size: size) }
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
