# Apple TV detail hero trailer sources

Research date: 18 November 2026. This document records provider evaluation only;
it does not activate video previews or change the existing trailer action.

## Native playback requirement

The requested behavior is a muted trailer behind the title and actions in the
detail hero, with the existing poster as the fallback. A native AVPlayer needs an
authorized direct MP4 or HLS URL. The existing YouTube search client returns video
identifiers, not such playback URLs. A YouTube API key alone does not solve this.

## Recommended provider: Origin Nexus (Fabric)

[Origin Nexus](https://www.fabricdata.com/origin-nexus) describes movie and TV
coverage and promotional video delivery in MP4 and HLS, among other formats.
This is the best technical fit among the evaluated providers for both title
types and a native tvOS background preview.

Production integration requires a provider account, API access and agreed
distribution terms. Confirm native in-app autoplay, territories, title coverage,
languages, delivery cost and caching rules before selecting a plan. No public
numerical price or free production allowance was established. The published
capabilities have been reviewed; authenticated lookup and native playback with
this provider have not been tested.

## Alternative: MovieGlu

[MovieGlu's trailer documentation](https://developer.movieglu.com/company/images-and-trailers/)
describes direct MP4 film trailers, available in High/Medium variants. It does
not establish equivalent TV-series coverage or availability for every film.
[Its evaluation terms](https://developer.movieglu.com/company/terms-of-use/)
exclude publishing retrieved data to public app users and storing the assets on
our own server. Public distribution therefore needs a separate full agreement.

## Other evaluated routes

- [KinoCheck](https://api.kinocheck.com/) provides matching metadata and YouTube
  video identifiers; [its permitted embedding route](https://www.kinocheck.com/contact)
  uses the YouTube player. It does not establish direct native stream delivery.
- Public iTunes movie search/lookup probes returned no usable movie preview
  results during this evaluation. This is an observation, not a guarantee about
  the entire catalog or all regions.
- Live Cinemana trailer fields could not be verified from this environment.
  Do not assume they contain authorized direct trailer URLs.

## Implementation boundary once access is available

Keep the existing hero layout. Resolve the matched trailer through a small
provider client, then play it muted using AVPlayer with a poster crossfade only
after a frame is ready. Stop playback when leaving the hero, opening the movie,
changing titles or backgrounding the app. Respect preview/autoplay preferences
and reduced motion; missing, expired or failed trailers fall back to the poster.
Do not reuse movie playback as a substitute trailer or extract YouTube streams.

No provider account was created, no paid plan selected and no vendor contacted.
No secrets or playable commercial trailer URLs are included in this change.
