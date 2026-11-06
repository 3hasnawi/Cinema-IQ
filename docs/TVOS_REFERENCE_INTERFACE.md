# Apple TV reference interface preview

Work branch: `chatgpt/ios-tvos-reference-interface`. This preview does not merge `main`, change app identity/signing, or publish to App Store/TestFlight.

## Interface

- Four centered glass tabs replace the sidebar: Home, Library, Search and Settings. Previously visited tabs retain their own search, scroll, forms and navigation state. Movie/series catalogs remain available through Home shortcuts.
- Home keeps the featured artwork clear, uses compact metadata and optional real cast portraits, centered selectable carousel dots and glass shelf labels. The Arabic layout mirrors the English reference's reading edge. Source artwork and metadata are used, never invented reference-title assets. Repository TMDB credentials are excluded from downloadable previews, so TMDB-only cast portraits are absent there unless a separately authorized private build configures them.
- The feature's main action opens its details. Siri Remote Play/Pause starts playback; long-press retains Play and favorites actions. Auto-rotation stops during focus, details, playback, popups, backgrounding and Reduce Motion.
- Settings follows the supplied blue-gradient reference, with the Cinema iQ logo physically left of rounded category rows. Source, external providers, account, library/sync, subtitles and about remain real destinations. Existing preference keys and account isolation are preserved.

## Playback and account repairs

- Advancing in the ending interval marks the prior episode complete and keeps that completion during later persistence.
- Quality changes retain pause/play intent and match native audio by stable track identity/language rather than a potentially reordered index. Subtitle selection remains intact.
- Subtitle failures are visible, and Retry reloads stream URLs rather than repeatedly opening the same expired URL.
- Cast portraits are informational focusable credits, no longer empty-action buttons that imply a person page.
- Settings exposes existing sign-up and password reset flows with disabled empty submissions; local-library copy uses an explicit confirmation and reports pending uploads/refresh failures honestly while retaining the outbox. Account/profile changes suppress stale success messages.

## Validation and artifacts

- Focused `TVNavigationTests` and `TVSettingsNavigationTests` cover composition, remote focus, tabs, detail Back, carousel, retained search, settings popups and account form guards. They do not create accounts, send email or import a real library.
- The Apple TV screenshot workflow propagates test/build failures, checks that tests actually ran, retains xcresult/logs/screenshots on failure, and never force-pushes screenshots to a shared branch or prints credential values.
- The compile workflow runs Core tests and unsigned iPhone/tvOS device compilation. On this specific authorized branch it exports an unsigned arm64 Apple TV IPA without a repository TMDB credential as a workflow artifact for 14 days, alongside its source commit and SHA-256. It must be signed by the viewer before installation. The simulator ZIP is a separate artifact and cannot be installed on a physical Apple TV.
- Passing CI is not a claim that ISP-restricted sources, real account login/sync, Chromecast, device audio tracks or physical Siri Remote behavior were tested. Mobile Cast remains the separate PR #104; this preview does not merge that PR or add a pretend Cast control to Apple TV.

Record exact successful run links and tested commit in the branch delivery after CI finishes. Failed or unrun checks must remain explicit.
