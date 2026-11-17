# IPTV source keyboard and focus correction

The user reported a keyboard/focus hang when adding a source in Android 4.37. Input traversal and keyboard dismissal take priority for all future field changes; the instruction is persisted in AGENTS.md and docs/COLLABORATION.md.

Reserved scope: IptvTvForms.kt, a dedicated TV field editor and source-form traversal policy/tests, source-dialog input validation tooling, and the existing Android TV validation workflow. No player redesign, package/version change or public publication. Existing credential storage stays unchanged.

Inspection: source fields currently rely on implicit spatial focus and default IME actions. The dialog's initial-focus request runs outside its own window. There is no explicit keyboard dismissal on submit/cancel and no explicit route through Xtream's conditional fields. These are code findings; runtime reproduction is still pending.

An existing approved signed APK can be used for baseline emulator diagnosis without rebuilding/exporting another APK. A fixed-APK runtime test still requires the separate build authorization specified by AGENTS.md. Validation results and remaining limits will be recorded in the PR.
