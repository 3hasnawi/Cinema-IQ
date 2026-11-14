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

## Static client findings (2026-11-14)

The official Android download was inspected without executing it, logging into the user's account, activating a device or sending a subscription key. Its SHA-256 is `fd3d8fb48838f48aadb45e2908034d95fb030d526f523475ab0ffb6e8c5783c3`.

- The remote request helper reads the installed APK signing certificate and supplies its fingerprint in the `x-sign-256` header. It also sends device identity, app identity/version and operating-system metadata. This is evidence of the client request format, not proof of which checks the production server enforces.
- Requests for activation, device sessions, live categories/channel lists and channel playback are routed through `clientgosdk.Clientgosdk`, via `initSdk` and `httpProxy`. The SDK is supplied through Android native libraries, not an independently documented REST contract.
- The official public GitHub repository contains a README and binary releases, not the SDK source or a third-party integration agreement. Searches for a public Netfly SDK/clientgosdk repository returned no matches; this is not proof that a private partner SDK does not exist.
- The dashboard's ordinary Bearer-authenticated account/license endpoints do not establish a contract for obtaining channel playback authorization.

## Current outcome

**No working Netfly provider has been implemented or enabled.** The verified material is insufficient to promise that an independent Cinema iQ client can activate and obtain authorized playback using only the license key. There is no claimed successful server authentication, real account test, catalogue request or channel playback.

A legitimate implementation needs a documented external-client activation/playback API, or a Netfly-supplied SDK supporting tvOS and an issued Cinema iQ client identity if required. The user's subscription must continue to be validated by Netfly. Alternatively, a provider-issued M3U/Xtream export works with the existing release.

The task is blocked on that external-client contract. No original application signature, SDK credential, device attestation result or secret has been extracted into project code; no unsupported signing/authentication workaround is proposed. Do not substitute a visible code-entry form or mocked authentication for a working provider.

This draft changes investigation documentation only. The planned source-form/storage reservations are released while blocked: there are no app-code edits in this PR and no Android, UI, storage, version, build or publication changes. Initial Core and required PR checks passed; the unrelated TV simulator run was cancelled because only documentation changed.
