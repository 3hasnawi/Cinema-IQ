# Cinema iQ — iPhone preview

Native SwiftUI starter app, isolated from the Android application. Requires iOS 17+, Xcode 16+ and a Mac. This is source code for an initial preview, not a signed IPA or a published iOS release.

## Included

- Arabic RTL, dark interface, Cairo font and existing Cinema iQ artwork.
- Dawwirli home, search, movie details, seasons and horizontal episode cards.
- Native AVPlayer controls and available embedded audio/subtitle tracks.
- Local favorites, continue watching, completed viewing history and playback resume.
- Playback completion stops the movie and offers the next episode for series.
- Screen stays awake while the player is visible; backgrounding pauses and saves progress.
- Namespaced source IDs and strict stream URL validation, preserving panel prefixes.

The app currently supports **Dawwirli only**. Cinemana and Vodu are not wired into this iPhone preview. Source access remains subject to the user's network. Native playback accepts MP4/M4V/MOV/HLS paths; actual video/audio codecs must also be supported by iOS. MKV/AVI, external subtitle downloads, IntroDB and advanced Android player features are not implemented here.

## Open in Xcode

Install Xcode and the XcodeGen project generator, then from this directory:

```sh
brew install xcodegen
xcodegen generate --spec project.yml
open CinemaIQ.xcodeproj
```

Select the CinemaIQ scheme and an iPhone simulator. For a real phone choose your own development team under Signing & Capabilities. The iOS bundle ID is `com.cinemaplus.rebuilt.ios`; the Android package/version/signing are unchanged. Generated Xcode files and personal signing settings are ignored by git; `project.yml` is the project source of truth.

To run pure source/parser tests without Xcode:

```sh
swift test --package-path Core
```

`iOS Core Checks` runs these tests on Linux for changes to this folder. Passing core tests does **not** establish that the SwiftUI app compiles or plays video on iOS. No automatic macOS build/export/release workflow is added.

## Firebase next step

Account login and cloud sync are **not enabled in this first preview**. The settings screen states this explicitly, and all data stays in the device's `ios.local.*.v1` UserDefaults namespace. No local Android data or existing cloud records are modified.

To prepare the shared Firebase backend, register a separate **iOS** app in the existing Firebase project `cinema-iq-98b3f`, with bundle ID `com.cinemaplus.rebuilt.ios`, and download `GoogleService-Info.plist`. The Android `google-services.json` cannot configure an iOS app. Do not commit personal configuration, service-account keys or signing certificates. Once that file is available, the next PR can implement verified-email login and the Android-compatible profile/entries sync protocol, preserving account isolation, pending writes and tombstones. Merely adding the file does not enable sync.

## Validation and remaining checks

- Core tests cover stream identity/rejected paths/unsupported containers, Android media decoding and identity round-trip, duplicate search rows and real series fixture parsing.
- Must still validate project generation, SwiftUI compilation, small-screen layout, landscape playback, seeking/resume, subtitle availability, network failures, foreground/background behavior and real-device resource use on a Mac/iPhone.
- No IPA has been exported, no Apple signing identity was created and nothing has been published to App Store/TestFlight.
