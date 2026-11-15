# Cinema iQ for Windows

Native Windows 10/11 x64 adaptation of the Android TV experience. This platform
lives in `windows/`; no Android or Apple application files are changed.

## Install

The deliverable is **one `Cinema-iQ-Windows-0.1.0-Setup.exe` installer**. It bundles
the .NET 10 runtime and the LibVLC decoder, including its native plugins. Users do
not install .NET, VLC, Java or an Android emulator separately. Installation is
per Windows user, without administrator privileges. Windows 11 x64 is recommended;
the technical minimum is Windows 10 build 19041. ARM64 and 32-bit packages have not
been built or tested. The installer is not digitally signed with a Windows code
signing certificate; Windows may show an unknown publisher prompt.

Uninstallation leaves the user's library in
`%LOCALAPPDATA%\CinemaIQ\UserData`. Subscription credentials and account refresh
tokens use Windows DPAPI, bound to the current Windows user. Do not copy these
files to another account or machine. Subscriptions are not uploaded to Firestore.

## Experience

- Android TV colors, Cairo typeface and existing brand artwork; Arabic RTL text,
  a right sidebar, large rotating home artwork and horizontal shelves.
- Cinemana, Vodu and Dawwirli providers, plus a combined Cinemana/Vodu catalog;
  search, pagination, details, separate season buttons and episode cards.
- Local profiles with avatar import, favorites, continue watching and watched
  history. Episode numbers remain visible alongside episode titles. Completed
  films leave continue watching; series advance to the next available episode.
  Returning to Home checks followed series for newly available episodes.
- Native fullscreen LibVLC playback, available quality options, seek, pause,
  volume, audio/subtitle tracks, speed and external subtitle files. IntroDB skip
  and next-episode buttons appear when the required metadata/markers are available.
- IPTV M3U and Xtream sources, categories, channel search, an in-player floating
  guide, channel switching and quality alternatives advertised by the subscription.
  HTTP requires explicit consent per subscription. Lists are bounded to 20 MB and
  20,000 channels. Credential-bearing API redirects stay on the exact server origin.
- TMDB details/cast/episode metadata, OMDb IMDb ratings and official YouTube API
  trailer lookup when configured. Trailers open the official YouTube page in the
  default browser. Film and live-channel playback stay inside the native player.
- Email/password Firebase account integration using the existing profile and
  library document schema, offline outbox and account-isolated local libraries.
  Cloud login and cross-device synchronization require valid Firebase client
  configuration/rules and an email-verified account.

Cinemana and Vodu can require a supported Iraqi network. Availability and the
formats supplied by a particular subscription remain provider-dependent. No DRM
or access controls are bypassed. Do not assume every codec or stream is supported.
Subtitle size/background preferences apply when a player is next opened. Windows
casting and an automatic Windows updater are not implemented in this first version.
Related-title recommendations are not presented without verified matching content.

## Controls

Mouse and keyboard are supported throughout. Tab/Shift+Tab moves between controls.
On home shelves, Left/Right moves between cards, Down/Up selects the first card
of the next/previous shelf, Right from its first card opens the sidebar, and Left
returns to the previous card. Enter opens the focused title; Escape returns home
or closes the current dialog/player. F11 toggles the main window's fullscreen mode.

In the player, Space toggles pause, Left/Right seeks for films, M toggles mute,
and Escape closes the floating panel before closing playback. In IPTV, Up/Down
switches channels and Enter opens the floating channel/category guide. The guide
also has mouse-accessible controls. The native video surface remains mounted while
panels open, avoiding a video surface rebuild or a large placeholder panel.

## Developer build

Install the .NET 10 SDK and Inno Setup 6 on Windows. From the repository root:

```powershell
./windows/scripts/build.ps1 -Installer
```

This runs the Core tests, writes local client configuration, publishes a
self-contained x64 directory and wraps it into one EXE under
`windows/artifacts/installer/`. Without `-Installer`, it produces the publish
folder only. Source builds need internet to restore NuGet packages. If Inno Setup
is missing, the script installs the pinned compiler with Chocolatey.

Configure client API keys through environment variables, not tracked source:
`TMDB_API_KEY`, `OMDB_API_KEY`, `YOUTUBE_API_KEY`, `FIREBASE_WINDOWS_API_KEY`,
`FIREBASE_PROJECT_ID`. `configure.ps1` writes the gitignored
`src/CinemaIQ.Windows/appsettings.json`. When run from the full repository it may
reuse the Firebase project/client configuration already in Android. An
Android-restricted API key may not authorize Windows; use a properly configured
Firebase client key for Windows and keep Firestore rules enforcing authentication
and ownership. Never embed service-account credentials or signing private keys.
Desktop client keys are distributable configuration, not a trusted authorization
boundary. Scope/restrict API access in the provider console.

The manual GitHub workflow `.github/workflows/windows.yml` reads repository
secrets, builds and silently installs the actual EXE on a Windows runner, then
runs the isolated WPF/native decoder smoke test. Its test artifact retention is
one day. It does not publish a release, merge a PR or change other platforms.
On this initial task branch it also runs on Windows-source pushes.

## Verification and limitations

`dotnet test windows/tests/CinemaIQ.Tests` runs fixed parser, provider identity,
URL policy, IPTV normalization, pagination, episode progression, library,
cloud-wire compatibility, backup recovery and cancellation tests. WPF can be
cross-compiled on Linux with `EnableWindowsTargeting`, but it cannot be run there.

The Windows workflow tests the **installed** app: real WPF rendering and focus
routes, offline recovery, DPAPI roundtrip, LibVLC playback of a local CC0 MKV,
two audio tracks, one subtitle track, pause/resume, native frame capture and clean
decoder shutdown. `windows-home.png` and other UI images are real WPF
RenderTargetBitmap captures with clearly labeled fixture titles; the
`windows-decoded-video.png` file is a separate actual decoder snapshot, not a
composited screenshot. Fixture files are excluded from the installer.

See the PR and workflow result for the checks that actually passed. These automated
checks are not a physical Windows-device test, a real IPTV subscription test,
proof of live provider availability, or a successful sign-in to a real user account.
The normal application starts with real sources, never the smoke fixture.

See `THIRD-PARTY-NOTICES.txt` and the installed license texts for .NET, AngleSharp,
Cairo, LibVLC and LibVLCSharp. LibVLC is dynamically linked and its DLLs are
replaceable; the installer does not seal them into a single application binary.
