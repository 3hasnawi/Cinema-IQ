# Focus restoration and episode labels

Scope: TvSectionNavigation.kt, CinemaApp.kt, ExploreScreen.kt, MovieDetailsScreen.kt, TmdbMetadata.kt, Models.kt, a pure episode-label helper and tests. Restore the selected Settings/Explore card after scrolling it into view, handle false focus requests, and show genuine episode titles without removing S/E numbering. No APK, version bump or publication. Local debug unit tests and release Kotlin compilation passed (159 tests, zero failures). GitHub checks pending.

Implementation: frame-based focus retries handle false results and exceptions. Explore scrolls to the selected mood row before requesting focus; Settings brings the selected card into view. Episode metadata uses an optional session field `episodeTitle`, preserving the source title and existing storage schema. Cards always retain S/E numbering, show a distinct episode title and hide a repeated parent series title.

Physical TV focus/scroll testing remains required; no claim of measured performance improvements.
