# Unified Android phone and TV app

The same package, account, repositories, view model and local library serve both interfaces.

- Android television mode selects the existing TV UI, scaling, remote navigation and player.
- Other Android devices select a touch interface: bottom navigation, movie and series filters, search, content rails, details, seasons/episodes, watchlist and settings.
- Continue-watching cards play directly; all existing completion and new-episode checkpoint rules remain in the shared view model.
- The mobile player uses native Media3 touch transport, seeking and audio/subtitle menus, plus quality, episode and next-episode actions. It reuses resolved Cinemana subtitle MIME types.
- Phone browsing follows device orientation; video uses sensor landscape and hides system bars. Leaving playback restores the browsing orientation and bars.
- Phone playback preserves progress across quality changes, uses the selected episode's progress on a new episode, pauses in the background and uses audio focus.
- The mobile settings reuse account, title language, content source, preferred quality, subtitle styles, family-filter state and GitHub updater settings.

No new dependencies or package change. Version remains 4.3 until the owner approves another export. No APK assembly or public release is authorized by the edit request.

## Validation

Kotlin compilation and JVM unit tests cover the existing shared behavior and added mobile quality/resume policy. A physical phone and a TV must still be tested for native player controls, orientation changes, keyboard visibility, insets and D-pad behavior before approving publication. BlueStacks normally reports a non-TV mode and therefore opens the touch interface.

## Phone controls follow-up

- Rounded floating bottom navigation shrinks on downward scrolling and expands on upward scrolling.
- Details and the player share horizontal episode artwork cards. Swipe upward from the bottom edge of the player or tap Episodes to open the season/episode sheet.
- Quality, audio/subtitle selection and caption styling are accessible from the lower player action row. Track selection uses the actual supported Media3 tracks; caption sliders persist through the existing preferences.
- The phone player requests an awake display and clears the window flag on exit. This addresses automatic screen sleep; actual device crashes or shutdowns require separate device logs.
- Verify on a phone: upward/downward scroll, bottom-edge gesture, season changes, available subtitle/audio tracks, caption sliders, quality switching, screen remaining awake beyond its timeout and sleeping normally after exit. TV UI files are unchanged.

## Phone player now matches the TV player

- Compose draws all controls (Media3 only renders video and subtitles): the same round glass buttons, thin gold scrubber with buffered range and time labels, title block with series line and quality badge, and the vertical shade over the picture.
- Tap the picture to show or hide the controls; they hide after 3.5 s while playing. Centre: back 10 s, play/pause, forward 10 s. Bottom row: episodes (series), scene filter, subtitle appearance, audio/subtitles, quality, next episode.
- Quality, audio/subtitles and subtitle appearance open as a glass panel that grows out of its button, like the TV. Subtitle appearance uses the same cycling rows and live sample as the TV; the sliders were removed.
- Drag or tap the scrubber to seek (the video seeks when the finger lifts). The swipe up from the bottom edge still opens the episodes sheet.
- TV player files are untouched. Needs a phone test: orientation, cut-out, popups near the screen edge, scrubbing, episode switching and subtitle changes.

## Phone playback and recommendations fixes

- Ended media stays stopped; the transport button does not restart it. Seek controls require a ready player, a known positive duration and a seekable timeline; a cancelled scrub does not keep auto-hide suspended.
- New media reveals its controls and gets a fresh visibility interval when playback starts, including after buffering.
- Similar titles stay in the same movie/series format, exclude the current title and individual episodes, and prioritize TMDB genre overlap or known source genre-shelf membership. Rating and year proximity break ties. Missing genre data falls back to rating/year; known unrelated genres are excluded when the target genres are available.
- TMDB genre IDs come from the existing matched search response and are cached with artwork. Old artwork cache entries without genres refresh once. Phone detail enrichment is bounded to 36 candidates and never changes playback sources.
- TV interface files and release version are unchanged. Device verification remains necessary; no APK export is part of these source edits.

- The phone next-episode prompt stays visible at the physical bottom-right above the scrubber, with manual selection only. Sources expose no credit timestamps: the fallback window is the final 90 seconds, capped at 10% of duration for short videos. No prompt appears for movies or a final episode without a successor. Existing completion handling removes finished content from Continue Watching.

## Shared phone and TV end behavior

- TV now uses the same no-restart policy and 90-second/10%-duration next-episode window as the phone. Movies stay stopped at the end and completion tracking remains intact.
- The TV next-episode button appears at the physical bottom-right above the scrubber, including while the overlay is hidden. Up from the scrubber or hidden host reaches it; Select activates it and Down returns to the scrubber/host. Appearance does not steal focus or pause playback. Auto-hide is suspended while the button is focused.
- Verify with a physical remote: prompt selection with a hidden overlay, focus return, episode transitions, final episodes and movie completion. No APK build is included.
