# Account navigation fix

User report: TV remote focus is trapped in the inline source username field, with no reliable route to password or the cloud account button. Latest main also combines optional source credentials in CloudAccountDialog (PR48).

Scope reservation: independent TV account/form navigation helper, CloudAccountDialog, CinemaApp and MobileCinemaApp caller cleanup. Replace inline TV fields with separate explicit account entry buttons; keep source authentication and Firebase authentication independent. Configure D-pad field transitions and IME Next/Done. No version bump, APK or release. Validation pending.
