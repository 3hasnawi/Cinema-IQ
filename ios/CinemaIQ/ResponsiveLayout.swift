import SwiftUI
import UIKit

/// Tablet layouts follow the current tab/window bounds, including a narrowed multitasking window.
/// Home shelves size their cards against the remaining column after sidebar and window changes.
struct ContentLayout {
    var size: CGSize = .zero
    var isTablet = false
    var spacious: Bool { isTablet && size.width >= 700 }
    var sideInset: CGFloat { spacious ? 32 : 18 }
    var detailInset: CGFloat { spacious ? 32 : 20 }
    var posterWidth: CGFloat { spacious ? 164 : 118 }
    var continueWidth: CGFloat {
        let count: CGFloat = spacious ? 3 : 2
        return min(340, max(132, (size.width - 2 * sideInset - (count - 1) * 12) / count))
    }
    var readableWidth: CGFloat { spacious ? 1000 : .infinity }
    var formWidth: CGFloat { isTablet ? 860 : .infinity }
    var episodeImageWidth: CGFloat { spacious ? 220 : 150 }
    /// Narrow portrait windows include Split View on iPad. A rotated phone gets a wide hero.
    var usesPortraitHero: Bool { size.width < 600 && size.height > size.width }
    var heroHeight: CGFloat {
        let width = size.width - (isTablet ? 2 * sideInset : 0)
        if usesPortraitHero { return max(260, min(width * 1.5, size.height * 0.66)) }
        return max(200, min(480, width * 9 / 16, size.height * 0.72))
    }
    var detailHeroHeight: CGFloat { isTablet ? max(300, min(600, size.width * 0.56)) : 430 }
    var panelWidth: CGFloat { isTablet ? min(360, max(200, size.width - 40)) : 300 }
    var panelHeight: CGFloat { max(140, size.height - 120) }
    var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: spacious ? 148 : 104, maximum: spacious ? 210 : 160), spacing: 12)]
    }
}

private struct ContentLayoutKey: EnvironmentKey {
    static let defaultValue = ContentLayout()
}

extension EnvironmentValues {
    var contentLayout: ContentLayout {
        get { self[ContentLayoutKey.self] }
        set { self[ContentLayoutKey.self] = newValue }
    }
}

/// Measure the actual tab or player window; no screen-size constants or orientation requests are needed.
struct ResponsiveContainer<Content: View>: View {
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        GeometryReader { geometry in
            content.environment(\.contentLayout, ContentLayout(size: geometry.size,
                                isTablet: UIDevice.current.userInterfaceIdiom == .pad))
        }
    }
}
