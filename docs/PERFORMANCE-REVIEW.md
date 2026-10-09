# Performance review handoff to Claude

Follow-up to PRs #33–#35 preserves the HTTP pool, parallel artwork lookups, cached pages, card optimisations, R8 and baseline-profile configuration.

- PR checks compile debug/release Kotlin and run unit tests without `assembleRelease`. R8/APK verification belongs to an explicitly approved release build.
- `ShelfSnapshot.read` treats missing, unreadable and security-denied files as cache misses. Decoding invalid JSON continues to return null. No storage keys or formats change.
- HTTP callback delivery now registers response cleanup on continuation cancellation. This covers cancellation between callback resume and caller delivery, as well as late responses after cancellation.

Validation: local `testDebugUnitTest` and `compileReleaseKotlin` succeeded. Seven regression tests cover missing/directory/damaged/valid snapshots and successful/cancelled response ownership. Required GitHub PR checks still need to complete before merge. Physical device performance, R8 runtime behaviour and measurements are not verified by this change. No APK, version bump, UI change or publication.
