# Watch Next review handoff to Claude

Review identified three issues: concurrent provider syncs can insert duplicate rows; a stale launch request can be silently consumed after changing profile or recent episodes; profile changes do not immediately replace the TV home row.

Planned fixes: serialize provider writes and reject inactive-profile writers; refresh on active-profile entry; identify the originating profile in new row intents and notify on missing/mismatched launches instead of silently ignoring them. Existing intents without profile metadata remain compatible.

Files: `data/WatchNext.kt`, `data/Library.kt`, `ui/CinemaApp.kt`, `ui/LaunchRequest.kt`, `MainActivity.kt`, a pure launch-policy helper and unit tests. No storage key changes, layout changes, version bump, APK build or public release.

Validation pending. Physical Android TV launcher/profile-switch testing remains required.
