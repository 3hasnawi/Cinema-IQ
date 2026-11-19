import SwiftUI
import UIKit

/// A native keyboard can change the text responder while Siri Remote focus returns to the old row.
/// Use the laid-out field's position to direct UIKit's separate focus system after the editor closes.
@MainActor final class TVIPTVRemoteFieldFocus: ObservableObject {
    private let anchors = NSMapTable<NSString, UIView>.strongToWeakObjects()

    func register(_ view: UIView, id: String) { anchors.setObject(view, forKey: id as NSString) }

    func restore(_ id: String) -> Bool {
        guard let anchor = anchors.object(forKey: id as NSString), let window = anchor.window,
              let system = UIFocusSystem(for: anchor) else { return false }
        let rect = anchor.convert(anchor.bounds, to: window)
        guard !rect.isEmpty, window.bounds.intersects(rect) else { return false }
        // The SwiftUI field may live inside a focusable Form cell. Prefer the actual native input.
        var candidates: [UIView] = []
        func visit(_ view: UIView) {
            guard !view.isHidden && view.alpha > 0.01 else { return }
            let frame = view.convert(view.bounds, to: window)
            guard frame.intersects(rect) else { return }
            if view.canBecomeFocused && rect.contains(CGPoint(x: frame.midX, y: frame.midY)) { candidates.append(view) }
            for child in view.subviews { visit(child) }
        }
        visit(window)
        guard let target = candidates.first(where: { $0 is UITextField }) ?? candidates.last else { return false }
        if target.isFocused { return true }
        system.requestFocusUpdate(to: target)
        system.updateFocusIfNeeded()
        return target.isFocused || (system.focusedItem as? UIView)?.isDescendant(of: target) == true
    }
}

struct TVIPTVFieldAnchor: UIViewRepresentable {
    let id: String
    let registry: TVIPTVRemoteFieldFocus
    func makeUIView(context: Context) -> UIView {
        let anchor = UIView()
        anchor.isUserInteractionEnabled = false
        anchor.accessibilityElementsHidden = true
        return anchor
    }
    func updateUIView(_ uiView: UIView, context: Context) { registry.register(uiView, id: id) }
}
