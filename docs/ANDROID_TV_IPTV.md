# Android TV IPTV

Add an M3U playlist/direct HLS URL or an Xtream live-channel account from the TV sidebar's IPTV entry, or Explore on Android phones. Sources belong to this device, independently of movie profiles and cloud sync. Live channels must not create movie watch-history/progress entries.

Implementation scope: isolated IPTV models/parser/client/storage and a responsive activity, source forms, channel catalogue and Media3 playback for TV and phones. Existing-file integration adds entries to the TV sidebar and phone Explore screen, plus activity/HTTP configuration in the manifest. Movie interfaces/players, package ID, versions and stored library keys stay compatible. See [IPTV_PLAYER_CONTROLS.md](IPTV_PLAYER_CONTROLS.md) for remote, touch and floating-guide controls.

Provider credentials and token-bearing URLs must use Android Keystore AES-GCM encryption in app-private no-backup storage. HTTPS is the default; per-source HTTP consent is required for plain HTTP catalogues, logos and streams. Android's manifest must permit user-selected HTTP hosts, while IPTV runtime validation still enforces each source's policy. Normal TLS certificate validation remains enabled. Do not log provider URLs, passwords or player exception text.

Live TV scope: M3U and Xtream categories, search, refresh and source management; no Xtream VOD/series, EPG/catch-up or subscription sync. HLS is recommended; codec and MPEG-TS support depends on the device/provider. No subscription or preset channels are supplied.

Unit checks cover fixed M3U/Xtream fixtures, malformed URLs/authentication, source identity/persistence formats, bounded networking, cancellation, HTTP status/redirect handling and source-scoped guide navigation. Record actual Android compilation, R8 and unit results in the PR; Apple simulator screenshots do not establish Android device behavior. No APK export, version bump or publication is included in this feature request.
