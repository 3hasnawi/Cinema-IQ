# Apple TV Top Shelf

User-requested dynamic Apple TV Home screen content. Prefer continue-watching entries and fall back to featured films, with deep links into the existing application. Selecting a continue-watching card resumes directly; selecting a featured card opens details, and its Play action starts playback.

Reserved: ios/project.yml; a dedicated Top Shelf extension and shared snapshot/route helpers; LibraryStore change notification hooks; a small TVApp scene modifier hook; independent Core policies/tests and focused simulator validation. No Android or phone design changes. Preserve existing library keys.

Coordination: PR #147 changes only the IPTV tab label in TVApp.swift. This task, by the same executor, reserves the independent scene integration lines and will verify a three-way merge against that branch before delivery. PR #148's home/details/artwork files remain untouched. No merge or version bump/export/publication is requested.

App Group sharing is optional: only a correctly signed shared container may expose the active profile's safe artwork/title/ID snapshot to the extension. Never copy authentication or stream URLs. Without an available shared group, the extension can show public featured catalogue content. Signing/runtime availability must be disclosed; a successful simulator test is not a physical Apple TV or free-account signing claim.

## Behavior

- A native `CinemaIQTopShelf.appex` is embedded in the Apple TV target. Keep Cinema iQ in the first Home row, select it, then move Up to browse the shelf. Refresh timing and final layout belong to tvOS.
- A shared snapshot shows up to eight unfinished recent works first, then up to eight distinct featured films. Completed works are omitted. The library's existing next-episode handoff is preserved.
- Select/display opens the existing detail view. Play opens the existing player and its normal saved position/episode queue. Back returns to details/home. Cold-launch URLs are queued until the actual account/library is attached.
- Matched TMDB landscape artwork uses the existing app metadata client. Portraits remain poster-shaped; no assumptions that a provider's `backdrop` field is landscape. Landscape-only featured results can use the system carousel.
- Active-profile changes replace the snapshot; namespace validation and the provider registry resolve IDs. Existing cloud/local storage keys are unchanged.

## Optional shared-container signing

Before generating a signed project, register a real App Group on the signing team, enable it for both `com.cinemaplus.rebuilt.tv` and `com.cinemaplus.rebuilt.tv.topshelf`, and use provisioning profiles containing that same group. Set `CINEMAIQ_APP_GROUP` when running XcodeGen. The two existing unsigned export workflows also accept it from the repository's non-secret Actions variable of the same name. No account identifiers or secrets are hardcoded.

Leaving it empty generates empty entitlements, preserving the normal unsigned/sideload workflow. The extension then races the three built-in public catalogues with an eight-second deadline and caches public featured metadata for fifteen minutes (stale fallback up to seven days). Without a shared group, it cannot read the app's account, selected source, or continue-watching history. A correctly signed App Group is required for personalized continue watching. Sideload tools must preserve and re-sign the embedded extension (do not enable atvloadly’s Remove Extensions option); this has not been established on the user's physical device or account.

URL delivery uses both the canonical application delegate callbacks (including a cold-launch URL) and the root SwiftUI scene handler. One validated pending URL waits for the profile/window, and duplicate callbacks for the same OS event are coalesced. Debug-only link audit labels contain fixed stages, not URLs or account information.

## Data and lifecycle

The atomic shared file is bounded to 128 KiB, eight entries per section, and seven days. It contains public artwork/title/description, namespaced media IDs, a random profile-context ID, and progress fractions. It excludes authentication, source configuration, playback URLs, and cloud account identifiers. Credential-bearing artwork URLs are rejected. Sequential writes prevent older scheduled writes from replacing newer history. Normal library save/clear/profile events notify TVServices.

## Validation

- `CinemaCoreTests/TopShelfTests.swift` verifies route round trips/rejection, safe artwork, progress/completion, episode numbers, bounds/deduplication, expiration and JSON snapshots.
- The focused native workflow verifies the embedded extension and matching version/build, reads/writes a real simulator App Group using the same loader/TVServices renderer as the extension, then delivers display/play URLs with public XCTest `XCUIApplication.open(_:)` (tvOS 16.4+) and handles any PineBoard Open prompt using the remote and verifies actual app navigation/player/Back behavior.
- The simulator audit calls shared loader code inside the host; it is not proof that PineBoard launched the extension process or rendered its shelf. Physical Home-screen rendering, resigner behavior and App Group provisioning remain device checks.
- Existing iPhone/iPad and Apple TV Release compilation and general navigation checks also run. These checks do not export or publish an IPA and do not change version numbers.

See PR #149 for current recorded check results. No main merge or publication is requested.
