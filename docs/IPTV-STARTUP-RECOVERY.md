# IPTV startup, recovery and TV controls

TV entry now restores a compressed, encrypted local catalogue before waiting for the provider. The cache is valid for 15 minutes, then refreshes behind playback; failed refreshes retain the last good list and defer automatic catalogue retries. The cache is capped at three encrypted files, with source configuration bound to authenticated encryption. Existing subscriptions, selected-source, favorite/history preference keys and source-form keyboard behavior are unchanged. Provider parsing and local reads run off the UI thread.

Temporary live-stream failures reconnect with 1/2/4/8/15/30-second capped delays, honor Retry-After and keep the last picture. Catalogue renewal runs independently of direct retries. Switching channel, pausing or leaving stops recovery; a stable 30-second playback period resets backoff. Permanent unsupported-format/HTTP-consent/account failures remain actionable rather than silently looping.

The TV HUD has a larger unframed logo, provider current/next programme titles and local start/end times, or «لا تتوفر معلومات». Xtream uses get_short_epg; M3U discovers a declared XMLTV URL. Optional metadata is debounced, bounded/cached, cancellation-aware and never blocks tuning. The sidebar has a TV glyph. Pause/close are removed from the TV HUD; remote media keys and Back retain their behavior.

Quality/audio/subtitle options use a compact translucent popover anchored to the actual pressed icon, with a short drop-like enter/exit animation. Focus enters the popover and returns after the parent window regains focus. The information strip hides after five seconds without interaction, including when a toolbar item was focused; guides/popovers and text input retain their focus while open.

Reserved/changed files: IPTV view model/client/models/parser, catalogue cache/repository/store and reconnect policy/tests; TV player/HUD/screen/activity; EPG parser/client/tests; TV glyph and anchored popup/placement policy/tests. Version/build files are unchanged.

No new APK has been built for these changes. Compile/unit/release-R8 results are recorded in PR #143. Playback recovery, cache timing on TV hardware, popover animation and new remote-focus flows require an authorized runtime build; previous 4.38 emulator results do not certify this revision.
