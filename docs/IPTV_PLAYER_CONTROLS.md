# Live IPTV player controls

The IPTV player keeps the same AVPlayer/Media3 instance while browsing the guide. Sources remain device-local; live channels never create movie watch-progress entries.

## TV remote

- Up/down while the full-screen video surface is focused: previous/next channel in the selected category, wrapping at either end.
- OK/Select on the video: open the floating channel guide over the continuing stream. Within the guide, arrows browse and OK tunes the highlighted channel.
- The guide contains category and channel panes plus search. Reopening highlights the playing channel; changing category starts at its first result.
- Left/right from the video: reach playback options. Play/Pause uses the hardware button or the toolbar.
- Back/Menu closes the open guide/settings panel first; another press exits playback and restores the last tuned channel in the catalogue.

## iPhone/iPad and Android phones

Open IPTV under Browse/Explore (iPad also has an IPTV tab/sidebar item). Tap the video to show controls. Previous/next buttons and a vertical swipe on the video switch channels. The Channels button opens the floating guide with categories/search without stopping playback. Rotating the iPhone reveals controls again. Backgrounding pauses playback.

Quality options come from the stream. Apple HLS choices are preferred maximum resolution/bit-rate caps that keep alternate audio in the master manifest; Auto removes caps. Apple TV retains the existing default quality setting on entry. Resolution caps chosen in the session survive channel changes, while rendition-specific bit-rate overrides are cleared for the new channel. Media3 selects supported video tracks or automatic adaptation. A fixed single-quality stream cannot offer additional renditions. Audio/subtitle options depend on tracks supplied by the provider.

The quality menu also offers **Prefer highest available quality**. On Apple this clears any lower default/session cap, uses the highest advertised HLS resolution/bit-rate ceiling when discoverable, and reapplies the preference after channel changes. AVPlayer still adapts to network and decoder capability; this is not a forced fixed-rendition promise. On Android, Media3 selects the highest supported bit-rate tracks within device constraints. Choosing Auto or a manual video track clears that preference. There is no application-imposed Full HD/4K ceiling: 4K/8K can be used when supplied and supported, without upscaling or inventing source renditions. The fixture tests do not establish physical-device 4K/HDR playback.

The existing M3U/Xtream source forms and encrypted source storage stay compatible. Source URLs must contain their scheme once, and HTTP requires source-level consent.

## Design references

The design is informed by quick-guide and remote-navigation patterns in [Channels remote documentation](https://getchannels.com/docs/apps/remote-control/general/), [Channels settings](https://getchannels.com/docs/apps/usage/settings/) and [iMPlayer's official product page](https://implayer.tv/). The specific up/down/OK mapping follows the requested Cinema iQ behavior rather than copying another app's default mapping.

Simulator screenshots and account/catalogue fixtures do not establish real-provider playback. Record actual validation in the PR. No version bump, APK/IPA export, merge or publication is included in this implementation task.
