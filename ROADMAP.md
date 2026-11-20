# Roadmap

Planned features, to build once the core (sharing, map, invites) has been proven on real phones.

## Ground rules for every feature

1. **Each feature can be switched on and off in the app.** All switches sit in one **Features** section in the app's Settings. Each has one plain sentence saying what it does and what it shares.
2. **Sending and receiving are separate switches** where a feature has both sides. For example, "Let others see my trail" is separate from "Show other people's trails".
3. **Switches that affect what others see are stored on the server**, so other phones respect them. Switches that only change your own phone (such as which alerts you get) are stored on the phone.
4. **Off means off.** When "my trail" is off, the server doesn't record it at all, and switching it off deletes what was stored.
5. **No new infrastructure by default.** Alerts ride along with the app's normal location updates, so they arrive within one update interval (2 minutes by default). Instant delivery is an optional extra.

## Delivery mechanism (shared by alerts)

The phone already contacts the server every interval to send its location. The reply to that request can carry any new events ("Sam arrived at Home", "Alex is at 10% battery", "SOS from Jordan"), and the app shows them as notifications.

- **Works on every phone** with no Google services and no push server.
- **Latency** is up to one interval. That's fine for arrivals and battery, but not ideal for SOS.
- **Optional instant delivery:** add [UnifiedPush](https://unifiedpush.org) support (for example with a self-hosted ntfy) for people who want SOS to arrive within seconds. When it isn't set up, the normal mechanism is used.

## Features

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
- **Known limit:** while your own sharing is paused, the background service is off, so alerts only show when you open the app. UnifiedPush (item 7) would remove this.

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

## Suggested order

1. ✅ **Delivery mechanism plus low-battery alerts** (0.2.0).
2. **Check-in and SOS**, which use the same mechanism.
3. **Circles** (medium priority). This comes before places and trails, which depend on it.
4. **Places and geofences** with arrive/leave alerts.
5. **Trail / history.**
6. **QR code invites.** Small and independent, so it can slot in anywhere.
7. **Optional UnifiedPush** for instant SOS.
8. **Profile pictures** (later).
9. **Shareable invite message** with an app download link (once the app is on F-Droid or IzzyOnDroid).
