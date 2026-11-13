import SwiftUI
import UIKit

/// Tablet layouts follow the current tab/window bounds, including a narrowed multitasking window.
/// Phone measurements retain the existing appearance.
struct ContentLayout {
    var size: CGSize = .zero
    var isTablet = false
    var spacious: Bool { isTablet && size.width >= 700 }
    var sideInset: CGFloat { spacious ? 32 : 18 }
    var detailInset: CGFloat { spacious ? 32 : 20 }
    var posterWidth: CGFloat { spacious ? 164 : 118 }
    var continueWidth: CGFloat { spacious ? 320 : 250 }
    var readableWidth: CGFloat { spacious ? 1000 : .infinity }
    var formWidth: CGFloat { isTablet ? 860 : .infinity }
    var episodeImageWidth: CGFloat { spacious ? 220 : 150 }
    var heroHeight: CGFloat {
        isTablet ? max(300, min(660, min(size.width * 0.75, size.height * 0.8))) : 560
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
