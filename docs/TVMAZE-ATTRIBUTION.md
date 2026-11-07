# TVmaze series cast metadata

Cinema iQ uses the [TVmaze public API](https://www.tvmaze.com/api) as a keyless fallback for missing **series** cast portraits. TVmaze data is provided under [Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/). TVmaze's API documentation permits hotlinking its image CDN.

## Attribution and adapted data

The TVmaze name and CC BY-SA 4.0 notice appear immediately below a cast shelf whenever it uses TVmaze data. Selecting the notice displays the exact source show's URL and the license URL, both as readable text and QR codes usable from a television. It also describes the adaptation: cast is combined with names from other sources, preserving unmatched names and their placeholders.

The TVmaze-derived portions of the combined cast data are available under the same CC BY-SA 4.0 terms. This notice concerns the metadata, not the application source code or unrelated provider data. Preserve the source URL, license notice and description of changes when redistributing TVmaze-derived data. No endorsement by TVmaze is implied.

## Identity and limitations

- When a validated IMDb series ID is available, it is sent to `/lookup/shows?imdb=...`. The returned show must report that same IMDb ID before `/shows/{id}/cast` is accepted.
- If neither metadata provider supplies a valid IMDb ID, series roots may use `/search/shows?q=...` with the source English title (or a Latin display title) and its original premiere year. Matching ignores only case and whitespace. Exactly one same-name result must exist, and its premiere year must match; same-name remakes, fuzzy names, missing years, non-Latin titles and episodes are rejected. A valid IMDb lookup miss or conflicting provider IDs never triggers title search. Search results are not persisted, so ambiguity is rechecked. This conservative fallback may leave legitimate shows unmatched.
- There is no user account, API credential, person search, fuzzy name search or movie-to-series substitution.
- Missing portraits are enriched only when one primary credit and one member of the verified show's cast share the same complete normalized name. Ambiguous and unmatched primary names keep their placeholders. Additional cast members are shown under their own names with namespaced identities.
- Existing TMDB photos and credit order are preserved. HTTPS portraits must belong to the documented TVmaze image host.
- IMDb lookups use a cache to reduce requests. Network failures do not become permanent misses. The app continues to show its existing credit names if TVmaze is unavailable or has incomplete coverage.
- TVmaze covers television shows. Movie cast portraits remain dependent on an authorized metadata source. The release's empty-TMDB-key protection is unchanged.

IMDb IDs, source catalog titles and premiere years are public work metadata. No viewing history, title ownership, account identifier or other personal data is included in these requests.
