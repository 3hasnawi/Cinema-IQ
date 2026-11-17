# IPTV source keyboard and focus correction

The user reported a keyboard/focus hang when adding a source in Android 4.37. Input traversal and keyboard dismissal take priority for all future field changes; the instruction is persisted in AGENTS.md and docs/COLLABORATION.md.

Reserved scope: IptvTvForms.kt, a dedicated TV field editor and source-form traversal policy/tests, source-dialog input validation tooling, and the existing Android TV validation workflow. No player redesign, package/version change or public publication. Existing credential storage stays unchanged.

Inspection: source fields currently rely on implicit spatial focus and default IME actions. The dialog's initial-focus request runs outside its own window. There is no explicit keyboard dismissal on submit/cancel and no explicit route through Xtream's conditional fields. These are code findings; runtime reproduction is still pending.

An existing approved signed APK can be used for baseline emulator diagnosis without rebuilding/exporting another APK. A fixed-APK runtime test still requires the separate build authorization specified by AGENTS.md. Validation results and remaining limits will be recorded in the PR.

Implemented repair: TV fields are explicitly navigable controls; OK opens a single-field editor. IME Next/Done commits the draft, hides the keyboard and restores the next form target. Back hides the IME before leaving the editor; cancel returns to the original field. A source-type-aware focus graph excludes hidden fields/disabled Connect, supports arrows and Tab, and leaves Cancel reachable during connection. The dialog requests initial focus inside its own focused window, one time only, applies IME insets, and releases its input session on disposal. Phone fields retain their inline design with explicit Next/Done handlers.

Baseline run 44059034377 reused the signed 4.37 APK from run 44053028135. Genuine emulator capture 02-name-navigation.png shows the keyboard opening on D-pad traversal to the name; 04-enter-next.json records the name still focused after Enter, rather than the address. The old input-method `mInputShown` flag remained stale after the keyboard was visually gone, so keyboard-visibility detection now uses `mImeWindowVis`; that baseline's Back-only assertion is not treated as reliable evidence. Initial dialog focus and leaving the form passed. Earlier attempts failed in the harness before this reproduction and are not app-test results.

The existing validation workflow has a source_input option for an explicitly authorized fixed test-APK build. It remains manual. No fixed-APK runtime pass is claimed yet.
