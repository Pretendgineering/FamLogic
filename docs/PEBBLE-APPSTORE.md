# Pebble Appstore: draft plan (not submitted)

Status: **draft, November 2026.** Nothing here is live. Until we're listed, the watchapp and watchface are installed from GitHub releases (Settings → Pebble & Index in the app links straight to them). Don't ship app text that assumes the store until the listing exists.

Plan: list the watchapp and watchface around the same time as the phone app goes to IzzyOnDroid / F-Droid, so the store's "companion app" link has somewhere stable to point.

## How publishing works today (to verify on first login)

- **One portal, two stores.** Core Devices funds Rebble to run the appstore. Apps submitted through Rebble's developer portal (`dev-portal.rebble.io`) also show up in the official Pebble Appstore in Core's Pebble app. Older guides mention `rebble.io/submit` and `dev-portal.getpebble.com`; the latter is gone. *This session couldn't reach repebble.com or rebble.io to confirm the current URL, so check it first.*
- **Account:** free developer account (Rebble login).
- **Steps** (from the current "Publishing an App" guide):
  1. `pebble build` → the `.pbw` (≤ 4.4 MB; ours are about 100 KB and 60 KB).
  2. In the portal: **Add a Watchapp** / **Add a Watchface**, upload the `.pbw`, give the name, a category (watchapps only), the source code URL, and a support/website URL.
  3. Create an **asset collection for each platform** the app supports: description (max 1600 characters), up to **5 screenshots per platform** (PNG, GIF or animated GIF, *not* framed in a watch image), up to three header images, and a marketing banner (720×320 in the old guides).
  4. Choose visibility and publish. A new release is matched to the listing by the **UUID** in `package.json` (never change it) and needs a **new version number** each time.
- `pebble publish` (pebble-tool) can do the upload from the command line; it asks for whatever the listing is missing. Worth trying for CI later, but it needs a stored login, so manual releases first.
- Review: we found no current statement of review rules or timing; plan for "it may take a while" and check the portal.

## What we'd submit

| | Watchapp | Watchface |
|---|---|---|
| Name | FamLogic | FamLogic Family |
| UUID | `6d3c9f4e-2b8a-4c71-9a35-7e1f0b2d4c88` | `a1f5e2c7-3d94-4b6e-8f02-5c9e7d1a3b64` |
| Category | Tools & Utilities (or "Daily") | – |
| Platforms | aplite, basalt, chalk, diorite, emery, flint, gabbro | same |
| Source | https://github.com/Spasm0dic/Thereabouts (pebble/app) | same (pebble/face) |

**Screenshots:** we can make them all in the emulator with the scripts used during development (a fake phone sends sample people; `pebble screenshot` per platform). Per platform: the list, the radar, a street map, the hold-to-SOS countdown, and the person menu. For the face: dark, light and an SOS.

**Companion app:** both need the FamLogic phone app. `package.json` already names the Android package (`io.github.spasm0dic.famlogic`) under `companionApp`, so the Pebble app knows which app to talk to and can prompt users to get it. The store's companion link was built for Google Play / App Store URLs. **Open question:** whether it accepts an F-Droid, IzzyOnDroid or GitHub link. If not, describe it in the text ("Needs the free Thereabouts app for Android: get it from IzzyOnDroid or GitHub") and keep the URL to the GitHub releases page. There's no iOS app, so the listing should say Android only.

## Draft listing text

> **FamLogic: your family, on your wrist.** For families using FamLogic, the self-hosted family hub (Android), with its Thereabouts location module.
>
> - Everyone at a glance: where they are, and whether they're driving, moving or staying put.
> - Hold SELECT for an SOS from anywhere in the app: it counts 3-2-1, then alerts your family.
> - An SOS from family takes over the watch, with a siren on watches with a speaker.
> - Above the list: a radar of where everyone is around you, and a small street map of each person.
> - Check in with a tap or your voice, ask for a pick-up, or send a nudge (where are you? ❤ 👍).
> - Optional fall detection and heart rate with an SOS.
>
> Needs the FamLogic Android app and your family's own FamLogic server. Nothing goes through anyone else's cloud.

Watchface: "The time, and your family in a grid: where everyone is in one word, coloured by what they're doing. Turns red with a name and place during an SOS."

## Before submitting

- [ ] Confirm the portal URL and whether non-Play companion links are allowed.
- [ ] Make the screenshots (5 per platform, unframed) and a 720×320 banner.
- [ ] Try one release by hand; then consider `pebble publish` in CI.
- [ ] Update the app's Settings → Pebble & Index to offer "Get it from the Pebble Appstore" (keeping the direct download as a fallback).

## Sources
- Rebble developer guides (open source): https://github.com/pebble-dev/developer.rebble.io (`source/_guides/appstore-publishing/`)
- Publishing an App: https://developer.repebble.com/guides/appstore-publishing/publishing-an-app/
- Preparing a Submission: https://developer.repebble.com/guides/appstore-publishing/preparing-a-submission/
- (re)Introducing the Pebble Appstore: https://ericmigi.com/blog/re-introducing-the-pebble-appstore/
- Rebble help, submit an app: https://help.rebble.io/appstore-submission/?viewall=true
