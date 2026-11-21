# Roadmap

Planned features, to build once the core (sharing, map, invites) has been proven on real phones.

## Ground rules for every feature

1. **Each feature can be switched on and off in the app.** All switches sit in one **Features** section in the app's Settings. Each has one plain sentence saying what it does and what it shares.
2. **Sending and receiving are separate switches** where a feature has both sides. For example, "Let others see my trail" is separate from "Show other people's trails".
3. **Switches that affect what others see are stored on the server**, so other phones respect them. Switches that only change your own phone (such as which alerts you get) are stored on the phone.
4. **Off means off.** When "my trail" is off, the server doesn't record it at all, and switching it off deletes what was stored.
5. **No new infrastructure.** Alerts arrive within seconds over a connection the app keeps to your own server (since 0.5.0), with the normal location updates as a fallback. Nobody has to install or host anything extra.

## Delivery mechanism (shared by alerts)

The phone already contacts the server every interval to send its location. The reply to that request can carry any new events ("Sam arrived at Home", "Alex is at 10% battery", "SOS from Jordan"), and the app shows them as notifications.

- **Works on every phone** with no Google services and no push server.
- **Latency** is up to one interval. That's fine for arrivals and battery, but not ideal for SOS.
- **Instant delivery (0.5.0):** the app's background service also keeps a connection open to the server ("long polling"), which answers the moment there's a new alert. Alerts arrive within seconds, with no Google services and no extra app or server to set up. Location replies still carry alerts too, as a fallback.

## Features

> Naming: in the app and dashboard, circles are called **Groups** and places are called **Fences** (since 0.8.0). The server's data and API keep the original names.

### 1. Places and geofences (arrive/leave alerts)
- A **place** is a named area on the map: a circle with an adjustable radius, e.g. Home, School, Work. Anyone can add one by long-pressing the map or searching an address. Places belong to a circle (see Circles), so a work circle and a family circle each have their own.
- **Geofence alerts**: "Sam arrived at School", "Alex left Work".
- **Fine-grained alerts** (set on the phone):
  - choose per person and per place whether to get alerts for **arrivals, departures or both**
  - optional active hours (e.g. only alert about School between 7am and 5pm on weekdays)
- **Switches:**
  - "Announce when I arrive at or leave places" (stored on the server, default on)
  - "Notify me when others arrive or leave" (on the phone, default on)
- **Sensible behaviour:**
  - the server detects arrivals and departures from location reports
  - a small buffer zone stops someone standing at the edge of a place from triggering alerts over and over
  - inaccurate fixes are ignored
  - a departure must hold for a minute before it alerts, so a GPS blip doesn't trigger one
- **Optional extra:** while you're near a place, the app checks your location more often, so arrivals register sooner without using more battery the rest of the time.

### 2. Trail / history
- Shows where someone has been over the last few hours as a line on the map.
- **Switches:**
  - "Keep my trail for others to see": off by default, length 6/12/24 hours, stored on the server. Turning it off deletes the stored trail.
  - "Show trails on the map" (on the phone)
- The server deletes points older than the chosen length.

### 3. Check-in button
- One tap sends "I'm here and OK" with your location to everyone.
- **Switches:**
  - "Show check-in button" (on the phone)
  - "Notify me about check-ins" (on the phone)

### 4. SOS button
- Press and hold for 3 seconds (so it can't go off in a pocket) to send an alert to everyone.
- While SOS is active, your location updates every 15 seconds until you cancel it.
- **Switches:**
  - "Show SOS button" (on the phone)
  - "Notify me about SOS alerts": stored on the phone, and the app warns before you turn it off
- SOS notifications ignore per-person mutes.

### 5. Low-battery alerts ✅ (done in 0.2.0)
- **Switches:**
  - "Tell my family when my battery is low" (stored on the server)
  - "Notify me when someone's battery is low" (on the phone)
- Choice of threshold (15% or 10%). One alert per discharge, so no repeats until the phone has been back above 20%. No alerts while charging.
- The phone sends an update the moment it crosses a level, so the alert doesn't wait for the next interval.
- Since 0.5.0, alerts also reach you while your own sharing is paused: the background service stays connected for alerts only.

### 6. QR code invites
- The dashboard shows a QR code next to each invite link. Scanning it with the phone's camera opens the app, the same as tapping the link.
- Purely a convenience on the dashboard, so it needs no switch.
- Optional extra: a "Scan invite" button on the app's join screen.

### 7. Circles (share with different groups)
Like Life360's circles: separate groups such as Family, Grandparents or Friends, where you choose what each one sees.
- **Membership:** a person can be in several circles. You only see people you share a circle with, and only in the circles where they share.
- **One sharing switch per circle in the app**, e.g. share with Family but pause sharing with Friends. The global pause switch still stops everything at once.
- **Map switcher:** a circle picker at the top of the map, plus "All circles".
- **Invites are for a circle.** The dashboard admin creates circles and invites people into them. A person already on the server can be added to another circle without reinstalling.
- **What belongs to a circle:** places, check-ins, SOS and battery alerts all go to the circles you share with, with a per-circle choice for SOS.
- **Upgrading:** existing servers become one circle named after `FAMILY_NAME`, so nothing changes until a second circle is created.
- **Build timing:** this changes who-sees-whom everywhere, so it should land **before places and trails**. That way those features are built on circles from the start rather than retrofitted.

### 8. Profile pictures on map pins
Friendlier pins: your photo in the map bubble instead of an initial.
- **Choosing a photo:** pick one in the app (Settings → Your photo) with a simple square crop. The phone shrinks it to about 256 px before uploading, so files stay tiny.
- **Storage:** the server keeps it next to `thereabouts.json` in the data folder, so the one-folder backup still holds.
- **Who sees it:** only people who share a circle with you.
- **Where it shows:** map pins, the people list and the web dashboard. Pins fall back to the coloured initial when there's no photo.
- **Switches:**
  - "Use my photo" (on the server; turning it off deletes the photo)
  - "Show photos on the map" (on the phone, for anyone who prefers plain pins)
- The dashboard admin can remove a photo.

### 9. One-tap shareable invite message
Make adding someone a single copy-paste. The dashboard's **Copy invite** button (and later an "Invite someone" button in the app) copies a complete, friendly message, for example:

> Join our family on Thereabouts!
> 1. Get the app: https://f-droid.org/packages/io.github.spasm0dic.thereabouts/
> 2. Then tap this link to join: https://family.example.com/join/ABCD-EFGH

- The app-store link points at F-Droid or IzzyOnDroid once Thereabouts is listed there, with the GitHub release or Obtainium as the fallback before that.
- The invite page (`/join/<code>`) shows the same download link, so a link opened on a phone without the app installed still makes sense.
- Wait until the app is in a store, so the message doesn't send people to sideload an APK.

## Queued for later (not started)

These are parked until we decide to pick them up. Each needs a short design pass (and questions) first.

### 10. Two builds of the app: FOSS and Google Play services
- **FOSS build (today's app):** stays the default, with no Google dependencies, for F-Droid/IzzyOnDroid and de-Googled phones.
- **"Plus" build with Google Play services:** a second product flavour from the same code. Extras live behind a small interface, so the FOSS build simply doesn't include them.
- Candidate extras:
  - Activity recognition: driving, walking and cycling from Google's sensors, more accurate and cheaper on battery than the GPS-speed method.
  - The fused location provider (better indoor fixes).
  - Optionally Firebase Cloud Messaging as a backup wake-up for alerts.
- Open questions: distribution (GitHub release with two APKs vs. Play Store), whether both can be installed side by side (different application IDs), and how the server and dashboard show which build someone is on.

### 11. Google Find Hub trackers
- Show Bluetooth trackers registered in Google's Find Hub network (keys, bags, pets) on the family map.
- **Research (Nov 2026):**
  - **No official API.** Google offers no supported way for a third-party app to read Find Hub tracker locations. Its developer docs cover only making certified tracker hardware (Fast Pair / Find Hub Network Accessory spec).
  - **Official sharing** is limited to temporary share links, e.g. for an airline to find a bag. They're not continuous and not machine-readable for us.
  - **Unofficial route:** GoogleFindMyTools (GPL-3.0) reverse-engineers the network and can fetch and decrypt your own trackers' locations. A Home Assistant integration (GoogleFindMy-HA) is built on it.
    - Setup needs a Google sign-in through Chrome to produce a secrets file (account tokens plus the end-to-end encryption keys). Users report having to regenerate it after updates.
    - It is experimental and may break whenever Google changes things.
- **Options:**
  - **A.** An opt-in, clearly-experimental "Find Hub bridge" on the server, using that approach and the admin's own Google account. It would only fetch on a schedule.
  - **B.** Skip Google. Instead, family phones listen for the family's own cheap Bluetooth tags and report where they heard them. This is private and FOSS, but only works near family phones.
  - **C.** Drop it.
- Decision pending.

### 12. Pebble watch companion
- A small watch app plus phone-side glue:
  - See everyone's status at a glance (name, "At Home" / "Driving", how long ago, battery).
  - Check in from the wrist.
  - Hold a button for SOS, with the same 3-second hold and the same confirmation.
  - SOS and other alerts buzz the watch.
- Research first:
  - The current Pebble landscape: the new Pebble hardware and open-source PebbleOS, the Rebble services, the companion app on Android, and PebbleKit (AppMessage) for talking to the phone app.
  - Which SDK and watch models to target.
- Likely shape: the Thereabouts phone app relays to the watch, and the watch never talks to the server directly. Works in both builds.

## Build order

1. ✅ **Delivery mechanism plus low-battery alerts** (0.2.0).
2. ✅ **Accounts:** sign back in with a username and password (0.4.0).
3. ✅ **Instant alerts, SOS and check-in** (0.5.0). The app's background service keeps a connection open to your own server, so alerts arrive in seconds without Google services or extra apps. This replaces the "optional UnifiedPush" idea: nobody has to install or host anything extra.
4. ✅ **Circles** (0.6.0), called **Groups** in the app and dashboard from 0.8.0.
5. ✅ **QR code invites** (0.6.0).
6. ✅ **Places and geofences** with arrive/leave alerts (0.7.0). Not done yet: per-person mutes and active hours (e.g. only on school days).
7. ✅ **Trail / history** (opt-in, 0.7.0).
7a. ✅ **0.8.0:** places renamed **Fences** for users; address search for fences (through the server, to Nominatim); opt-in Wi-Fi positioning through beaconDB; Settings split into pages; purple Material You look; Check in and Show everyone in one row and a **Hold for SOS** bar.
7b. ✅ **0.9.0:** per-person, per-group and per-fence alert switches; emoji icons instead of the initial; phone health in the dashboard and "phone went quiet" alerts; daily server backups and a download button; fence alert hours; battery saver.
7c. ✅ **0.10.0:** trips: journeys between stops (kept 1, 3 or 7 days, opt-in), with fence or street names, distance, average and top speed, route on the map, optional "finished a trip" alerts, in the app (tap a person) and dashboard. Also: colour and emoji picker, keyless map styles, Settings reordered.
7d. ✅ **0.11.0:** driving detection from GPS speed: "Driving" status and 🚗 badge, 1-minute updates while driving, optional "started driving" alert.
8. **Profile pictures** (later; emoji icons cover the simple case).
9. **Shareable invite message** with an app download link (once the app is on F-Droid or IzzyOnDroid).
