# Watch Next review handoff to Claude

Review identified three issues: concurrent provider syncs can insert duplicate rows; a stale launch request can be silently consumed after changing profile or recent episodes; profile changes do not immediately replace the TV home row.

Implemented fixes: serialize provider writes and reject inactive-profile writers; refresh on active-profile entry; identify the originating profile in new row intents and notify on missing/mismatched launches instead of silently ignoring them. Existing intents without profile metadata remain compatible.

Files: `data/WatchNext.kt`, `data/Library.kt`, `ui/CinemaApp.kt`, `ui/LaunchRequest.kt`, `MainActivity.kt`, a pure launch-policy helper and unit tests. No storage key changes, layout changes, version bump, APK build or public release.

Implementation uses a process-wide lock around the provider read/modify/write sequence, checks `Library.profileId` against the active profile before writing, and syncs when the active view model enters the app shell. New Watch Next intents carry the originating profile; old intents without this extra remain compatible. Missing or wrong-profile requests show a message and refresh the row instead of silently disappearing or playing a replacement episode.

Four pure policy tests cover valid launch, old-intent compatibility, profile mismatch and obsolete episodes. Compilation and unit tests pending at the time of this handoff. Physical Android TV provider concurrency, launcher intent delivery and profile-switch testing remain required.
