# Automatic TV playback prompt focus

Scope: PlayerScreen.kt only. When the intro/next-episode prompt becomes visible, move remote focus to it after attachment. Do not repeatedly steal focus while the same prompt remains visible. Respect open panels, episode strip, seeking and error/buffering states, and restore playback focus when the prompt disappears. No APK, version or publishing changes. Validation pending.
