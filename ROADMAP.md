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

### 1. Places with arrive/leave alerts
- Anyone can add a place (name, position, radius) by long-pressing the map. Places are shared with the whole family.
- **Switches:**
  - "Announce when I arrive at or leave places" (stored on the server, default on)
  - "Notify me when others arrive or leave" (on the phone, default on, with an optional per-person or per-place mute)
- The server detects arrivals and departures from the location reports. A small buffer zone stops someone standing at the edge of a place from triggering alerts over and over.

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

### 5. Low-battery alerts
- **Switches:**
  - "Notify others when my battery is low" (stored on the server)
  - "Notify me when someone's battery is low" (on the phone)
- Choice of threshold (15% or 10%). One alert per discharge, so no repeats until the phone has charged again.

### 6. QR code invites
- The dashboard shows a QR code next to each invite link. Scanning it with the phone's camera opens the app, the same as tapping the link.
- Purely a convenience on the dashboard, so it needs no switch.
- Optional extra: a "Scan invite" button on the app's join screen.

## Suggested order

1. **Delivery mechanism plus low-battery alerts.** This is the smallest feature, and it proves out alerts end to end.
2. **Check-in and SOS**, which use the same mechanism.
3. **Places with arrive/leave alerts.**
4. **Trail / history.**
5. **QR code invites.** Small and independent, so it can slot in anywhere.
6. **Optional UnifiedPush** for instant SOS.
