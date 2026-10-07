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
