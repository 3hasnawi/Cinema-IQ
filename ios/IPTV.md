# IPTV on iPhone and iPad

Open **Browse → IPTV** on iPhone, or **IPTV** in the iPad tab/sidebar. Tap **+** to add a source from your provider:

- **M3U:** paste the complete playlist URL, including any token or query parameters. A direct HLS manifest is also accepted as one channel.
- **Xtream:** enter the server's base URL, username and password separately. Do not paste `get.php`, `player_api.php` or their query parameters into the server field. HLS (`m3u8`) is recommended; MPEG-TS depends on the provider's codecs and AVPlayer support.

After connecting, select a category, search channel names, or pull down to refresh. Source management supports selecting, editing and deleting saved sources. Playback opens inside the app and returns to the channel list when closed. If a playback link expires, **Retry** refreshes the provider's catalogue before reopening the channel.

## Scope and storage

This integration supports **live channels**, not Xtream video-on-demand, series, catch-up or EPG. It does not supply an IPTV subscription or channels. Use a provider you are authorized to access.

Source addresses, tokens and credentials are stored in a non-synchronizing, device-only Keychain item. They are not part of movie/series library data or Firebase synchronization. Only the selected source's opaque identifier is stored in UserDefaults. Channel catalogues are cached in memory for five minutes, with at most three cached sources. Live channels do not create movie watch-history or resume entries.

The current app form accepts **HTTPS** providers. HTTP support requires a coordinated iOS ATS configuration change; it remains disabled in the form while that change is pending. Core parsing supports HTTP only when a source explicitly opts in. TLS validation is never bypassed. Catalogue/API redirects to other origins are rejected; use the provider's final direct URL in that case.

## Verification

`CinemaCoreTests/IPTVTests.swift` uses static fixtures and mocked transport to check M3U/HLS parsing, Arabic metadata, stable source-scoped identities, Xtream authentication and URL encoding, invalid links, network errors and redirect restrictions.

`CinemaIQUITests/IPTVFlowTests.swift` exercises the native source forms, filtering, actual isolated Keychain persistence across relaunch, and the existing AVPlayer with a local reference clip on iPhone and iPad simulators. Debug fixture hosts and the test-only Keychain namespace are compiled out of Release builds. Test credentials are synthetic; no real subscription is included.

The dedicated validation workflow retains screenshot/log artifacts for one day and does not export or publish an IPA. Simulator fixtures do not establish compatibility with a particular real subscription or physical device.
