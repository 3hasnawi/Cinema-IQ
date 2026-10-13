# Watched history inside My List

Phone and TV My List now have two sections: Saved (للمشاهدة لاحقاً) and Watched (سجل المشاهدة). Existing type/sort filters apply independently to the selected data set. Section choice belongs to the profile's view model; no existing saved list is renamed or cleared. The saved section remains the default.

Completed films/episodes, including manually marked watched works, are stored in a separate `watched` JSON list inside the existing account/profile library preferences. Rewatching or removing a work from Continue Watching does not erase its history. Re-completion moves that work to the top rather than duplicating it. Episodes retain their own IDs and season/episode data. History is bounded to the latest 500 works.

A one-time migration recovers completed works whose metadata is still in favorites, followed-series checkpoints or continue watching. Previously removed completed films that have only a boolean flag and no metadata cannot be reconstructed; the feature does not claim a complete historical backfill.

Cloud sync adds optional watched metadata inside existing `playback/<id>` JSON values, not new Firestore paths or fields. Existing deployed rules still apply. New clients read old progress records and retain their local history when receiving old-client records without history metadata. Old clients ignore the new metadata and may remove it from a server record when writing progress, so full cross-device history requires updated clients. History participates in explicit local import and stays isolated by account/profile, like progress.

Scope: Library.kt, CinemaViewModel.kt, CinemaApp.kt, MobileCinemaApp.kt, new data/WatchedHistory.kt, CloudLibraryRecords.kt, CloudLibrarySync.kt and focused JVM tests. No version bump, APK or public release. PR69's player focus changes are left untouched.

Validation: focused tests cover episode identity, rewatch deduplication/order, bounded/damaged storage, optional cloud metadata with reset progress and old-client compatibility. Final Debug/Release compilation and JVM test results are reported in the PR. No physical TV/phone or live multi-device sync test was run.

Final local Debug/Release Kotlin compilation and all 185 JVM tests passed (zero failures/errors); git diff --check passed.
