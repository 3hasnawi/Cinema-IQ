# Apple TV artwork and detail focus correction

User-requested follow-up to PRs #146 and #147: preserve the complete hero artwork when a source supplies portrait or differently proportioned imagery; remove the description's white focus card; refine the trailer and library action icons for Apple TV without altering navigation or playback.

Reserved files: ios/CinemaIQTV/TVSupport.swift, TVDetailView.swift, TVHomeView.swift; a dedicated TVHeroArtwork view; independent Core artwork-priority policy/tests; focused native visual tests/fixtures and their workflow as needed. Existing long-description reading remains available through an explicit action rather than a focusable paragraph. Main is the source; no Android changes. The television name/antenna icon belongs to PR #147.

The home showcase now requests matched TMDB metadata through the existing client and cache, and both home and detail prefer its landscape backdrop. Provider artwork is a fallback. As requested, plain page backgrounds use a lighter slate gradient. TMDB configuration still comes from the existing build secret; no key is added to source.

Validation pending. No merge, version bump, IPA export or publication requested for these edits.
