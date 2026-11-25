# Weekly look-over

Started 2026-11-25 (0.39.1). Once a week, a careful debug and visual check, so the code stays
tidy as it grows. **Surgical, not broad:** fix real problems; never change something just to
change it. Each run ends with a patch release (bump `versionCode`/`versionName`) only if
something was fixed, and a short report: what was fixed, what was checked and left alone, and
what couldn't be checked.

## 1. Code

- `cd server && gofmt -l . && go vet . && go test ./...` (all must be clean).
- Android: compile both flavours and read the compiler warnings
  (`./gradlew --offline :app:compileFossDebugKotlin :app:compileGoogleDebugKotlin`), then
  `:app:lintFossDebug`.
- Pebble: `pebble build` in `pebble/app` and `pebble/face` if they changed.
- Look for: logic written out twice (make one helper), dead code (functions, styles and data
  nothing uses), work repeated on every redraw or refresh, things that can crash on bad input,
  and checks that are always true.
- Console: list functions defined in `server/web/app.js` that nothing calls, and style rules in
  `style.css` that are duplicated or overridden by a later rule.

## 2. Visual sweep (console)

- Build and run a test server, sign in, and screenshot every page (Home, Thereabouts and its
  tabs, Chat, every module page, Family, Access & Control, Devices, Settings and its tabs, a
  coming-soon page, the Steward panel, setup) in **light and dark**, and at **phone width
  (390 px)**.
- Look for: labels or fields that don't line up, boxes not clearly separated from one another,
  stray or missing dividers, outlines that don't follow rounded corners, text cut off or
  squeezed, buttons of one kind styled differently, checkboxes where the design uses switches,
  colours or accents off the design language (serif headings, Accent2 for small accents, rounded
  cards, no emoji in text), and anything that overflows at phone width.
- Re-screenshot after fixing, to confirm.

## 3. App and watch

- The app can't be run in the build environment: review the changed screens' code for the same
  things (shapes and borders that match, the design language, crash risks).
- Compare against the latest user feedback in the Roadmap.

## Log

- 2026-11-25 (0.39.1): first run. Field heights and label alignment, switches, backup buttons,
  PIN divider, phone layouts (chores, Pantry, Home), Home map refit; dead console code; one
  backup helper; Steward people helper; Pantry date formatting.
