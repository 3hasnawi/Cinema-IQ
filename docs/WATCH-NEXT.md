# Android TV "Watch Next" row

The profile's continue-watching list is mirrored to the Google/Android TV home screen (up to 10 titles, one per series, newest first) through `androidx.tvprovider`.

- Sync happens when the player closes, a card is removed or marked watched, and when the app goes to the background (TV only).
- Selecting a card opens the app and plays the title from the saved position.
- The row belongs to the active profile on the device; switching profile republishes on the next sync.
- The system owns the row. If the provider refuses access the app works unchanged.
- A separate recommendations row (like the streaming apps) is not included; it needs Engage SDK, whose requirements are unconfirmed.

Not verified on a device yet.
