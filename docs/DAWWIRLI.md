# Dawwirli third source

Adds opt-in DAWWIRLI (دورلي). Existing BOTH/CINEMANA/VODU values, their behavior and the default are preserved; BOTH still means Cinemana plus VODU. Settings on phone and TV enumerate the new choice. No account, source token or Cinemana authorization header is sent to Dawwirli.

Public read endpoints observed in the site's public JavaScript: /api/ar/home, /api/section, /api/cat, /api/search, /api/info, /api/series_info and /api/subtitles. Playback uses /vod with kind/id/ext. A movie MP4 range request returned HTTP206 and a valid MP4 signature without login. Full decoder playback/seek was not performed.

Namespaced IDs encode movie/series/episode, parent show, multi-panel raw IDs and file extension. Saved lists/history/followed episodes retain IDs and route playback to the original provider even after changing selected source. Source selection filters background followed-series refreshes appropriately. Dawwirli subtitles bypass Cinemana-specific resolution and diagnostic logging.

Home displays source shelves; search uses the server's ranked result set and does not invent pagination. Blank catalog uses the site's paginated newest list (the server currently bounds this to recent items). Explore displays the site's actual category/platform shelves rather than Cinemana mood categories. Category loading is bounded to eight per type in batches of three; these shelves preview the first category page. Search is the route to other works beyond previews. No claim of a full-catalog mirror is made.

The initial playback adapter exposes original file quality only, not the site's browser-specific transcode/alternate-source machinery, live TV, downloads, accounts or social features. Embedded tracks remain Media3's responsibility; external tracks are mapped from public subtitle responses. Hardsub-only results do not create fake selectable tracks. Some devices/codecs and video editions need actual playback testing.

Scope: independent DawwirliApi/Mapper and fixture tests, SourceChoice.kt, CinemanaApi.kt routing, CinemaViewModel.kt integration, CinemaApp.kt/MobileCinemaApp.kt Explore routing and PlayerScreen.kt diagnostic provider guards. No layout redesign, preference-key, version, APK or public release. Current main is the source of truth.

Validation pending: GitHub Debug tests/Release R8 compile; real-source catalog/detail/range probes. Not tested: physical Android phone/TV playback, all provider panels, external subtitle alignment and future server changes. This uses public interfaces observed technically; it is not evidence of an official integration agreement.
