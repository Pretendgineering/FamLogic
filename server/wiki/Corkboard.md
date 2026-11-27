# Corkboard: FamLogic on the TV

Corkboard turns a TV (or a tablet on the wall) into the family's board. One screen, nothing to
scroll: a greeting for the time of day, the clock, and the day's **Routines** (the ones on now,
with their checklists, and what's later today), **Upkeep** (chores due) and the **Docket**
(to-dos, and how much is on lists like Shopping). The screen follows the sun at home: light in the
morning, slowly darker towards the evening, dark at night (it uses the fence called Home to know
when the sun rises and sets).

Down the right side are buttons for the **Map** (where everyone is), the **Feed** (today so far),
**Steward** (ask by talking into the remote), **Datebook** (what's coming up) and **Pantry**
(meals) and **Huddle** ([family meetings](Huddle.md)), then **Apps** (every app on the TV) and **Settings**. These are the
**Quick Access Modules**: choose which show in **Settings → Quick Access Modules**. Steward also has its own round button at
the bottom right, always there. (With Plain Labels switched on, they're called Map, Calendar, Meals and Meetings.)

- **Map:** the family down the left; choose someone to zoom to them, or Everyone.
- **Feed:** today so far, across the screen. Choose an entry to go where it happened (the map,
  zoomed to that person, or Upkeep).
- **Datebook:** the list, or the calendar as 3 days, the work week, the week or the month, each
  event in its person's (or its calendar's) colour. In the month, move to a day to see what's on.

Routine steps show a picture next to them (a toothbrush for "Brush teeth"), so younger
children can follow them.

The whole screen stretches to fit the TV, whatever its resolution or display size. If your TV
still cuts off the edges, turn up **Settings → Fit to the screen**.

From a phone, parents and grandparents can:

- **Start quiet time** (nap time) for 30 minutes, an hour, two hours or until they end it. The TV
  shows a calm, dark quiet-time screen. It ends by itself, from a phone, or with **End quiet
  time** on the TV's remote.
- **Send a message** to the TV ("Inside voices please, the baby's asleep"). It rises onto the
  screen with a soft chime and stays, with who sent it, until someone presses OK. **Read aloud**
  reads it out.

Both are on the **TV** tile in the app (add it from the tile drawer) and on the **TV** page in
the web console. With more than one display, the tile asks which one first (or all of them).

## Setting up a TV

1. Switch on **Corkboard** in Settings → Modules.
2. Install `famlogic-tv.apk` on the TV, from the release page (Obtainium works: point it at the
   FamLogic releases and pick the `famlogic-tv` file).
3. In the web console, open **Devices → Pair a family display**. It shows a code.
4. Open FamLogic on the TV, type your server's address and that code, and choose Connect.

The TV signs in as a family display: it shows the family's day but never shares a location and
can't post. On the console's Devices page it's listed by its own name ("Living room TV"). The name is
what tells displays apart: pair a TV again with the same name (after a reset, say) and it takes
over its old listing and settings. Signing a display out removes it from the list.

## The TV's apps

The launcher along the bottom holds up to 1 to 6 apps (choose how many in **Settings**), each shown as
one big picture and centred as a group. It starts with Jellyfin, Spotify and SmartTube if they're
installed. To add one, open **Apps**, hold OK on it and choose **Add to the launcher**. Apps on the
launcher aren't listed in Apps again.

**Apps** (on the right) shows every app on the TV. Press OK to open one. **Press and hold OK** on
an app, there or on the launcher, for more: add it to the launcher or remove it, move it left or
right, its App info, or Uninstall.

**Your own pictures:** hold OK on a tile and choose **Change picture** to pick one from the
TV's files (you'll need a file manager app, such as FX File Explorer), or a parent can upload one
on the **TV** page of the web console. A wide picture works best, 16:9 like 1280 × 720. **Use
the app's own picture** puts it back.

**Hide apps:** hold OK on an app in Apps and choose **Hide from Apps**. **Settings → Hidden apps**
lists them; choose one to show it again.

## The screen saver

When the TV sits idle (5, 10, 15 or 30 minutes; or Off), the screen saver comes up. Any button
wakes the TV; **hold OK to ask Steward**, even from the screen saver. Choose it in **Settings →
Screen saver**:

- **Family clock:** a big clock that drifts slowly round the screen (so nothing burns in), with
  the next event, the routine on now or next, and everyone's picture, bright when they're home.
- **Family photos:** the album, drifting slowly closer and crossfading. Add photos on the console's
  **TV** page: drag photos, or a whole folder, onto it (or choose them).
- **Photo link:** pictures from a link set on the console's TV page: an Immich shared album, an
  RSS or Atom feed of photos, a web page of pictures, or a single picture that changes (a webcam).
- **A theme:** a slow gradient, a starry sky, or the season (snow, petals, fireflies or leaves).
- **A mix:** a few minutes of each.

**Family updates:** as things happen (someone arrives home, a chore is done), they pop up in a
corner for a few seconds. Switch them on for the screen saver, the home screen, or both, in
**Settings**.

## Steward on the TV

If Steward is set up, the round microphone button at the bottom right (always there), or the
**Steward** button on the side, listens through the remote's microphone and answers
on screen and out loud. On the TV, Steward acts as the TV (a family display), so it can only do
what the TV is allowed to; ask on your phone for anything more.

## Making it the TV's home screen, and the remote's buttons

On Android TV boxes that let you choose a home app (an Nvidia Shield, for example), use
**Settings → Make FamLogic the home app**.

Google TV (Chromecast with Google TV, onn) doesn't let another app replace its home screen.
FamLogic gets round that the way other launchers do, with an accessibility helper:

1. In FamLogic on the TV, open **Settings → Remote helper → Accessibility settings**.
2. Choose **FamLogic TV remote** and switch it on. (It only sees which app comes to the front and
   which buttons you press, never what's on the screen.)
3. Back in FamLogic's Settings, switch on:
   - **Override current launcher:** when the TV's own home screen opens (pressing Home, or
     after leaving an app), FamLogic opens instead. Google TV stays installed underneath.
   - **Start when the TV turns on**, if you like.
4. **Remote buttons:** open it, choose **Add a button**, press a button on the remote (the Netflix
   or YouTube button, say), then pick what it should do: open an app, open FamLogic, or ask
   Steward. The buttons you've changed are listed there too, to remove. The arrows, OK and Back
   can't be changed, so the remote always works.

If the switch in Accessibility settings is greyed out ("restricted setting"), open the TV's
**Settings → Apps → FamLogic**, choose **Allow restricted settings** from its menu, and try
again. If your TV has no such option, it can be switched on from a computer with adb:
`adb shell settings put secure enabled_accessibility_services io.github.pretendgineering.famlogic.tv/io.github.pretendgineering.famlogic.TvRemoteService`.

Google TV updates have sometimes stopped launcher overrides working. If FamLogic stops opening
on Home, switch the helper off and on again in Accessibility settings. As a last resort (with
adb, reversible), switch off the Google TV home screen entirely:
`adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx`; to undo it:
`adb shell pm enable com.google.android.apps.tv.launcherx`.
