# Independent iPhone releases

At the owner's request, `ios` is the separate iPhone release branch; `main` remains the Android channel. Begin iPhone work from current `origin/ios`, use a `chatgpt/ios-…` or `claude/ios-…` task branch, and send a PR to `ios`. Keep the shared collaboration safeguards (no direct pushes or forced updates, checks before merging, separate version PR, explicit build/publication authorization). This channel exception is specific to the owner's separate-branch request.

This branch contains only the iPhone portion of the Google Cast feature from PR #104. Android Cast remains in that separate PR for future Android review. No Android source, version, signing settings or update metadata is changed here.

The existing `ios-unsigned-ipa.yml` workflow is manual and restricted to the `ios` branch. Use `confirm=RELEASE` for a build; set `publish=true` only when the owner explicitly requests publication. It exports only the iPhone arm64 IPA, with the Google Cast framework, and retains the Actions artifact for one day.

Publication uses `RELEASES_TOKEN` to create an `ios-v…` public preview in `Cinema-IQ-Releases`, with an unsigned IPA and SHA-256 checksum. `--prerelease --latest=false` keeps Android's `/releases/latest` updater on its existing APK release. It never modifies Android `update.json`, APKs or previous releases. A separate PR increments the iPhone target's marketing/build versions; the Apple TV and Android versions stay unchanged.

The IPA needs user-side signing (for example Sideloadly on Windows); no Apple distribution certificate or App Store Connect credentials are configured. This does not publish to App Store or TestFlight. Local-network discovery and source/codec/subtitle compatibility still require physical Chromecast testing. External WebVTT captions are supported; SRT conversion requires a receiver-accessible service not configured here.
