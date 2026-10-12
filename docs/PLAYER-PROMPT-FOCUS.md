# Automatic TV playback prompt focus

TV prompt focus moves automatically once per visible intro/next-episode prompt. Focus waits until the target attaches and respects open panels, episode strips, active scrubbing, buffering and errors. A prompt first seen while blocked is focused after that interaction ends. Moving away while the same prompt remains visible does not trigger repeated focus theft. Hiding the prompt restores playback focus; controller visibility changes no longer overwrite focus already on the prompt.

Scope: PlayerScreen.kt and docs/PLAYER-PROMPT-FOCUS.md. No phone touch behavior, timings, auto-next behavior, design, version, APK or publishing changes.

Validation: local Debug/Release Kotlin compilation and 180 existing JVM tests passed. git diff --check passed. GitHub test/R8 pending. JVM tests do not validate Compose focus; no physical TV/remote test was run. No APK was built.
