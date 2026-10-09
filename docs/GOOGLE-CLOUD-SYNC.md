# Google account and cross-device sync setup

Status: setup/design only. Google login is not implemented or enabled by this document. No APK or version changes.

## Scope

A separate Cinema iQ cloud identity, independent of source credentials, syncs watchlist, recent playback position/duration, completed episodes and followed-series checkpoints. It does not upload media or source passwords/tokens. Source playback still depends on the device's supporting network.

Use Firebase Authentication (Google provider) and Cloud Firestore under the owner's project. Store data under authenticated user UID, then cloud profile ID. Never use a device-local profile name as the account identity. Existing local preference keys and offline use remain compatible.

Conflict handling must distinguish replay/reset from completion and removals from additions. Store per-title revisions/server timestamps and tombstones, not overwrite an entire library snapshot or choose the largest playback position. Queue offline changes and apply remote changes without triggering an upload loop. Synchronise periodically while playing and on pause/stop; avoid a network write each player tick. A successful login must ask whether to import the current local library. Signing out must isolate account data and avoid copying it to the next signed-in account.

## Owner setup

1. Create a Firebase project in https://console.firebase.google.com/ using an account you control.
2. Add an Android application with package `com.cinemaplus.rebuilt`.
3. Register the existing signing certificate SHA-256:
   `5067306a939e183d5862467b39ccd02eaef3359353a4dc23ebf3f923a396193a`
   and SHA-1:
   `c55bcdbd65c87c5f820e97e0ecd9b2a89cdf4f88`.
4. Enable Authentication → Google; configure the project support email. Download the updated `google-services.json` for this Android application after enabling Google sign-in.
5. Create Firestore and deploy UID-scoped access rules before clients write. Do not use public/test-mode rules. Cloud credentials belong to the authenticated user; Firebase configuration does not grant database access by itself.
6. Provide `google-services.json` through a file upload. Never provide service-account private keys or Google passwords in chat. Confirm the Firebase project ID and desired user-visible consent/app name.

## Device flows and rollout

Phone sign-in should use the supported native Google identity flow and exchange the Google ID token with Firebase Auth. Android TV requires a supported TV/browser/pairing flow, verified separately; do not assume phone credential UI works on remote-only televisions. A QR pairing flow requires an authenticated companion web page and short-lived, one-use session handling on a trusted backend. Never reuse another application's OAuth client ID or expose OAuth client secrets in the APK.

Implement sync logic and tests in independent files where possible. PR #43 currently reserves CinemaApp.kt; do not edit its Settings integration concurrently. Review open PRs again before integration. Validate rules, profile/account isolation, offline conflicts, replay/reset and two-device progress before an explicitly approved trial APK. Public publication requires the owner's separate instruction.
