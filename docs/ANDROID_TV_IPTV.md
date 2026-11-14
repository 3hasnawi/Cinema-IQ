# Android TV IPTV

Add an M3U playlist/direct HLS URL or an Xtream live-channel account from the TV sidebar's IPTV entry. Sources belong to this device, independently of movie profiles and cloud sync. Live channels must not create movie watch-history/progress entries.

Implementation scope: new isolated IPTV models/parser/client/storage and TV activity, source forms, channel catalogue and Media3 playback. Preserve the existing Android phone UI, TV movie UI/player, package ID, versions and stored library keys. Only the TV sidebar entry and Android manifest require existing-file integration.

Provider credentials and token-bearing URLs must use Android Keystore AES-GCM encryption in app-private no-backup storage. HTTPS is the default; per-source HTTP consent is required for plain HTTP catalogues, logos and streams. Android's manifest must permit user-selected HTTP hosts, while IPTV runtime validation still enforces each source's policy. Normal TLS certificate validation remains enabled. Do not log provider URLs, passwords or player exception text.

Live TV scope: M3U and Xtream categories, search, refresh and source management; no Xtream VOD/series, EPG/catch-up or subscription sync. HLS is recommended; codec and MPEG-TS support depends on the device/provider. No subscription or preset channels are supplied.

Validation will cover fixed M3U/Xtream fixtures, malformed URLs/authentication, source identity/persistence formats, bounded networking, and compatibility with existing code. No APK export, version bump or publication is authorized by this feature request.
