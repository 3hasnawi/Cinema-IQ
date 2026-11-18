# Apple TV television parity with Android TV 4.39

Port the approved Android TV television experience to Apple TV, separately from the movie-player restoration in PR #146. Match the bottom HUD, larger unframed logo, current/next programme times (or «لا تتوفر معلومات»), flat controls without pause/close, icon-anchored compact translucent drop menus, five-second inactivity dismissal, OK floating channel list and physical Right/Left category navigation over the continuing full-screen stream.

Also restore the last channel on entry, cache catalogues before provider refresh and reconnect temporary stream failures without a blocking modal. Keep explicit error handling for permanent account, protection or consent failures. Existing source/collection keys remain valid; new local cached catalogues must be encrypted and bounded. Source-form and guide search keyboard dismissal/focus remain the first input priority.

Reserved files: ios/CinemaIQTV/TVApp.swift, TVIPTVView.swift and IPTV/TVIPTVPlayerView.swift, TVIPTVLiveGuide.swift, TVIPTVConfiguration.swift; new TV HUD/popover views; ios/SharedIPTV/IPTVPlayerSession.swift (TV opt-in); CinemaCore IPTV models/parser/store/client plus independent cache/EPG/retry policies/tests. Focused TV IPTV UI tests/workflow will be updated for the new full-screen overlay behavior. No movie, iPhone/iPad design or Android edits.

Compile/core and simulator validation pending. No version bump, IPA export, public release or merge requested yet.
