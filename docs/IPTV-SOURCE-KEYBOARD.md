# IPTV source keyboard and focus correction

The user reported a keyboard/focus hang while adding a source in Android 4.37. Input focus traversal, IME Next/Done, keyboard dismissal and focus restoration take priority for every input change. This instruction is persisted in AGENTS.md and docs/COLLABORATION.md.

## Implementation

TV fields browse as noneditable controls; OK opens a single-field editor within the same source-dialog window. The editor uses a native Android EditText so that Android owns and closes its actual input connection when the field is removed. Native Next/Done and physical Enter commit the draft and hide the IME. Back hides the keyboard before leaving the editor, and cancellation restores the edited control. Cairo, Arabic labels and the existing dialog/buttons remain in use. Phone fields retain their inline layout with explicit Next/Done handlers.

The source-aware focus graph excludes hidden Xtream fields and disabled Connect, supports arrows/Tab and keeps Cancel reachable during a connection. Browse focus is restored after the IME has closed and the dialog owns window focus; TV input mode is explicitly Keyboard. Closing the source dialog restores the toolbar control after the activity regains window focus. Source credentials remain transient while editing and existing encrypted storage/preference keys are unchanged.

## Runtime evidence and limits

The baseline reused the approved signed 4.37 APK from run 44053028135 in native run 44059034377. Genuine captures show that D-pad focus opened the keyboard while browsing and Enter did not advance from Name to Address. Input-service visibility flags were unreliable; the harness now inspects the actual WindowManager IME window.

A first Compose-editor repair passed 14 native cases in run 44065281676, but expanded sequential-input testing exposed intercepted remote events after completion. Later traces showed the keyboard window hidden and its text connection cleared, while killing the emulator keyboard process restored remote dispatch immediately. These partial/failed runs are not acceptance evidence for the current editor. The final revision uses a real Android editor rather than a virtual Compose text connection.

The manually approved current build/native regression is run 44073925657 at db950eb. Its final assertions, APK SHA-256 and genuine captures are recorded in PR #141. The regression covers initial focus, browse-versus-edit, physical Enter, native IME Next/Done, M3U/Xtream URLs, username/password, masking, arrows through Connect/Cancel, keyboard Back, editor cancellation and reopening the source form. Fixtures use dummy credentials and never connect to a live subscription. Diagnostic IME recovery is used only after failure and never counts as a passing case.

The source_input workflow path builds the authorized Debug APK; reuse_input_run_id replays the same APK without rebuilding when only the harness changes. Early launcher, input-source/action-key and emulator boot failures are reported separately. Three pure focus-policy tests and compile/release R8 checks run independently of native input coverage.

No physical TV, live-provider connection or production update is certified by this task. Debug APKs cannot update the signed Release application. There is no version bump, main merge or public publication.
