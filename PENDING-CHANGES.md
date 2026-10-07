# Pending GitHub update setup after 4.2

- Source stays private in 3hasnawi/Cinema-IQ. Public release APKs and update.json go only to 3hasnawi/Cinema-IQ-Releases.
- Include the latest scalable CiQ launcher and TV banner and preserve the approved UI and subtitle handling.
- Add a public release check, manual download/install controls, hash verification and installed-certificate matching.
- CI release workflow is manual only and requires explicit RELEASE confirmation; no workflow or APK build was dispatched during setup.
- Initial updater-capable APK must be installed once before older devices can receive update notices.
- The GitHub integration cannot manage Actions Secrets; the owner must configure signing and publishing secrets before running CI.
