# Pebble companion: design notes

(Part of FamLogic's Thereabouts module. The watch apps are called "FamLogic" and "FamLogic Face" on the watch.)

Status: **built in 0.12.0** (November 2026). Everything in "Feature ideas" below shipped. How to use it is in the README ("Pebble watches and the Index 01 ring").

## How it's built
- `pebble/app`: the C watchapp (all platforms) with a background worker (`worker_src`) for fall detection. `pebble/face`: the Family watchface. CI builds both into `famlogic-watch.pbw` and `famlogic-face.pbw` on each release.
- The phone app talks to the watch with the PebbleKit Android 2 protocol, ported minimally (two AIDL files in `android/app/src/main/aidl`, `pebble/PebbleKit.kt`) because the library needs a newer compileSdk and Kotlin. `PebbleListenerService` receives from the watch; `PebbleBridge` sends people, alerts and timeline pins.
- Messages use small integer keys (listed at the top of `PebbleBridge.kt` and `main.c`; keep them in sync).
- The Index 01 ring calls the server directly (`POST /api/index/{token}/{sos|checkin|remind}`), so it works without the watchapp. Requests are checked with the HMAC signature when present, and repeats are ignored.
- Tested in the Pebble emulators (basalt, gabbro). Still to try on real hardware: the phone↔watch bridge, fall detection tuning, heart rate.
- 0.20.0 added: the watchface renamed **FamLogic Face**, with serif type (Liberation Serif, rasterised at build time from `pebble/face/resources/fonts`, OFL) and the app's colour themes (Default, Warm paper, Forest, Ocean, High contrast; black and white on monochrome watches; the phone sends the theme as `K_NUM`); more nudges from a person's menu (thumbs up, heart, wave, smile, come home, cowboy, alien, demon mask, and eggplant and peach between grown-ups, flagged per person with `F_ADULT`); a check-in pops up a reply menu on the watch when the app is open.
- 0.14.0 added: protocol v2 (movement state, one-word labels, bearing and distance from you, fences, light/dark, miles), hold-Select SOS everywhere (an overlay layer moved onto whichever window is showing, timed by the clock rather than timer ticks), Quick Launch → SOS screen, the radar (log scale, far outliers pinned to the rim) and street maps (`pebble/PebbleMap.kt` renders the user's tiles, stretches their pale colours and converts them to the watch's 64 colours or dithered black and white; sent in pieces of about 2 KB), nudges, and the redesigned watchface (2-column grid, per-label layers so long names can scroll inside their slot).
- Appstore listing: see [Pebble-App-Store](Pebble-App-Store.md) (draft).

---

The original research follows.

## The hardware (Core Devices, 2025–2026)

| Watch | SDK platform | Screen | Input | Mic | Speaker | Other |
|---|---|---|---|---|---|---|
| Pebble 2 Duo | `flint` | 1.26" black & white e-paper (144×168, as on Pebble 2: *verify*) | 4 buttons | yes | yes | no Alloy (JavaScript) apps yet |
| Pebble Time 2 | `emery` | 1.5" 64-colour e-paper, 200×228 | 4 buttons + touch (watchapps only) | yes | yes | heart rate |
| Pebble Round 2 | `gabbro` | 1.3" 64-colour e-paper, round, 260×260 | buttons + touch (watchapps only) | yes | no (per one source: *verify*) | no heart rate, thin |
| Older Pebbles | `aplite` / `basalt` / `chalk` / `diorite` | 144×168 or round 180×180 | 4 buttons | diorite only | no | still common second-hand |

**Pebble Index 01** (a ring, not a watch): one button, a short-range microphone, Bluetooth and an RGB LED. It has no screen, no vibration and no health sensors, and a non-rechargeable battery that lasts about 2 years.
- It records only while the button is held, and can store about 5 minutes offline.
- Gestures: single, double and triple click (media controls), "hold and talk", and "double-click and hold".
- Each hold gesture can go to the Index agent, an MCP server, or **a webhook**. Only one webhook URL can be set; the gesture arrives in an `X-Index-Trigger` header, and requests can be signed.
- Everything runs through the open-source Pebble phone app (coredevices/mobileapp, GPL-3.0).

## The software

- **PebbleOS is open source**, and so is the official Pebble phone app (Kotlin Multiplatform, GPL-3.0). Rebble provides app store, weather and timeline services for the community.
- **Watchapps:**
  - **C** runs on every platform.
  - **Alloy** is pure JavaScript (Moddable) and runs on emery and gabbro only, not flint yet.
  - **CloudPebble** (a browser IDE) is back.
  - The `pebble-tool` command-line tool installs with `uv` and needs Python 3.13 (not 3.14), and comes with QEMU emulators for every platform.
- **Talking to our Android app: PebbleKit Android 2** (`pebble-dev/PebbleKitAndroid2`).
  - It's a Kotlin library. The watchapp's `package.json` lists Android companion packages (`"android": [{ "package": "io.github.pretendgineering.famlogic", "url": … }]`), and the Pebble app connects to the first one installed.
  - Our app adds a provider entry to its manifest and can choose to talk only to our watchapp.
  - The Pebble app keeps our app awake while the watchapp is open, and no "starter" app is needed on the watch.
  - It needs Pebble/Core app 1.0.7.7 or newer. Version 1.14 (September 2026) added data logging from the watch to the phone over PebbleKit 2.
  - Old "classic" PebbleKit broadcasts are off by default in the new app, so we use PebbleKit 2.
  - Messages are AppMessage dictionaries (small integer keys → ints, strings or byte arrays). Keep them small, because watches have very little memory.
- **Timeline pins:** the Pebble Time 2 has the timeline (down button) with pins from apps. Whether apps can still push pins, through the phone or a web API, **is unclear: verify**.
- **No Google needed:** all of this works in the FOSS build of FamLogic.

## Proposed shape

- A **watchapp in C**, so one codebase covers flint, emery, gabbro and the older watches, with layouts for rectangular and round screens.
- The **FamLogic phone app is the bridge.** It already has everything (people, alerts, SOS, check-in), so the watch never talks to the server.
- The phone pushes a compact snapshot on change, plus alerts. The watch sends actions back.
- For the **Index 01**, a small **webhook endpoint on our server**: the ring's phone app calls it directly, so it also works when the watchapp isn't open.

## Feature ideas

### Watch: core (first version)
1. **Family at a glance:** a scrolling list of everyone with their emoji or initial and a short status ("At Home", "Driving", "12 min ago · 40%"). It's colour-coded on colour screens; touch scrolls on Time 2 and Round 2.
2. **Person detail:** where they are (fence name or "1.2 mi NE"), last update and battery, plus actions: "Call", which hands off to the phone, and "Directions on phone".
3. **Check in from the wrist:** canned notes ("On my way", "Made it", "Running late") or **dictation** with the watch microphone on Duo, Time 2 and Round 2.
4. **SOS from the watch:**
   - Hold a button combination for 3 seconds, with a vibrating countdown and a cancel window, the same as the phone's hold bar.
   - Then "I'm OK" to end it.
   - It works even with the phone in a bag, as long as it's connected.
5. **SOS on other people's watches:**
   - An unmissable full-screen alert with a repeating vibration, plus a **siren on watches with a speaker** (Duo, Time 2), until acknowledged.
   - Showing where they are, as a distance and direction from you.

### Watch: nice next steps
6. **"Seen" acknowledgements:** dismissing an SOS on the watch (or the phone) tells the sender "Mom has seen your SOS". This needs a small server addition and helps on phones too.
7. **Quick requests:** "Pick me up" (sends your location and a request to a chosen person) and "Call me".
8. **Arrive/leave and driving alerts** with distinct vibration patterns (short double for arrivals, long for SOS) and the same per-person mutes.
9. **A "Family" watchface or glance:** the time plus a row of family emoji with small status dots (home, away, driving, quiet). It refreshes only when the phone pushes a change, so battery use stays low.
10. **Timeline pins** for arrivals, check-ins and trips, if pushing pins is still possible.
11. **Your own status on the watch:** sharing on or paused, the battery saver indicator, and "Driving" while you drive. It could offer a one-press pause.

### Watch: later or experimental
12. **Fall detection** using the accelerometer: a suspected hard fall starts a 30-second vibrating countdown before an automatic SOS. False alarms are a real risk, so it would be opt-in, with careful tuning.
13. **Heart rate in SOS** on the Time 2: include the latest heart rate in the SOS alert. It's sensitive data, so it would be opt-in.
14. **Watch battery** in your status, so others know if your watch is about to die.

### Index 01 ring
15. **Discreet SOS:**
   - Map "double-click and hold" to our webhook. Holding raises a **silent SOS**: the family is alerted, but the phone makes no sound, and the ring's recording can be attached as a note or transcript.
   - It's useful when pulling out a phone isn't safe.
   - The webhook URL carries a per-person secret, and requests are signature-checked and replay-protected.
16. **Voice check-in:** "hold and talk", e.g. "Made it to practice", becomes a check-in with that text at your current location.
17. **Voice reminders tied to places:** "When I get home, remind me to take the bins out" becomes a one-off reminder that fires on arriving at the Home fence. This would be a new feature that the watch, phone and ring could all use.

Because only one webhook URL is allowed, our endpoint would route by gesture (`X-Index-Trigger`). The Index agent and AI features are separate and unaffected.

## Open questions (to verify before building)
- Exact screen sizes, especially the Duo, and the Round 2 speaker, from the official hardware guide.
- The PebbleKit Android 2 API details (current version and Maven coordinates, manifest provider snippet) from its README and sample app.
- The Index 01 webhook payload (does it include the transcript, the audio, or both?), the signing scheme, and timeouts, from developer.repebble.com/index-01.
- Whether timeline pins can still be pushed by apps.
- Distribution: Pebble/Rebble app store listing vs. sideloading the `.pbw`.
- **Build environment:** this cloud session can't reach developer.repebble.com or repebble.com, so those hosts (and the SDK downloads) need allowing when we start.

## Sources
- Core Devices Pebble GitHub: https://github.com/coredevices
- PebbleKit Android 2: https://github.com/pebble-dev/PebbleKitAndroid2
- libpebble3 (PebbleKit 2 provider setup): https://github.com/ardangelo/libpebble3
- Pebble app changelog: https://ndocs.repebble.com/changelog
- Hardware information: https://developer.repebble.com/guides/tools-and-resources/hardware-information/
- CloudPebble, pure JavaScript and Round 2 SDK: https://repebble.com/blog/cloudpebble-returns-plus-pure-javascript-and-round-2-sdk
- Introducing two new PebbleOS watches: https://ericmigi.com/blog/introducing-two-new-pebbleos-watches
- Pebble Round 2 (Notebookcheck): https://www.notebookcheck.net/Core-Devices-launches-the-Pebble-Round-2-with-a-color-ePaper-display-and-two-weeks-of-battery-life.1195218.0.html
- Index 01 developer page: https://developer.repebble.com/index-01/
- Index 01 webhooks and MCP: https://help.repebble.com/en/articles/15724406-index-advanced-features-mcp-webhook
- Index 01 (TechCrunch): https://techcrunch.com/2025/12/09/pebbles-founder-introduces-a-75-ai-smart-ring-for-recording-brief-notes-with-a-press-of-a-button
- Index 01 review (Engadget): https://www.engadget.com/2321607/pebble-index-01-ai-ring-review/
- Index 01 webhook examples: https://github.com/rbartoli/pebble-index, https://github.com/JayCork/index-01
- Sleep as Android on PebbleKit 2: https://forum.urbandroid.org/t/status-on-new-pebble-app-integrations/15291
- Pebble Time 2 review, timeline (TechCrunch): https://techcrunch.com/2026/08/21/the-225-pebble-time-2-is-a-refreshingly-fun-smartwatch/
- Rebble: https://rebble.io
