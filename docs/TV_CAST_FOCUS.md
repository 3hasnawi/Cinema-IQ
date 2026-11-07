# Apple TV cast portraits and focus

## Diagnosis of the 0.3.0 preview

- `TVDetailView.people` uses TMDB cast and portrait URLs when available. Its fallback is OMDb's actor names, explicitly with an empty `photo` value. OMDb does not supply actor portraits.
- The released `chatgpt/ios-tvos-reference-interface` preview at `982195dc` deliberately sets `TMDB_API_KEY` to an empty value in the unsigned IPA workflow and checks that the package contains no TMDB credential. `TMDB.shared` is therefore nil and the portraits cannot be requested. This is a metadata configuration gap, not evidence of a failing image CDN.
- That release made each credit a focusable `VStack` rather than a no-op button. The system focus treatment then surrounds the whole rectangular stack, including the actor's name, instead of only its circular portrait.

## Scope of this fix

`TVCastLockup` retains the existing circle, dimensions, name and initials placeholder. Each credit remains focusable for horizontal scrolling and VoiceOver, without an action that promises an actor detail page. System focus decoration is disabled for this lockup only. A circular white ring and a small portrait lift identify the focused credit. Reduce Motion keeps the ring and disables animation and scaling. Poster, episode and other buttons are unchanged.

A keyless TVmaze fallback supplies series cast portraits where available. It prefers a consistent IMDb identity; when none is available, a series root requires an unambiguous exact Latin source title and matching original premiere year. Same-name remakes, missing years and episode-year searches are rejected. Conflicting IMDb IDs or an IMDb lookup miss never trigger title search. It preserves unmatched actors and placeholders; coverage is not complete. Movie portraits remain dependent on an authorized metadata source. See [TVmaze attribution and identity rules](TVMAZE-ATTRIBUTION.md). No repository secret is copied into a downloadable IPA, and release credential checks are unchanged.

No local library keys, versions, signing settings, release workflows or catalog data are changed.

## Regression verification

`TVCastFocusTests` uses a DEBUG-only, offline fixture started with `-tv.testCastFocus`. The fixture contains twelve named credits with empty photo URLs. It does not start cloud synchronization and is unavailable in Release builds.

After an authorized simulator build, run the tests with the existing XcodeGen project and an available tvOS 18+ simulator:

```sh
xcodegen generate --spec ios/project.yml
xcodebuild test -project ios/CinemaIQ.xcodeproj -scheme CinemaIQTV \
  -destination 'platform=tvOS Simulator,id=SIMULATOR_UUID' \
  -only-testing:CinemaIQTVUITests/TVCastFocusTests \
  CODE_SIGNING_ALLOWED=NO TMDB_API_KEY=
```

The tests cover focus entering/leaving/re-entering the row, inert Select, Arabic accessible names, every offscreen credit and the reverse traversal, plus source/license attribution and focus restoration after dismissal. Screenshot attachments must also be reviewed to check that the highlight is circular and no system rectangle remains. Manually check Reduce Motion and VoiceOver on the simulator or a device. Also verify a valid TVmaze portrait, a missing portrait, a failed image download and repeated visits; none should remove the visible focus indicator.

Implementation environment: Linux without Swift, Xcode, or an Apple TV simulator. Source/patch checks can be run here, but they do not establish that the app compiles or that the tvOS focus engine renders correctly. Core tests have been added for metadata identity, strict title/year matching, remake rejection, credential-free search paths, URL validation, cache/error behavior and cast merging. They require Swift and have not been executed in this environment. No app build, export or publication is part of this change.

## Authorized 0.3.1 publication

The owner explicitly approved embedding the configured TMDB API key in the public Apple TV 0.3.1 IPA after disclosure that recipients can extract/reuse it. Only this named preview's physical tvOS Release build receives that GitHub Actions secret. iPhone/simulator builds and OMDb/YouTube keys remain excluded. CI checks live TMDB cast metadata and credential presence without logging values. This deliberate distribution choice is not a claim that compiled app resources can keep an API key secret. The original 0.3.0 release is unchanged.
