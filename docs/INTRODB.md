# IntroDB timing fallback and source marker corrections

Scope: strict source timing parser, IntroDB client/parser/cache, PlaybackEndPolicy validation, CinemaViewModel asynchronous fallback integration and focused unit tests. Source timing wins per segment. No player layout, version, APK or publishing changes. Validation pending.

IntroDB public reads: https://api.introdb.app/segments?imdb_id=tt0903747&season=1&episode=1 . Match show IMDb ID plus season/episode. Interpret explicit start_ms/end_ms; never guess units from magnitude or consume duration/confidence as a start time. Missing/mismatched/unavailable data must not block playback. Community timing can differ between video editions; skipping remains manual.
