# Google Cast on mobile

Android phone and iPhone senders use Google's Default Media Receiver (`CC1AD845`). The existing Media3 and AVPlayer remain the local players. TV player screens are unchanged. AirPlay remains available on iPhone.

## Sender behavior

- Open a title in the phone player and tap the Cast icon. The phone and receiver must be on the same discoverable LAN. iPhone asks for local-network permission on discovery; Android requires Google Play services.
- Receiver controls provide play/pause, seeking, quality selection and subtitle selection. Quality changes reload the chosen source URL at the receiver's current position, retaining play/pause state and the selected external text track.
- Local playback pauses after the receiver accepts the load. Its paused progress clock cannot overwrite remote progress. The receiver's position is saved every five seconds, and on screen exit. Completion uses the existing watched/next-episode library logic.
- Disconnecting returns playback to the phone at the last receiver position. Closing the player stops this player's remote media. It does not leave an untracked remote playback running.
- Casting does not launch Safari or a WebView and does not screen-mirror the phone.

## Source and subtitle compatibility

The receiver retrieves the original URL itself. Cinemana/Vodu network restrictions therefore apply to the receiver too. The phone cannot make an inaccessible ISP URL work on another network. A URL working in AVPlayer/Media3 is not proof that Chromecast supports its codecs, CORS configuration or authorization requirements.

HLS, MP4 and Android DASH are declared using appropriate media MIME types. Unknown endpoint extensions are treated as MP4, as source playback endpoints commonly have no file extension. MKV/AVI/MOV are rejected rather than promised compatible. No proxy, conversion backend, cookies or custom authentication headers are added. Expired links require reopening the title to resolve fresh source URLs.

Only source-provided external WebVTT tracks are offered to the default receiver. SRT and subtitles rendered locally on the phone are not sent as WebVTT. The sender explains unavailable tracks in Arabic. Converting them requires an authorized, receiver-reachable subtitle service; none is configured. Arbitrary embedded audio/text selection and remote subtitle styling are not implemented. Quality options are those supplied by the content source, not fabricated HLS resolutions.

## Implementation

- Android: `cast/CastPolicy.kt`, `CinemaCastOptions.kt`, `MobileCast.kt`; integration in `MobilePlayerScreen.kt`. `MainActivity` uses `FragmentActivity` so the MediaRouter route chooser has a fragment manager. The options provider is declared in the manifest. Google Cast framework 22.1.0 is pinned in Gradle.
- iPhone: `CastSender.swift` owns receiver sessions/requests and a SwiftUI remote-controls sheet. `PlaybackView` connects it to existing library/progress handling; `PlaybackController.externalPlayback` suppresses local writes during remote playback. Core receiver validation lives in `CastMediaPolicy.swift`.
- `ios/scripts/prepare-cast.sh` runs before XcodeGen, downloads Google's official dynamic SDK 4.8.6 archive and verifies SHA-256 before extraction. Files live in ignored `ios/.build/`; no SDK binary or credentials are committed. Only the iPhone target embeds GoogleCast.xcframework. `project.yml` includes local-network permission and both Default Media Receiver Bonjour service declarations.

## Validation

Core tests cover signed URLs, receiver MIME types, unsafe URL rejection, and supported/unsupported external subtitle formats on Android and Swift. CI compiles Android and both Apple targets; results are recorded in the pull request.

No APK/IPA export or public release is requested by this change. No physical Chromecast, real iPhone/Android phone, discovery network, ISP-restricted playback, remote codec decoding, subtitle CORS, or interrupted-session scenario has been tested. These require a receiver/device acceptance run before release.
