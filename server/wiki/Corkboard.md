# Corkboard: FamLogic on the TV

Corkboard turns a TV (or a tablet on the wall) into the family's board. One screen, nothing to
scroll: a greeting for the time of day, the clock, and the day's **Routines** (the ones on now,
with their checklists, and what's later today), **Upkeep** (chores due) and the **Docket**
(to-dos, and how much is on lists like Shopping). The screen follows the sun at home: light in the
morning, slowly darker towards the evening, dark at night (it uses the fence called Home to know
when the sun rises and sets).

Down the right side are buttons for the **Map** (where everyone is), the **Feed** (today so far),
**Steward** (ask by talking into the remote), **Datebook** (what's coming up) and **Pantry**
(meals). Choose which ones in **Customize**, at the bottom of that row.

From a phone, parents and grandparents can:

- **Start quiet time** (nap time) for 30 minutes, an hour, two hours or until they end it. The TV
  shows a calm, dark quiet-time screen. It ends by itself, from a phone, or with **End quiet
  time** on the TV's remote.
- **Send a message** to the TV ("Inside voices please, the baby's asleep"). It shows big on the
  screen, with who sent it, until someone presses OK.

Both are on the **TV** tile in the app (add it from the tile drawer) and on the **TV** page in
the web console.

## Setting up a TV

1. Switch on **Corkboard** in Settings → Modules.
2. Install `famlogic-tv.apk` on the TV, from the release page (Obtainium works: point it at the
   FamLogic releases and pick the `famlogic-tv` file).
3. In the web console, open **Devices → Pair a family display**. It shows a code.
4. Open FamLogic on the TV, type your server's address and that code, and choose Connect.

The TV signs in as a family display: it shows the family's day but never shares a location and
can't post.

## The TV's apps

The launcher along the bottom shows from 1 to 6 tiles (choose how many in **Customize**). Each
tile is one of the TV's apps, shown as one big picture. It starts with Jellyfin, Spotify and
SmartTube if they're installed. To change a tile, choose it in **Customize**, or press and hold
OK on it; an empty tile (+) asks which app to put there.

**Your own pictures:** a parent can give any tile its own picture on the **TV** page of the web
console (Pictures for the TV's apps). A wide picture works best, 16:9 like 1280 × 720.

## Steward on the TV

If Steward is set up, the **Steward** button listens through the remote's microphone and answers
on screen and out loud. On the TV, Steward acts as the TV (a family display), so it can only do
what the TV is allowed to; ask on your phone for anything more.

## Making it the TV's home screen

On Android TV boxes (an Nvidia Shield, for example), press Home and choose FamLogic as the home
app.

Google TV devices (Chromecast with Google TV, onn) don't let another app replace the home
screen in their settings. Two ways round it:

- **Simplest:** install a button-remapping app (such as Button Mapper) and set the remote's Home
  button to open FamLogic. Google TV stays as it was, a long press still reaches it.
- **Advanced (reversible):** with adb from a computer, switch off the Google TV home screen:
  `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx`. FamLogic then
  becomes the home screen. To undo it:
  `adb shell pm enable com.google.android.apps.tv.launcherx`.
