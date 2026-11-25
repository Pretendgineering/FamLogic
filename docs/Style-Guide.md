# FamLogic style guide

One look and one voice everywhere: the Android app, the web console, the Pebble, and what's
coming (the TV launcher, display tablets, TRMNL e-ink screens, a PWA). When something here and
the code disagree, fix the code (or, if the code is right, update this page in the same change).
The weekly look-over ([Maintenance](Maintenance.md)) checks against it.
This is a developer document: it lives in `docs/`, not in the in-app wiki, which is for using
FamLogic.

## 1. Voice

- **Say it short.** A description, hint or empty state is one plain line. Details go in the wiki.
- **Plain words a child or grandparent understands.** "Fences", "Check in", "Hold for SOS";
  never "geofence", "authenticate", "sync conflict".
- **Talk to the person:** "Your family has been alerted", not "Alert dispatched".
- **No emoji in text.** Emoji are for people's own icons and nudges only, never in labels,
  descriptions, titles or empty states.
- **Don't assume one kind of family.** No school, two parents or Monday-to-Friday by default.
  Examples rotate: "Co-op", "Grandma's", "Practice", "Work".
- **Names:** every module has its own name and a plain name (Thereabouts / Map, Chitchat / Chat,
  Datebook / Calendar, Odds & Ends / Lists, Upkeep / Chores, Rhythms / Routines, Pantry / Meals,
  Steward / AI). Show whichever the person chose ("Plain Labels"). Use the same pair everywhere.
- **Sentence case** for buttons and headings ("Add a chore"), except section labels in the
  console, which are small caps-style uppercase (`DUE NOW`).
- **Numbers and dates:** "2 min ago", "Thu 26", "3:30 PM" in the person's locale; `YYYY-MM-DD`
  only in files.

## 2. Colour

Every surface takes its colours from the theme's tokens, never fixed values (except SOS red and
picture icons). Each theme has light and dark versions.

| Token | Use |
|---|---|
| `bg` | the page behind everything |
| `surface` | cards, panels, tiles, sheets |
| `surface-2` | fields, chips, quiet buttons, the inside of a card |
| `text` / `muted` | words / secondary words (times, hints) |
| `line` | dividers and outlines |
| `accent` (Material `primary`) | **actions**: the main button, the selected tab, links |
| `accent-soft` (`primaryContainer`) | tonal buttons, selected rows, the Steward button |
| `secondary` (**Accent2**) | **small accents only**: section and tile icons, the family name and crest ring, switches that are on, chosen chips, the dot on coming-soon items |
| `sos` | SOS only (and "late") |
| `danger` | deleting and removing |

- One main-colour action per area. Everything else is tonal, outlined or a link.
- Never use the main colour for decoration, or Accent2 for a big fill.
- Check every new screen in **light and dark** and in at least one non-default theme.

## 3. Type

- **Headings and names:** Liberation Serif (bold for titles, people's names and card titles).
- **Body, labels, buttons, fields:** the system sans (Roboto on Android, `system-ui` on the web).
- **SOS:** bold sans everywhere ("Hold for SOS", the SOS screen), never the serif.
- **The family crest:** UnifrakturMaguntia, the family's initial, centred by its ink (not its
  line box) in a circle ringed in Accent2.
- **Watch:** Gothic for text, the serif faces bundled with the watchface for the time and date.
- **Sizes:** page title ≈ 1.85rem / `headlineSmall`; card title 1.1–1.2rem / `titleMedium`;
  body 15px / `bodyLarge`; hints `.85rem` / `bodySmall`. Big view ("Easier to use") scales
  everything up a step.

## 4. Shape and space

- **Rounded Material You cards with a little depth** (a soft shadow), never flat boxes or hard
  corners.
- **Radii:** pills and buttons fully round (999px); home tiles and big panels 22dp/px; cards
  and sheets 18; fields, chips and small cards 12; tiny badges 8. Anything drawn on top of a
  shape (a border, an edit outline, a highlight) **uses the same radius as the shape under it**.
- **Spacing:** a 4px grid. 12px between cards and between form rows, 8px inside tight groups,
  16–24px page margins (16 at phone width).
- **Boxes are clearly separate:** every card, panel or row group has its own background or
  divider; things that belong together line up on a shared grid (like the Pantry week).
- **Dividers:** `line` colour, 1px, *between* items, never above the first or below the last.
- **Lists:** rows 44px or taller (touch), the avatar or icon on the left, the main text in the
  serif or sans as the page uses, the secondary line muted, actions on the right (they wrap
  below the text at phone width rather than squeezing it).

## 5. Icons

- **Grid and weight:** 24-unit square, 1.8 stroke, round caps and joins, like the console's
  icons. The app uses Material icons where one fits and FamLogic's own (`FamIcons`) where none
  does.
- **FamLogic's own:**
  - SOS: a round beacon with an exclamation mark and signal waves.
  - Upkeep: a small scroll with two lines.
  - Steward: two sparkles.
- **Minimal:** an icon must read at 16px. If it doesn't, simplify it.
- **Colour:** icons in Accent2 (or `muted`), except in the selected state.
- **Picture icons** (for children who can't read yet, and grandparents): literal, colourful
  drawings (a tiny map, a paper plane, a red alarm, a cream scroll). Every new section needs
  one.

## 6. Components

- **Buttons:** main (filled `accent`), tonal (`accent-soft`), outlined (secondary), link
  (quiet text). Buttons side by side are the same kind and height.
- **Switches, not checkboxes,** for anything on or off (the pill switch, Accent2 when on).
  Checkboxes only for ticking items off a list.
- **Forms:** the label above its field; fields in a row are one height so their labels line
  up; fields and text boxes look alike (`surface-2`, no outline until focused).
- **Chips:** pill-shaped, a soft outline in Accent2; chosen chips filled softly in Accent2.
- **Tiles (app home):** a shortcut when small (icon, name, a count); a live mini version at
  2×2 or larger; one wide or tall, a strip of its newest items. All tiles use the same card
  and radius; the edit outline follows it.
- **The Steward button:** a small round tonal button in a bottom corner of every screen except
  Chat and the map, opening Steward in a sheet or panel that knows the page. Only when Steward
  is on.
- **Empty states:** one muted line, no picture, no emoji ("Nothing due.").
- **Coming soon:** a muted dot on the nav item, a "Coming soon" page or tile with the plan in a
  short list, and a line saying Steward can keep notes for it already.
- **SOS:** red, unmistakable, held (never tapped) to send; the beacon icon; bold sans.

## 7. Motion

- Short and calm: 150–260ms, ease out. Nothing bounces except the talk button's "listening"
  pop. No motion that carries meaning on its own; respect "reduce motion".

## 8. Accessibility

- Contrast of at least 4.5:1 for text in every theme, light and dark.
- Touch targets 44px or larger; the big view makes them larger still.
- Every icon-only button has a label for screen readers.
- Colour is never the only signal (SOS also says "SOS"; stale people are greyed *and* say how
  long ago).

## 9. Per surface

### Android app (phones and tablets)
Material 3 with the theme's colours and `AppShapes` (8 / 12 / 18 / 24 / 30). Tabs at the
bottom on phones, a rail on the right on tablets. Screens have their own top bar. Edge to edge,
with system bars tinted to the surface.

### Web console
The same tokens as CSS variables (`--accent`, `--secondary`...). A rail of sections on the left
(a top bar at phone width). Pages are panels up to 760px wide (880 for Access & Control); Home is
a dashboard (map and feed on top, panels in a row below). Must work at 390px wide.

### Pebble (watchapp and watchface)
Colour watches use the person's colour and emoji; black-and-white ones the initial with rings
for state. Keep 6px or more from the screen's edges and a few pixels between stacked text. The
time and date in the serif, everything else in Gothic, SOS in bold sans. Round watches centre
everything.

### TV launcher and display tablets (planned)
Read from across the room: everything 1.5–2× the phone sizes, the serif for headings, no small
print. A row of rounded cards (three in view) moved with the D-pad, app shortcuts below. The
background drifts from a light morning palette to a dark night one; nap or quiet time has its
own calm look. Messages pushed to the screen appear large, in a card, with who sent them. Low
motion; shift the layout slowly to avoid burn-in.

### TRMNL and other e-ink screens (planned)
Black and white (or a few greys): no colour can carry meaning, so use weight, size, outlines and
icons instead. The serif for headings, a crisp sans for the rest, nothing under ~14px. No
motion, no shadows; cards become 2px outlines with the same radii. Layouts are fixed and rendered
on the server as images; refresh rarely (minutes), so show times as "at 3:30" rather than
"2 min ago".

### PWA (planned)
The console's look and tokens, installable, with the app's tab layout at phone width. It should
feel like the Android app, not a website: no hover-only controls, 44px targets, the Steward
button, and offline-friendly empty states.

## 10. Before you ship something visual

1. Light, dark and a second theme.
2. Phone width (390px) and a big screen.
3. Picture icons and the big view, if it's in the app.
4. Labels line up, boxes are clearly separate, dividers only between items, outlines follow
   the shapes they sit on.
5. Words: short, plain, no emoji, both names (own and plain) right.
