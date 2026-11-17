# Apple TV artwork and detail focus correction

User-requested follow-up to PRs #146 and #147: preserve the complete hero artwork when a source supplies portrait or differently proportioned imagery; remove the description's white focus card; refine the trailer and library action icons for Apple TV without altering navigation or playback.

Reserved files: ios/CinemaIQTV/TVSupport.swift, TVDetailView.swift; a dedicated TVHeroArtwork view; focused native visual tests/fixtures and their workflow as needed. Existing long-description reading remains available through an explicit action rather than a focusable paragraph. Main is the source; no Android changes. The television name/antenna icon belongs to PR #147.

Validation pending. No merge, version bump, IPA export or publication requested for these edits.
