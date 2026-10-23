# Cinema iQ — iPhone preview

Native SwiftUI app, isolated from the Android application. Requires iOS 18+. Liquid Glass (iOS 26 design) is used when built with Xcode 26 or later and run on iOS 26+; older systems get the same layout with materials. Local development needs Xcode 26+ and a Mac; Windows testing can use the manual GitHub macOS build and Sideloadly. This is source code for an initial preview, not a signed IPA or a published iOS release.

## Included

- Arabic RTL, dark interface, the iPhone system font (SF Arabic, Dynamic Type) and existing Cinema iQ artwork.
- Floating glass tab bar with a separate search tab, featured carousel (6 s), continue watching, poster shelves, zoom transitions to title pages, episode lists by season, glass player controls with a next-episode button in the last 15 seconds.
- Settings → «فحص الاتصال بالمصادر» loads each source and shows the exact failure (HTTP status or URL error code).
- Cinemana, Vodu and Dawwirli source selection, home, search, movie details, seasons and horizontal episode cards. Cinemana/Vodu catalogs support paged browsing; Dawwirli browse shows its home selections and search results.
- Native AVPlayer controls, available embedded audio/subtitle tracks, and source-provided stream quality selection.
- Local favorites, continue watching, completed viewing history and playback resume.
- Playback completion stops the movie and offers the next episode for series.
- Screen stays awake while the player is visible; backgrounding pauses and saves progress.
- Namespaced source IDs and strict stream URL validation, preserving panel prefixes.

Select **Cinemana, Vodu or Dawwirli** from the home/search source menu or profile settings, according to the service reachable on your network. Catalogs reload on source changes; saved works route to their own original source regardless of the selected catalog. Source choice uses the new `ios.contentSource.v1` setting; existing local library keys are preserved. Source access remains subject to the user's network. Native playback accepts MP4/M4V/MOV/HLS paths; actual video/audio codecs must also be supported by iOS. MKV/AVI, external subtitle downloads, IntroDB and advanced Android player features are not implemented here.

## Open in Xcode

Install Xcode and the XcodeGen project generator, then from this directory:

```sh
brew install xcodegen
xcodegen generate --spec project.yml
open CinemaIQ.xcodeproj
```

Select the CinemaIQ scheme and an iPhone simulator. For a real phone choose your own development team under Signing & Capabilities. The iOS bundle ID is `com.cinemaplus.rebuilt.ios`; the Android package/version/signing are unchanged. Generated Xcode files and personal signing settings are ignored by git; `project.yml` is the project source of truth.

The core package uses SwiftSoup 2.7.0 (MIT) to parse Vodu HTML with the same selectors as Android. Its license is included in app resources.

To run source/parser tests without Xcode:

```sh
swift test --package-path Core
```

`iOS Core Checks` runs these tests on Linux for changes to this folder. Passing core tests does **not** establish that the SwiftUI app compiles or plays video on iOS. No automatic macOS build/export/release workflow is added.

## Windows testing without a Mac

See [Windows installation steps](WINDOWS-TESTING.md). The manual-only **Build unsigned iPhone IPA** workflow tests the core, generates the Xcode project and compiles a Release arm64 device app without signing. It uploads a private artifact for three days, with no public release. It must only be run after an explicit build request. The first Xcode build has not been performed yet.

## Firebase next step

Account login and cloud sync are **not enabled in this first preview**. The settings screen states this explicitly, and all data stays in the device's `ios.local.*.v1` UserDefaults namespace. No local Android data or existing cloud records are modified.

To prepare the shared Firebase backend, register a separate **iOS** app in the existing Firebase project `cinema-iq-98b3f`, with bundle ID `com.cinemaplus.rebuilt.ios`, and download `GoogleService-Info.plist`. The Android `google-services.json` cannot configure an iOS app. Do not commit personal configuration, service-account keys or signing certificates. Once that file is available, the next PR can implement verified-email login and the Android-compatible profile/entries sync protocol, preserving account isolation, pending writes and tombstones. Merely adding the file does not enable sync.

## Validation and remaining checks

- Core tests cover source routing, Cinemana UTF-8/base64 query encoding and zero-based pagination, nested seasons and parent identity, HTTPS stream validation/deduplication, and Vodu catalog/movie/series fixtures, alongside existing Dawwirli and media round-trip tests.
- Live Cinemana and Vodu probes returned HTTP 503 from the development environment. Their adapters follow the existing Android requests and are verified against fixtures; live browsing/playback must still be tested from a supported network.
- Must still validate project generation, SwiftUI compilation, small-screen layout, landscape playback, seeking/resume, subtitle availability, network failures, foreground/background behavior and real-device resource use on a Mac/iPhone.
- No IPA has been exported, no Apple signing identity was created and nothing has been published to App Store/TestFlight.

## Source connectivity (0.2.0)

Cinemana and Vodu use the same HTTPS endpoints as Android. Their requests now send Android's `okhttp` user agent, accept redirects inside the source's own domain (`shabakaty.cc`, `vodu.me`), retry once on timeouts/5xx, and map failures to Arabic messages that keep the error code. App Transport Security is relaxed only for `shabakaty.cc`, `shabakaty.com` and `vodu.me` (older TLS, no forward-secrecy requirement) plus `NSAllowsArbitraryLoadsForMedia`; other hosts keep full ATS. The actual cause of the 0.1.0 failure on the user's iPhone was not observed (the sources are unreachable from the development network); use the in-app source check to read it on the device.
