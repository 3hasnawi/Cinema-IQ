import SwiftUI
import UIKit

/// A transparent native focus target. Horizontal input belongs to the carousel, not the tab sidebar.
struct TVCarouselFocusSurface: UIViewRepresentable {
    @Binding var focused: Bool
    let title: String
    let position: String
    let activate: () -> Void
    let move: (Int) -> Void

    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.isOpaque = false
        view.backgroundColor = .clear
        view.isAccessibilityElement = true
        view.accessibilityTraits = .button
        view.accessibilityIdentifier = "tv.home.showcase"
        view.accessibilityHint = "يمين ويسار لتغيير العمل، واضغط للاطلاع على التفاصيل، وأسفل لعرض القوائم."
        return view
    }

    func updateUIView(_ view: Surface, context: Context) {
        view.accessibilityLabel = title
        view.accessibilityValue = position
        view.activate = activate
        view.move = move
        view.focusChanged = { value in
            if focused != value { focused = value }
        }
        view.wantsFocus = focused
        view.requestFocusIfNeeded()
    }

    static func dismantleUIView(_ view: Surface, coordinator: ()) {
        view.activate = nil
        view.move = nil
        view.focusChanged = nil
        view.wantsFocus = false
    }

    final class Surface: UIView {
        var activate: (() -> Void)?
        var move: ((Int) -> Void)?
        var focusChanged: ((Bool) -> Void)?
        var wantsFocus = false
        private var selecting = false
        override var canBecomeFocused: Bool { true }

        override init(frame: CGRect) {
            super.init(frame: frame)
            let directions: [UISwipeGestureRecognizer.Direction] = [.left, .right]
            for direction in directions {
                let swipe = UISwipeGestureRecognizer(target: self, action: #selector(swiped(_:)))
                swipe.direction = direction
                swipe.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.indirect.rawValue)]
                addGestureRecognizer(swipe)
            }
        }
        required init?(coder: NSCoder) { return nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            requestFocusIfNeeded()
        }
        override func layoutSubviews() {
            super.layoutSubviews()
            requestFocusIfNeeded()
        }
        func requestFocusIfNeeded() {
            guard wantsFocus, !isFocused, window != nil, !bounds.isEmpty,
                  let system = UIFocusSystem.focusSystem(for: self) else { return }
            system.requestFocusUpdate(to: self)
            system.updateFocusIfNeeded()
        }
        override func shouldUpdateFocus(in context: UIFocusUpdateContext) -> Bool {
            if context.previouslyFocusedView === self,
               context.focusHeading.contains(.left) || context.focusHeading.contains(.right) {
                // SwiftUI's onMoveCommand also let the native tab sidebar take focus on Right.
                // Keep horizontal focus here; presses/swipes below change only the selected work.
                return false
            }
            return super.shouldUpdateFocus(in: context)
        }
        override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
            super.didUpdateFocus(in: context, with: coordinator)
            guard context.previouslyFocusedView === self || context.nextFocusedView === self else { return }
            let active = context.nextFocusedView === self
            wantsFocus = active
            if !active { selecting = false }
            focusChanged?(active)
        }
        override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            let remaining = Set(presses.filter { press in
                switch press.type {
                case .leftArrow: move?(1); return false
                case .rightArrow: move?(-1); return false
                case .select: selecting = true; return false
                default: return true
                }
            })
            if !remaining.isEmpty { super.pressesBegan(remaining, with: event) }
        }
        override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            let remaining = Set(presses.filter { press in
                switch press.type {
                case .leftArrow, .rightArrow: return false
                case .select:
                    if selecting { selecting = false; activate?() }
                    return false
                default: return true
                }
            })
            if !remaining.isEmpty { super.pressesEnded(remaining, with: event) }
        }
        override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            selecting = false
            super.pressesCancelled(presses, with: event)
        }
        override func accessibilityActivate() -> Bool {
            activate?()
            return true
        }
        @objc private func swiped(_ gesture: UISwipeGestureRecognizer) {
            guard isFocused else { return }
            move?(gesture.direction == .left ? 1 : -1)
        }
    }
}
