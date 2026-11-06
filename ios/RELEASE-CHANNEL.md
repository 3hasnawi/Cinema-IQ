# iPhone release channel

The `ios` branch is the user-requested independent Apple release channel. Android remains on `main`.

Preparation in progress: bring over only the iPhone Cast changes from PR #104; update the existing manual IPA workflow for iPhone-only export and optional publication to Cinema-IQ-Releases; enable Core checks for PRs targeting `ios`. Files reserved: `ios/CinemaIQ/CastSender.swift`, iPhone app/player, shared Core receiver policy/tests, SDK preparation script, `ios/project.yml`, `.github/workflows/ios-unsigned-ipa.yml`, `.github/workflows/ios-core-checks.yml`, and this document.

The version increment is a separate PR. No Android code, version, signed APK or Android update manifest changes are included.
