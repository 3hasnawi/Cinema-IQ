# Skip timing corrections and IntroDB fallback

Based on current main after PR54 was merged. No player layout, preference-key, version, APK or publishing change.

## Source parser

SkipMarkers is now a separate pure class. It reads whitelisted intro/opening start/end and credits/outro/ending start fields at the root or in explicit timing containers. Camel case, underscore and hyphen variants are normalized. Explicit ms/milliseconds fields take precedence over sec/seconds. Legacy unsuffixed boundary fields are defined as seconds (or mm:ss/hh:mm:ss strings); units are never inferred from numeric magnitude. This is not confirmation of Cinemana's undocumented schema. Duration, confidence, count and unrelated objects cannot become start times. Invalid, nonfinite, negative, reversed or excessively large boundaries are rejected. A missing start with a valid end retains the original start-at-zero convention; an explicitly invalid start does not.

## IntroDB

Public read endpoint: https://api.introdb.app/segments . Requests carry only the matched work's IMDb ID and season/episode, or is_movie=true. No provider credentials, source IDs or stream URLs are sent. No IntroDB key is needed for public reads. Schema was checked against https://introdb.app/docs and a live tt0903747 season 1 episode 1 response.

IMDb identity comes from the existing trusted TMDB title match/external-ID lookup. Episodes use their parent show, not an episode title search. Without a usable IMDb ID or episode coordinates no request is made. The response must match the requested identity, media type and episode. Only intro/outro boundaries are parsed; start_ms/end_ms win over start_sec/end_sec. Source markers win per segment, including source markers present in the incoming card but absent from the detail response.

Requests run after stream loading, independently of video startup. Playback changes cancel the child coroutine/network call and stale results cannot replace another work. Failures leave playback/source timings intact. The client caches valid responses for 7 days and missing results for 6 hours, caps the disk cache at 200 entries, and does not cache network/rate-limit failures.

## Players

Manual skip requires a known duration and a valid interval inside the video. No automatic intro seek was added. IntroDB is community supplied; video editions may differ and exact timeline alignment is not guaranteed. The phone's periodic prompt update is restarted when optional markers arrive, so it sees the new timing without reloading the video. Existing credits-based next-episode policy and its 15-second fallback are retained.

## Validation

Focused JVM regressions cover explicit small millisecond values, ignored metadata, invalid starts/times/ranges, field-unit precedence, mismatched show/episode, source-first merging and a fixture matching the observed live response. Debug/release compilation and full JVM tests are run without building APKs. GitHub checks tests/R8.

Not tested: actual Cinemana marker fields, exact source video edition alignment, end-to-end TMDB→IntroDB matching on a real source title, and physical phone/TV playback. The live API sample had outro timings and no intro; it proves reachability/shape, not universal intro coverage.

Final local result: Debug/Release Kotlin compilation and 180 JVM tests passed with zero failures/errors; git diff --check passed.
