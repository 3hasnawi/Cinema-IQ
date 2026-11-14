# IPTV on iPhone, iPad and Apple TV

Open **Browse → IPTV** on iPhone, or **IPTV** in the iPad tab/sidebar. Tap **+** to add a source from your provider:

- **M3U:** paste the complete playlist URL, including any token or query parameters. A direct HLS manifest is also accepted as one channel.
- **Xtream:** enter the server's base URL, username and password separately. Do not paste `get.php`, `player_api.php` or their query parameters into the server field. HLS (`m3u8`) is recommended; MPEG-TS depends on the provider's codecs and AVPlayer support.

On **Apple TV**, open **IPTV → إضافة مصدر** from the sidebar. Choose M3U or Xtream, enter the same provider information using the native TV keyboard (or the paired iPhone keyboard notification), and select **اتصال وحفظ**. The channel grid supports remote navigation, category filtering, search, refresh and source editing/deletion. Menu closes the current modal/player first; on the IPTV page it returns Home. Closing playback restores the selected channel. The player uses the existing PlaybackController with native AVKit remote/audio/subtitle controls and the preferred quality/language settings.

After connecting, select a category, search channel names, or pull down to refresh. Source management supports selecting, editing and deleting saved sources. Playback opens inside the app and returns to the channel list when closed. If a playback link expires, **Retry** refreshes the provider's catalogue before reopening the channel.

## Scope and storage

This integration supports **live channels**, not Xtream video-on-demand, series, catch-up or EPG. It does not supply an IPTV subscription or channels. Use a provider you are authorized to access.

Both apps use the same Core IPTV store, parser/client, memory cache and Keychain vault implementation. The iPhone/iPad service and selection key remain unchanged; Apple TV uses its own `com.cinemaplus.rebuilt.tv.iptv` service and `tv.iptv.selected.v1` selection key.

Source addresses, tokens and credentials are stored in a non-synchronizing, device-only Keychain item. They are not part of movie/series library data or Firebase synchronization. Only the selected source's opaque identifier is stored in UserDefaults. Channel catalogues are cached in memory for five minutes, with at most three cached sources. Live channels do not create movie watch-history or resume entries.

HTTPS is the default. Enable **السماح باتصال HTTP لهذا المصدر** only if the provider uses HTTP for its catalogue or channels, including HTTP channels inside HTTPS playlists. HTTP is not encrypted. Both target ATS settings permit user-configured HTTP hosts; Core rejects HTTP unless the source explicitly opts in. TLS validation is never bypassed. Catalogue/API redirects to other origins are rejected; use the provider's final direct URL in that case.

## Verification

`CinemaCoreTests/IPTVTests.swift` uses static fixtures and mocked transport to check M3U/HLS parsing, Arabic metadata, stable source-scoped identities, Xtream authentication and URL encoding, invalid links, network errors and redirect restrictions.

`CinemaIQUITests/IPTVFlowTests.swift` exercises the native source forms, filtering, actual isolated Keychain persistence across relaunch, and the existing AVPlayer with a local reference clip on iPhone and iPad simulators. Debug fixture hosts and the test-only Keychain namespace are compiled out of Release builds. Test credentials are synthetic; no real subscription is included.

Run **iPhone and iPad IPTV checks** manually from Actions when validating UI changes. It uses Xcode-managed local simulator signing so the tests exercise actual Keychain storage; it needs no developer account or release certificate. The reference video is included before Xcode seals the test app. The workflow retains screenshot/log artifacts for one day and does not export or publish an IPA. Core, Release compilation and existing PR checks remain automatic. Simulator fixtures do not establish compatibility with a particular real subscription or physical device.

`TVIPTVFlowTests` and the manual **Apple TV IPTV checks** workflow verify native remote entry/exit, M3U form input, Xtream source selection/authentication through fixture transport, category filtering, real isolated Keychain persistence, and internal local-reference playback. The TV workflow retains screenshots/logs for one day and includes the local clip before Xcode-managed simulator signing. Fixture and test storage code is excluded from Release. Neither platform supplies a subscription or bypasses TLS/access controls.
