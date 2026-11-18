import UIKit

/// Owns only the Siri Remote's hardware Play/Pause press. AVKit continues to own
/// Select, scrubbing, menus and Back; no AVPlayerViewController subclass is needed.
/// https://developer.apple.com/library/archive/documentation/General/Conceptual/AppleTV_PG/DetectingButtonPressesandGestures.html
@MainActor final class TVMoviePlayPauseAdapter: NSObject, UIGestureRecognizerDelegate {
    private let action: @MainActor () -> Void
    private lazy var recognizer: UITapGestureRecognizer = {
        let value = UITapGestureRecognizer(target: self, action: #selector(toggle(_:)))
        value.allowedPressTypes = [NSNumber(value: UIPress.PressType.playPause.rawValue)]
        value.cancelsTouchesInView = true
        value.delegate = self
        return value
    }()

    init(action: @escaping @MainActor () -> Void) { self.action = action; super.init() }

    func install(on view: UIView) {
        guard recognizer.view !== view else { return }
        uninstall()
        view.addGestureRecognizer(recognizer)
    }

    func uninstall() { recognizer.view?.removeGestureRecognizer(recognizer) }

    @objc private func toggle(_ sender: UITapGestureRecognizer) {
        guard sender.state == .ended else { return }
        action()
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive press: UIPress) -> Bool {
        press.type == .playPause
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // The competing recognizer must wait for ours to fail, rather than also
        // toggling AVKit's never-played item. Other press types never activate ours.
        // https://developer.apple.com/documentation/uikit/uigesturerecognizerdelegate
        gestureRecognizer === recognizer && otherGestureRecognizer.allowedPressTypes.contains(
            NSNumber(value: UIPress.PressType.playPause.rawValue))
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        false
    }
}
