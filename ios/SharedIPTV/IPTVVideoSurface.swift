import SwiftUI
import AVFoundation

/// Shared AVPlayer layer; controls belong to the IPTV interface so OK never conflicts with AVKit's pause action.
struct IPTVVideoSurface: UIViewRepresentable {
    let player: AVPlayer
    final class Surface: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var video: AVPlayerLayer { layer as! AVPlayerLayer }
    }
    func makeUIView(context: Context) -> Surface {
        let view = Surface(); view.backgroundColor = .black
        view.video.videoGravity = .resizeAspect; view.video.player = player
        view.isUserInteractionEnabled = false
        return view
    }
    func updateUIView(_ view: Surface, context: Context) { if view.video.player !== player { view.video.player = player } }
    static func dismantleUIView(_ view: Surface, coordinator: ()) { view.video.player = nil }
}
