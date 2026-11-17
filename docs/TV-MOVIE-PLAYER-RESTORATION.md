# Apple TV movie player restoration

The shared decoder preference allowed an IPTV compatibility choice to select the movie fallback UI as well. Supported movies must retain the previous AVKit presentation, independently of live-TV decoder settings. Movies that actually need the compatibility decoder keep wide-format playback with a movie transport: circular icon menus, a slim seek bar and the original pause-information placement, without the large rounded glass panel.

Scope: Apple TV movie player and compatibility transport; shared controller receives an optional preference scope with unchanged defaults for iPhone/iPad and IPTV. Existing preference keys and saved playback data are retained. New scoped Apple TV movie preferences start with automatic decoding.

Reserved files: ios/CinemaIQTV/TVPlayerView.swift, TVCompatibilityTransport.swift; ios/CinemaIQ/PlaybackController.swift; a CinemaCore preference policy/test; focused TV movie UI tests/workflow as needed. Android and live-TV design are outside this change.

Validation pending. No merge, new IPA/version or publication is part of this editing request. Compile/unit checks and actual simulator remote tests will be reported separately, with no claim about a physical Apple TV or live provider access.
