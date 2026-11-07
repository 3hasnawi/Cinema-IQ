# Apple TV hero trailers and remembered quality

Based on the released `chatgpt/ios-tvos-reference-interface` home at `982195d` and the user's supplied Apple TV reference. The full-bleed backdrop, compact RTL title block, centered pagination and landscape shelves are retained.

## Behavior

- Left/right on the primary hero action changes featured titles in RTL reading order, including wrapping. Moving focus across the pagination dots also selects the title, without an extra click.
- After two seconds on a featured title, the hero can play its explicitly supplied direct HTTPS MP4/M4V/MOV/HLS trailer. It starts muted, exposes pause/replay and sound controls, and stops on title change, navigation, an overlay, full-screen playback, backgrounding or leaving the hero area. Reduce Motion disables automatic previews.
- Artwork remains behind the video until the first frame is ready. Missing, invalid, failed or timed-out trailers leave the artwork visible. Finished previews return to artwork, with replay available. Trailers do not create playback progress or watch-history entries.
- The provider JSON extension is optional `trailerURL` (also accepts `trailerUrl`, `trailer_url` or `trailer`). Only direct media links are accepted. Existing media records decode without it; no existing storage keys change.
- External providers can supply direct trailers in their home item or item details. Cinemana and Dawwirli metadata preserve those explicit fields if the source supplies them. This does not imply those services currently provide direct trailers. Vodu has no verified direct-trailer field and falls back to artwork.
- The demo catalog exposes the existing, openly licensed Sintel trailer from Blender's official download host. Other demo movies are not substituted for trailers. TMDB/YouTube webpage links are not native tvOS streams. No YouTube stream extraction, private API, fabricated URL, API key or new trailer service is introduced.
- Settings → Playback controls hero preview/mute and preferred playback quality. Choosing quality in the full-screen player persists only its resolution, never its expiring URL. The next movie, episode and app launch reuse it. Exact resolution is preferred, then an adaptive HLS stream with a resolution cap, then the nearest lower file (or lowest available higher file when none is lower). Fallback does not overwrite the preference.

## New local preferences

- `tv.playback.quality.v1`: `auto`, a resolution string such as `1080`, or `label:` plus a normalized source quality label.
- `tv.hero.trailers.enabled.v1`: true by default.
- `tv.hero.trailers.muted.v1`: true by default.

These are Apple TV device preferences, separate from account/library data.

## Verification

Core fixtures cover quality matching/fallback/adaptive streams, URL validation, old-record compatibility, demo trailer identity and carousel wrapping. tvOS acceptance tests use a synthetic six-second bundled clip under a DEBUG-only launch flag for remote title changes, pause/mute, short-preview completion past the startup watchdog, focused Replay retention, rapid title switching, and quality persistence across app relaunch. The synthetic clip is not used as a catalog trailer. Run `swift test --package-path ios/Core` where Swift is installed. Native UI checks need Xcode and an Apple TV simulator/device. No build/export, workflow dispatch, push or release is part of this source-only change.

Before release, verify real source metadata and codec/network reachability, rapid left/right changes while resolving, tab changes/backgrounding during resolution and playback, preview end/replay, repeated mute/pause, Reduce Motion, missing/failed media, and preferred quality across next-episode and relaunch.
