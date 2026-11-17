import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

public enum IPTVPopupPlacement {
    public static func above(window: CGSize, anchor: CGRect, popup: CGSize, margin: CGFloat = 32, gap: CGFloat = 10) -> (origin: CGPoint, pivot: CGFloat) {
        let x = max(margin, min(anchor.midX - popup.width / 2, max(margin, window.width - margin - popup.width)))
        let y = max(margin, min(anchor.minY - gap - popup.height, max(margin, window.height - margin - popup.height)))
        let pivot = popup.width > 0 ? max(0.08, min(0.92, (anchor.midX - x) / popup.width)) : 0.5
        return (CGPoint(x: x, y: y), pivot)
    }
}
