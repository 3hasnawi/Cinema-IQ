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
