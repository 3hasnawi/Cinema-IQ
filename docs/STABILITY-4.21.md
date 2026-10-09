# Stability batch

- **Damaged saved lists** (`Library`): a malformed favourites, recent or followed-series text is set aside
  under `<key>_damaged` and read as empty, instead of crashing at every launch.
- **Damaged profile list** (`ProfileStore`): falls back to the last good copy (`list_backup`); the damaged
  text is kept under `list_damaged` and never silently overwritten, so extra profiles are not lost.
- **Progress saving**: the position is saved every few seconds as before, but the continue-watching list is
  only rewritten when the film changes (it was parsed and rewritten on the main thread every time).
- **Network**: Arabic messages for DNS, timeout, TLS and HTTP errors (`NetworkErrors`), an honest message per
  HTTP status instead of always "check the local network", and one retry for brief failures and 5xx on
  Cinemana and VODU reads. English library text is never shown to the user.
- **CI**: `pr-checks.yml` compiles the app and runs unit tests on pull requests. It builds no APK, uses no
  secrets and publishes nothing.
- Not in this batch: cancelling in-flight HTTP calls, sharing one HTTP client, R8, TMDB cache limits.
