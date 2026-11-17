# Cinema IQ project rules

- The approved visual baseline is the user-provided CinemaTV Apple TV 0.9.0 archive.
- Do not change its UI, layouts, resources, or artwork unless the user explicitly requests a design change.
- The user requires explicit approval before every APK build/export. Do not infer build approval from a request to collect or edit changes.
- GitHub setup and updater edits are authorized. A new APK build or public release still requires explicit approval.
- Keep package com.cinemaplus.rebuilt for update compatibility. Increase versionCode for future authorized releases.
- Use English code comments and Arabic RTL UI with Cairo.
- Claude and ChatGPT share this repo: follow docs/COLLABORATION.md (branch per task, PRs only, one open PR per hot file, pull before work, no builds without explicit approval).
- For every change involving input fields or an on-screen keyboard, prioritize focus traversal, IME Next/Done, keyboard dismissal, and restoration of focus to the correct control. Validate the actual input flow when a runtime is available; compile/unit checks alone do not establish that remote/keyboard interaction works. State untested runtime coverage explicitly.
