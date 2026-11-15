# Mobile Cast removal

The user requested removal of the Cast option on iPhone and Android on 15 November 2026.

Scope: remove the Google Cast sender controls and connection lifecycle from mobile movie playback, its SDK dependencies and discovery registration. Native AirPlay remains the existing Apple system route control. IPTV navigation work is tracked separately in PR #136.

Files reserved for this task: `ios/CinemaIQ/PlaybackView.swift`, `ios/CinemaIQ/CinemaIQApp.swift`, `ios/CinemaIQ/CastSender.swift`, `ios/project.yml`, `ios/scripts/prepare-cast.sh`, Cast-only Core policy/tests, `app/src/main/java/com/cinemaplus/rebuilt/ui/mobile/MobilePlayerScreen.kt`, `app/src/main/java/com/cinemaplus/rebuilt/cast/`, Cast-only tests/styles/manifest registration and `app/build.gradle.kts`.

No version change, package export, merge or publication is requested. Validation results will be added after the removal.

The same player task also tracks the user-reported failure to restore hidden iPhone movie controls. Regression coverage uses the unchanged four-second production HUD timer and real centre-screen taps with Apple and compatibility decoders. The existing iOS validation workflow retains its layout tour and adds a focused `movie_controls` scenario. Fixtures are included before simulator resource signing.
