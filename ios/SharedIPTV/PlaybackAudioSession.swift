import AVFoundation
import CinemaCore

enum PlaybackAudioSession {
    // These synchronous system calls can wait for audio I/O. Never make them from a view's
    // onDisappear/MainActor, especially while a network decoder is still stopping.
    static let shared = PlaybackSessionCoordinator(activate: {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .moviePlayback)
        try session.setActive(true)
    }, deactivate: {
        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    })
}
