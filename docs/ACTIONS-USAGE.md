# GitHub Actions usage and storage

- Pull-request checks compile and run unit tests without creating an APK. Older checks for the same PR are automatically cancelled.
- Release builds remain manual and require the owner's explicit build/publication instruction under `COLLABORATION.md`.
- Release artifacts are retained for three days. This does not expire APKs published in the public releases repository.
- Do not run duplicate GitHub release builds for the same approved source version. Use an existing completed artifact during its retention period when available.
- Published releases retain their signed APK and `update.json`; deleting an Actions artifact does not remove the release.

## Account controls (owner access required)

In account Settings → Billing and licensing → Budgets and alerts, review the Actions allowance, configure spending limits and select stopping usage at the budget limit if offered. Notifications alone do not stop spending. Enable consumption alerts when supported by the account's billing interface. The current connector cannot read or change account billing settings (HTTP 403); no spending cap has been set through this change.

Review Actions usage periodically. Changing artifact retention only affects new uploads; old unneeded artifacts must be removed separately. Keep the latest trial artifacts until their APKs have been downloaded or published.
