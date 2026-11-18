# Apple TV Top Shelf

User-requested dynamic Apple TV Home screen content. Prefer continue-watching entries and fall back to featured films, with display/play deep links into the existing application.

Reserved: ios/project.yml; a dedicated Top Shelf extension and shared snapshot/route helpers; LibraryStore change notification hooks; a small TVApp scene modifier hook; independent Core policies/tests and focused simulator validation. No Android or phone design changes. Preserve existing library keys.

Coordination: PR #147 changes only the IPTV tab label in TVApp.swift. This task, by the same executor, reserves the independent scene integration lines and will verify a three-way merge against that branch before delivery. PR #148's home/details/artwork files remain untouched. No merge or version bump/export/publication is requested.

App Group sharing is optional: only a correctly signed shared container may expose the active profile's safe artwork/title/ID snapshot to the extension. Never copy authentication or stream URLs. Without an available shared group, the extension can show public featured catalogue content. Signing/runtime availability must be disclosed; a successful simulator test is not a physical Apple TV or free-account signing claim.

Implementation and validation in progress.
