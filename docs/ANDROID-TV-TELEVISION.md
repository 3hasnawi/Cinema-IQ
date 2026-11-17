# Android TV television entry and channel overlay

User request: rename the Android TV IPTV entry to «التلفزيون», resume the last watched channel in fullscreen, show a translucent floating channel list on OK, and reveal categories with physical Right from that list.

Reserved scope: `ui/iptv/IptvTvActivity.kt`, `IptvTvScreen.kt`, `IptvTvPlayer.kt`, `IptvChannelRow.kt`, new `IptvTvChannelOverlay.kt`, and `data/iptv/IptvTvStartup.kt` with its unit tests. No hot root movie-player or navigation files are needed. Apple and mobile UI remain outside this task.

Startup uses IDs already stored in `iptv_channel_collection_v1` / `ids` by the successful-playback callback. It resolves the saved ID against the current selected source's freshly loaded catalogue and falls back to the first available channel if needed. No playback URLs, credentials or new storage keys are introduced.

Planned remote behavior: OK opens channels focused on the current channel; focus movement does not tune; Right reveals categories; selecting a category returns focus to its channel list; Left/Back from categories returns to channels; Back from channels dismisses the overlay; OK on a channel tunes it and returns to fullscreen. Video stays on its existing Media3 surface underneath the overlay.

Validation pending. No APK export, version bump, merge or publication is requested for this task.
