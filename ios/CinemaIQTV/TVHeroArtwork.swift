import SwiftUI
import UIKit

/// Keep all source artwork visible, including portrait posters supplied as backdrops.
/// A dimmed, blurred copy fills the unused sides without stretching or cutting the foreground image.
struct TVHeroArtwork: View {
    let url: String
    var fallbackURL = ""
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                TVTheme.ink
#if DEBUG
                if ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-hero-artwork") {
                    fitted(Image(uiImage: Self.portraitFixture), size: geometry.size)
                } else {
                    remote(size: geometry.size)
                }
#else
                remote(size: geometry.size)
#endif
            }.frame(width: geometry.size.width, height: geometry.size.height).clipped()
        }
        .allowsHitTesting(false).accessibilityHidden(true)
    }
    private func remote(size: CGSize) -> some View {
        AsyncImage(url: URL(string: url)) { phase in
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
            image.resizable().scaledToFill().frame(width: size.width, height: size.height)
                .clipped().blur(radius: 30).opacity(0.28)
            image.resizable().scaledToFit().frame(width: size.width, height: size.height)
        }
    }
#if DEBUG
    /// Colored edge markers make unintended aspect-fill cropping detectable in real simulator pixels.
    private static let portraitFixture = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 900)).image { context in
        UIColor(white: 0.12, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 0, width: 600, height: 900))
        UIColor.systemPink.setFill(); context.fill(CGRect(x: 0, y: 0, width: 600, height: 90))
        UIColor.systemGreen.setFill(); context.fill(CGRect(x: 0, y: 810, width: 600, height: 90))
        UIColor.systemYellow.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 180, y: 120, width: 240, height: 240))
    }
#endif
}
