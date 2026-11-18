# Android TV television update

This task implements the approved translucent channel/category navigation design for Android TV only.

- Open the most recent available channel from the selected source without flashing a channel grid.
- Keep a fullscreen player underneath the OK channel overlay; physical Right opens categories and Left returns to channels.
- Use a shared graduated glass material and a larger borderless station logo above the information strip.
- Prioritize search focus, IME completion, keyboard dismissal, and restoration before cosmetic details.
- Provide reversible device-local category deletion and PIN protection across all live playback entry points.
- Keep the existing Media3 player, with automatic buffering, decoder selection, and paced reconnects. Retain quality/audio/subtitle controls.
- Preserve existing subscriptions, channel identifiers, favorites, and history. Do not alter movie playback or Apple applications.

Implementation and verification are tracked in the pull request. No APK export or public release is authorized by this task.
