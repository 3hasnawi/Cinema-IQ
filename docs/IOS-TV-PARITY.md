# iPhone parity with the approved Apple TV changes

Base: main `cc6b57c`, Apple TV 0.4.6. This task reviews the existing iPhone and
Apple TV implementations and ports applicable improvements to the touch UI.

## Changes

- Home uses the same featured-film selection and TMDB-first artwork policy as
  Apple TV. A fitted image and blurred fill keep portrait edges visible. Each
  metadata result publishes independently, with cancellation/stale-result guards.
- Details keep a static synopsis, balanced round favorite action, separate season
  buttons and horizontal episode cards with existing ratings, dates and progress.
- Movie playback has a separate engine preference, automatic/preferred quality,
  text-only pause facts without moving the transport buttons, and an in-player
  episode sheet. Selecting the current
  episode leaves playback intact. Changing quality preserves pause/play intent.
  Closing latches immediately and cancels/ignores late queue and metadata work.
- Television opts into the shared encrypted catalog cache, last-channel restore,
  reconnect and foreground policies. Current/next EPG times, large frameless logos,
  compact anchored track popovers and idle HUD dismissal use the existing player.
  Touch play/close actions and the preview/list arrangement remain available.
  Surfing Recent preserves its entry order while displaying updated Recent ranks;
  fresh catalog objects replace stale URLs and removed members are excluded.
- Account and IPTV forms explicitly handle Next/Done and dismissal. Search
  submit/return dismisses the keyboard. The settings Form owns account focus and
  its keyboard toolbar once, outside virtualized rows. Paged requests are owned and reject
  results from a stale tab/generation.
- Plain pages use the approved slate gradient. The section is named «التلفزيون»
  and uses an available television SF Symbol.

Shared Core policies are reused rather than duplicated. Android is unchanged.
Existing library, subtitle and live engine preference keys are retained; the new
`ios.playback.quality.v1` and `apple.phone.movie.playback.engine.v1` keys default to
automatic. Shared native seeking now keeps its pending intent until AVPlayer
confirms completion, including an initially paused opening; this has focused
Core and actual-player UI coverage. AVKit presentation waits for preparation and
the initial seek to settle, with a bounded escape for interrupted/stalled seeking
so system controls and cancellation remain reachable.

Priorities: preserve library and preference keys, distinguish movie and live-TV
controls, keep Arabic/RTL and adaptive iPad behavior, and validate keyboard
Next/Done, dismissal and return focus for any modified input flow.

Apple TV Top Shelf and remote-only gestures have no direct iPhone equivalent.
Automatic hero trailers still require the previously documented provider access;
this task does not represent posters as working trailer previews.

## Validation

Local checks: `git diff --check` and actionlint (excluding the installed older
tool's existing `macos-15-intel` runner-label warning). The parity workflow runs
artwork and input/settings tests on iPhone and iPad, plus actual movie-clock,
quality and episode-sheet regressions on iPhone. The separate IPTV flow validates
Next/Done, search dismissal, metadata/popover presentation and offline reentry on
both devices. Shared native seek changes also run the Apple TV movie regression.

Exact macOS compile/Core/simulator outcomes and evidence links are maintained in
[PR #152](https://github.com/3hasnawi/Cinema-IQ/pull/152). Simulator fixtures do not
establish physical iPhone/Apple TV behavior, live Cinemana reachability, metadata
service matching or subscription-server availability. No version bump, IPA/APK
export, merge or publication is included in this request.

UI screenshots and accessibility trees also persist directly in the test runner's
cache, so an incomplete XCTest result bundle does not discard already captured
evidence. The expanded parity suite has its own overall allowance; individual
assertions and the production HUD timeout stay active.
