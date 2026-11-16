# IPTV channel browser and navigation

Reference: Netfly TV 3.0.5 (versionCode 14), official distribution
https://github.com/NFLAB-OF/NFLAB/releases/tag/V3.0.5,
`netfly_tv_tvbox.apk` (49,713,547 bytes). The attached file exceeded the attachment
transfer limit; the user supplied this official distribution link. Analysis used
its decoded Android layouts and targeted TV activity key/navigation methods.
The reference app was not authenticated or interactively run in this environment.
No reference source, binaries, artwork or credentials are included in this PR.

Observed behavior: categories and channels are separate vertical panes, with a
continuing small video preview and channel metadata alongside them. Channel rows
show a source-order number, logo, title and playing marker. Remote focus alone
does not tune a stream. Selecting a new channel previews it without closing the
browser; selecting the current channel expands to fullscreen. Up/down switches
channels in fullscreen. A guide owns its arrows; Back dismisses the guide before
leaving playback. Mobile reference layouts use the same categories/channels split
and touch-accessible quality/audio/subtitle controls.

Implementation uses the existing Cinema iQ players, source clients, credentials
and storage. TV enters the continuing preview browser when a saved catalog first
loads. The existing source-management/channel-grid page remains accessible after
leaving playback. Categories retain provider order and display counts. Filtering
does not renumber channels. Browsing another category is local until a channel is
chosen, so cancel does not silently change the surfing queue. Apple TV keeps its
existing single video-surface compositor transform. Android uses one TextureView
transformed into the measured preview rectangle, without replacing the player.
Phone/iPad guide rows keep single-tap selection and provide side-by-side categories,
logos, numbering and the current-channel marker for touch use.

Scope: IPTV UI and navigation models/tests only. No provider authentication,
subscription handling, movie transport, existing storage keys or version numbers
are changed. No APK/IPA export, public publication or merge was requested.
Validation results and remaining platform limits are recorded in the PR.

The user then supplied the iPhone reference screenshot: an inline video at the top,
physical left category list and right channel list below, with Recent, Favorites,
a heart and a fullscreen action. iPhone/iPad and Android phones now enter this
embedded browser rather than requiring fullscreen before browsing. In landscape,
the preview sits beside the two lists so neither list collapses below the video.
A new channel plays in place; choosing the current channel or the fullscreen icon
expands the same player. Closing fullscreen returns to the lists.

Recent channels are recorded only when playback starts, deduplicated and capped at
100; favorites are capped at 2000. Records contain channel IDs only, are device-local,
filter within the active source and do not change movie history or cloud sync.
Persistence is additive: Apple uses `<existing selectionKey>.channels.v1` in
UserDefaults, Android uses `iptv_channel_collection_v1` / `ids` in SharedPreferences.
All existing source selection and credential keys are retained. Fresh fixture runs
reset only the added channel collection for repeatable tests.

The Android and Swift pure-model suites cover source isolation, recency, missing
channels, toggling, provider order, search and bounds; Swift also tests the bounded
JSON round trip. Native touch flow tests cover inline tuning, Recent/Favorites,
search, fullscreen/return, M3U persistence, Xtream and the compatibility decoder's
audio/subtitles and rotation. Final outcomes are recorded in PR #136, including
which runs use simulator fixtures and which device checks were not performed.
