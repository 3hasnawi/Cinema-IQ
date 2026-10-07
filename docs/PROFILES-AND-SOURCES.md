# Profiles, sources and warm palette

- **Profiles** (up to 6): each has its own favourites, continue-watching, playback and subtitle settings.
  The main profile keeps the original `cinema_library` preferences file, so existing data carries over.
  The source account (Cinemana login) stays shared. The picker appears at launch when more than one
  profile exists; Settings > Profiles switches or adds one. Removing a profile deletes only its own data.
- **Sources**: Settings > Sources lists the three choices (Cinemana + VODU, Cinemana only, VODU only)
  with the selected one marked, instead of cycling a single row.
- **Palette**: warm tokens (`Ink`, `Panel`, `PanelHi`, `Accent` orange) replace the blue-violet ones; all
  tokens stay in `ui/CinemaApp.kt`, so reverting is a single edit.
- Not included by design: friends/activity, Trakt, Letterboxd, debrid, P2P, live TV.

## Explore (phase 2)
- New page "استكشاف" (TV rail and phone bar): eight moods (crime, comedy, horror, sci-fi, romance, action,
  family, real stories). A mood gathers the source's own categories whose titles match its keywords
  (`data/Moods.kt`) and shows them as movie and series rows. Only categories the source really has appear.
- Uses Cinemana categories; with "VODU only" the page explains that and points to Settings > Sources.
- Not included: following a director/cast thread (needs new TMDB credit calls that cannot be tested here).

## Settings hub (phase 3)
- TV Settings is a hub of six cards (sources and content, account, profiles, subtitles, playback, update
  and about). Each opens a short page; Back returns to the hub. The subtitle page shows a live sample.
- Not included: settings search and "different from default" filters. The phone keeps its single list.
