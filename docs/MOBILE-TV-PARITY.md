# Phone interface follow-up: closer to the TV experience

Only files under `ui/mobile` and this note changed. No TV file was touched.

- Phone home now asks TMDB for the featured titles and the first titles of the first shelves (`vm.enrich`), so sharper artwork and missing synopses reach the phone too. Without a key nothing is requested.
- Home hero: the same selection as the TV (best rated interleaved with newest), swipeable and advancing every 8 seconds. Artwork goes through the shared `HeroBackdrop`, so portrait posters are framed instead of stretched.
- Cards show the rating chip and a kind dot (gold for films, teal for series); shelf titles use the shared gold section header with a count; Movies, Series, Search and Library get the shared page header.
- Details: shared hero artwork, kind badge, rating and info chips, a trailer button (full-width window using the existing resolver and player), and a "similar titles" row. A new list state per title, so opening a similar title starts at the top.
- The lighter palette from PR #1 applies automatically because the phone uses the same colour tokens.

Not compiled or run in the editing session (no Android SDK available there); JVM test added for the featured-title selection. A phone test is still needed: hero swipe and auto-advance, trailer window, similar titles, and the rating chip placement in RTL.
