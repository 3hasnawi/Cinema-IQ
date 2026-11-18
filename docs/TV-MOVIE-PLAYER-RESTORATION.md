# Apple TV movie player restoration

The shared decoder preference allowed an IPTV compatibility choice to select the movie fallback UI as well. Supported movies must retain the previous AVKit presentation, independently of live-TV decoder settings. Movies that actually need the compatibility decoder keep wide-format playback with a movie transport: circular icon menus, a slim seek bar and the original pause-information placement, without the large rounded glass panel.

Scope: Apple TV movie player and compatibility transport; shared controller receives an optional preference scope with unchanged defaults for iPhone/iPad and IPTV. Existing preference keys and saved playback data are retained. New scoped Apple TV movie preferences start with automatic decoding.

Reserved files: ios/CinemaIQTV/TVPlayerView.swift, TVCompatibilityTransport.swift; ios/CinemaIQ/PlaybackController.swift; a CinemaCore preference policy/test; focused TV movie UI tests/workflow as needed. Android and live-TV design are outside this change.

Current validation results are recorded in PR #146. No merge, new IPA/version or publication is part of this editing request. Compile/unit checks and actual simulator remote tests are reported separately, with no claim about a physical Apple TV or live provider access.

## Exit after extended playback

The reported case is Cinemana: watch a movie for a while, press Back, then the
app stops responding. Review found synchronous audio-session deactivation on
MainActor while compatibility decoder shutdown was still asynchronous, plus
synchronous VLC pause/drawable teardown during dismissal. These are concrete
blocking paths; live Cinemana reproduction has not yet established which one
causes the user's device hang.

Additional scope: shared `PlaybackAudioSession`, Core `PlaybackSessionCoordinator`
and tests, `SharedIPTV/CompatibilityPlayback`, cancellable controller tasks,
explicit AVKit dismantling and `TVMovieExitTests`. No design/storage-key changes.
Audio activation/deactivation runs on a serial actor, never the main actor;
deactivation waits for decoder retirement and an ownership token prevents an old
exit from silencing newly opened playback. Shutdown cancels media-selection,
subtitle and seek/asset work; progress saves use the last 250 ms clock snapshot.
VLC retains its drawable/player until its stopped event before releasing output.
Quality hints may arrive before asynchronous audio activation creates an item.
Keep both the resolution and bandwidth cap and apply them to that eventual item;
the focused queued-quality simulator case reads the actual AVPlayerItem properties.
During dismissal, an exit latch prevents decoder cleanup from remounting the
loading button. Cancelled compatibility-focus tasks also stop before resetting
focus; pending source results cannot reopen an exiting player.

New tests: Core ownership, duplicate release, activation failure and deliberately
blocked shutdown while MainActor remains responsive. Simulator exit cases play
real H.264/AAC for 125 seconds and MPEG-2/MP2 for 65 seconds, press Menu, verify
hittable/focused details, move left/right, reopen at saved progress and exit again.
The test-only clock and quality injection are excluded from Release. Current run results and provider
reachability limitations are recorded in PR #146.
