# Netfly provider investigation

Scope: an Apple TV provider accepting a user's own Netfly subscription through normal server authentication. No Android changes or release/version changes.

## Verified public evidence

- https://key.netflyapp.com/ describes license keys and device bindings.
- The dashboard's public JavaScript authenticates its session with a Bearer token and reads `/api/v1/app/check`, `/api/v1/user/license`, and `/api/v1/user/license/devices`.
- The official Android installation file linked by https://netflyapp.com/ar/download contains route names for device sessions, activation, live categories, channel lists and channel playback. String presence does not establish a working external-client contract.
- https://github.com/NFLAB-OF/NFLAB publishes installation files and a README, not application source or a documented third-party SDK/API.

## Work boundary

Use genuine subscription authorization and an honest Cinema iQ client/device identity. Do not embed a user's key in code, fixtures or repository history. Do not reuse extracted app credentials/signing secrets, spoof device attestation, bypass device limits, or disable server/TLS validation. No account mutation or device activation has been attempted during the public-file investigation.

A successful dashboard session is insufficient evidence of channel playback authorization. Verify license authentication, device/session requirements, catalogue response models, and authorized playback responses separately before enabling a provider in production. If the provider requires a client identity or SDK issued by Netfly, record that prerequisite rather than presenting a non-working code-entry form.

## Planned files

Shared provider logic and tests should be separate files under `ios/Core/Sources/CinemaCore/` and `ios/Core/Tests/CinemaCoreTests/`. Apple TV integration may touch `ios/CinemaIQTV/IPTV/TVIPTVSourceForm.swift` and existing shared IPTV dispatch/storage only after the authentication contract is verified. Preserve existing M3U/Xtream data and UI behavior.
