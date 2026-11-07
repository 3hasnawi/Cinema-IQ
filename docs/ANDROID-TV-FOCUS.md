# Android TV focus and scroll audit

## Entry and return are different operations

- Fresh app/section entry: `RegisterPageEntry` publishes a stable first target and a suspend preparation step. The root waits for registration, resets the relevant lists, materializes the first card, then requests it. A real but unattached target never falls through to spatial search in the middle of the screen. Empty/error pages may focus their existing header/retry controls.
- Explicit remote input cancels a delayed startup entry request, so a late network response cannot take focus away from the user's rail navigation.
- Returning from a title/player: the containing home/shelf/grid restores its title ID and saved scroll. The shared FilmCard no longer schedules its own delayed focus, which competed with section entry and explicit row navigation.
- Returning from the rail: restores saved focused descendants. Only if that path disappeared does the root call the prepared first-entry fallback.

## Reviewed routes

| Route | Intended focus |
| --- | --- |
| Profile chooser | First profile, retried on frame boundaries |
| Home fresh entry | First featured title, or first available shelf card |
| Home Down / shelf Up/Down | First card of destination shelf |
| Movies/Series genre pages | First genre's first card; each vertical transition resets horizontal position before reveal |
| Flat catalog / saved/watched grids | First card on fresh entry; vertical arrows select the first column of the adjacent row using the grid's actual column count |
| Search | First keyboard key on entry; Down from filters selects the first result; result rows use the same first-column rule |
| Explore | First mood on entry; four-column row transitions select the first mood; choosing a mood requests fresh first-entry into its shelves; Back restores the opened mood |
| Settings | Account section on fresh entry; changing panes resets the panel scroll; rail return preserves the previous control |
| Grid/shelf boundaries | No wrap into a middle card; explicitly reveal load-more/retry controls when present; Up returns to a header or rail |
| Details/player | Existing deliberate resume/play/selected-option focus retained; their remote routing was reviewed without replacing it |

## Scroll ownership

Shelf pages and grids suppress default vertical bring-into-view while their navigation controller reveals a target. Horizontal shelf centering remains handled by the existing LazyRow. Materialize an off-screen target before requesting focus, cancel pending scroll/focus jobs when a container loses focus, and keep the full Left press consumed across rail exit.

The page-entry registration uses ownership tokens so disposal of an old layout cannot erase a new layout's target. Layout changes use current list IDs/column counts. No persistent library/profile storage keys change.

## Validation limits

Unit tests cover entry decisions, row-first destinations, incomplete rows, invalid positions, changed column counts, held Select/Left presses and scoped restoration. CI compiles Android debug/release, runs unit tests and runs release R8 without APK packaging.

No local adb/emulator or physical Android TV was available. Real-device acceptance must cover startup with slow/empty sources, fresh rail section selection, deep scroll, held arrows, details/player return, source/filter changes, and error/load-more footers. Passing code checks does not replace that test.
