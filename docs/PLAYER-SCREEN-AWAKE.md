# Playback screensaver fix

Scope: ui/PlayerScreen.kt, ui/mobile/MobilePlayerScreen.kt, new ui/PlaybackScreenAwake.kt and tests. The TV player currently has no explicit screen-awake guard. Add a shared lifecycle/player guard to prevent screensavers during active playback and buffering, and release it on pause, completion, background or player disposal. No APK or version bump. Validation pending.
