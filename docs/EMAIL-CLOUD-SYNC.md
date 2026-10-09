# Cinema iQ email account and cloud sync

PR43 was checked, updated from main and merged first. This change introduces a separate Firebase email/password account in the phone and TV account settings. Source login is unchanged. No version bump, APK or release.

## Firebase console setup

Email/Password is already enabled. Firestore was created in production mode, so its default rules deny client access. The project owner must open **Firestore Database → Rules**, replace the rules with the complete contents of [`firebase/firestore.rules`](../firebase/firestore.rules), and press **Publish**. Do not switch to test mode. This change does not deploy rules or upgrade the Spark plan.

The supplied Android client configuration is stored as `app/src/main/assets/firebase-config.json` and used to initialize a named Firebase app. It is client configuration, not a service account credential. No Google Services Gradle plugin or signing-secret changes are needed for email/password authentication.

## Use

1. Settings → account → **حساب Cinema iQ والمزامنة**.
2. Create an email/password account, open the verification email and confirm the address.
3. Return to the dialog and select **تحقق من تأكيد البريد**. Only verified accounts can sync.
4. Optionally select **ضم بيانات هذا الجهاز إلى الحساب**. Import requires confirmation because local progress for the same title replaces that account's saved progress. Existing local data is retained.
5. Sign in to the same account on another device, then select the same profile. The signed-in account starts with its own library; device data is not silently uploaded.

## Data and limits

- Syncs profile IDs/names/built-in avatars, favorites, continue-watching, followed-series checkpoints, positions/durations and completion flags. Next-episode records retain the series identity.
- Imported profile photos stay on their original device. Source tokens, passwords, catalog caches, video files and playback/device settings are not uploaded.
- Account-specific preferences/outboxes isolate users. Signed-out mode keeps the original preference files and keys. Original data is available again after logout. Profile choice remains device-local.
- Changes are separate records under `users/{uid}/profiles/{profileId}/entries`. Removing an item writes a tombstone, so a different device's unrelated titles are retained. The latest server-committed write wins for concurrent edits to the same title; an offline device can therefore replace newer progress when reconnecting. This is not conflict-free timeline merging.
- Local progress is saved as before. Network writes are normally batched about every 30 seconds during playback; list changes use a short delay. Backgrounding/profile changes submit pending writes immediately. Durable SDK storage and a per-account outbox retain pending updates. Abrupt process termination can delay updates until the next launch.
- Only the active profile has a live library subscription. Import submits data for all included profiles. Sync runs while the app is active; it is not a continuously running background service. Firestore Spark read/write/storage quotas still apply.
- Rules require an authenticated, verified owner and limit fields/sizes. Unmatched paths and deletes are denied; tombstones represent removal. Data transfer uses the Firebase SDK.

## Validation

Unit tests cover per-title removals, atomic completion/progress resets, grouping episodes by series, excluding source credentials/settings, malformed list items and separation of source IDs. Debug/release compilation and unit tests are run without producing APKs; GitHub also runs R8.

Not yet validated: deployed production rules, verification email delivery, real-device remote/touch input, offline recovery and two-device account switching/sync. These require deployment of the supplied rules and an authorized test account/device session. No live user data was uploaded during development.
