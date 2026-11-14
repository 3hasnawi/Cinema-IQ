# Apple playback engines and IPTV reference

Cinema iQ keeps AVPlayer/AVKit as the default for HLS and MP4. A dynamically linked
VideoLAN VLCKit 3.7.4 backend adds decoding for containers/codecs Apple may reject
(for example MPEG-2/MP2 in Matroska or MPEG-TS). Both backends use the existing
PlaybackController and library save callbacks. Android is unaffected.

## Selection and state

Playback options → **محرك التشغيل** offers Automatic, Apple and Extended
Compatibility. The choice is device-local under the new independent key
`apple.playback.engine.v1`; existing settings, profiles and progress keys are unchanged.
Automatic selects compatibility for known non-native container extensions and
attempts one decoder fallback after a native unsupported-format error. HTTP
401/403, missing streams, rate limits, network/TLS and protected-content errors
are reported separately and never automatically treated as codec failures.

Cinema retains its quality, audio, subtitle, pause, seek, rate, episode and progress
controls. Compatibility uses its own renderer and TV transport because AVKit
cannot control an independent decoder. Casting remains subject to the receiver's
codec support; using VLC locally does not add codecs to Chromecast/AirPlay.

HLS stays on the master manifest. Apple uses public rendition caps; compatibility
uses VLC's `adaptive-maxheight` and `adaptive-logic=highest`. Changing a compatibility
quality reopens the authorized URL and retains VOD position/pause. Live input may
return to the live edge. Source-backed refresh remains the existing provider retry;
no decoder retries credentials or bypasses DRM, subscription or server restrictions.

VLCKit shutdown runs on a serial utility queue, and a generation token prevents
stale callbacks/channel requests from changing the active channel. Old renderers
cannot detach a newly mounted guide/full-screen drawable.

## TiviMax reference and scope

Public sources inspected:

- https://tivimax.web.app/ (FAQ: default Yum and alternative Magic engines)
- https://tivimax.web.app/images/tv.png (category/channel columns and live preview)
- https://apps.apple.com/us/app/tivimax-iptv-player-premium/id1632953327?platform=tv

The native paid app was **not** installed, executed, disassembled or decrypted.
Its latest runtime behavior was not verified. Cinema's guide is an independent
implementation of publicly observable category/channel/preview browsing: playback
continues in a preview while browsing; Up/Down changes channels full-screen, OK
opens the guide, and Back closes the guide before leaving playback. We do not
invent EPG data where the user's provider has supplied none.

## Dependency and license

`scripts/prepare-vlc.sh` downloads official stable **dynamic** MobileVLCKit and
TVVLCKit XCFrameworks with pinned SHA-256 checksums. SDK binaries and archives
stay in ignored `.build/vlc`, never in Git. The project embeds the frameworks;
no statically linked copy is introduced. The official LGPL-2.1-or-later license
is included as `VLCKit-LGPL-2.1.txt` in both applications.

- Library source: https://code.videolan.org/videolan/VLCKit
- Mirror/releases: https://github.com/videolan/vlckit/tree/3.7.4
- Underlying libVLC source: https://code.videolan.org/videolan/vlc
- Official binary distribution: https://download.videolan.org/cocoapods/prod/
- License: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

The libraries are unmodified. Recipients retain the LGPL rights to inspect/modify
these libraries and reverse engineer their use for debugging library changes.
They can replace embedded dynamic frameworks with compatible modified builds and
re-sign/install the app through the same sideloading mechanism as the original.
Cinema imposes no additional restriction on those rights.

## Validation limits

The generated CC0 fixture `CinemaIQUITests/Fixtures/compatibility-pattern.mkv`
contains MPEG-2 video, two synthesized MP2 audio tracks and SubRip subtitles.
Only simulator checks copy it into a Debug app; it is not a Release app resource.
Tests exercise actual decoding and controls, not third-party subscriber channels.

No universal codec guarantee is possible: DRM, corrupt/expired URLs, provider
permissions, CPU throughput, HDR/Dolby output and connected audio hardware still
matter. Device-only HDR, passthrough and a subscriber's real live stream need
physical-device verification. See the PR for actual completed validation results.
