# Thereabouts: family location sharing

The Thereabouts module of [FamLogic](../README.md): everyone on an OpenStreetMap map, SOS, check-ins, fences with arrive/leave alerts, trips, driving, and the Pebble watch and Index 01 ring extras. Switch it on or off in the web console under **Settings → Modules**.

**Privacy:** only each person's **latest** position is stored, unless they choose to keep a trail or trips (off by default; turning them off deletes them). Pausing hides you from everyone. Location comes from Android's own location service, so it works on de-Googled phones; Wi-Fi-only tablets can opt in to [beaconDB](https://beacondb.net) positioning.

When you join, choose **Allow all the time** for location and say yes to the battery prompt, so sharing keeps working when the phone is locked.

## Phones without Google

GrapheneOS and other phones without Google work as-is, because the app uses Android's own location service. Without a network location provider, the app uses GPS, so indoor fixes can be slower and battery use a little higher. Turning on GrapheneOS's own network location (Settings → Location) lets the app use Wi-Fi positioning instead, which saves battery. Also set FamLogic's battery usage to **Unrestricted** when the app asks.

## The Google edition

- **`famlogic.apk`** (FOSS): no Google code at all. Works on every phone, including GrapheneOS and others without Google.
- **`famlogic-google.apk`** (Google edition): the same app with Google Play services added for:
  - **quicker, better location fixes** (Google's fused location, which also uses Wi-Fi and cell towers, so it's better indoors);
  - **driving from motion sensors** (Google's activity recognition): more accurate than GPS speed and notices sooner. It asks for the "Physical activity" permission once; Settings → Sharing → **Google Play services** has the switch.

  If Play services is missing or stops working, the Google edition quietly does what the FOSS build does. Settings → Sharing shows which is in use, and the console shows "-google" after the app version.

Both are signed with the same key and are the same app to Android, so you can switch by installing one over the other: you stay signed in and keep your settings. You can't have both installed at once.

## Using it

- **Map**: everyone's latest position, full screen. Each pin has a small tag saying how old it is ("now", "5m", "2h"); faded pins are more than 30 minutes old. Someone moving has a little arrow pointing the way they're heading; someone with an SOS has a pulsing red halo. People in the same spot share one pin with their faces stacked: tap it to zoom in and spread them out (or, if they really are in one place, to see who's there).
- **People sheet**: everyone is in a sheet at the bottom of the map. It rests tucked away (just its handle, buttons and the SOS bar, so you see nearly all the map), peeking (one person), half way or full: drag it, tap its handle to step up, or keep scrolling the list. Each row says where they are and how fresh it is ("At School · 5 min ago"); once a phone has been quiet for 30 minutes it says "Last seen at Home 2 h ago", so nobody mistakes an old spot for now. Tap a person to zoom to them. **Directions** opens your maps app (OsmAnd, Organic Maps, Google Maps...). Landscape tablets show the people beside the map instead.
- **Pause sharing** (Settings): your pin disappears for everyone, who see "Location sharing paused" instead. Resume any time. You still get SOS and other alerts while paused.
- **SOS**: press and hold the red **Hold for SOS** bar at the bottom for 3 seconds. Everyone else gets an alert that keeps ringing until they look, and a red banner on their map. Your location updates every 15 seconds, and the app keeps retrying until the alert gets through. Tap **I'm OK** when you're safe. Pressing SOS also turns sharing back on if you'd paused it.
  - So an SOS always rings: in Settings → Alerts → SOS, tap **Let SOS ring in Do Not Disturb** and turn on "Override Do Not Disturb".
- **Fences**: long-press the map to drop a new fence right there, then shape it on the map: drag inside it to move it, drag the dot on its edge to make it bigger or smaller, name it in the bar at the bottom and tap **Save**. Tap an existing fence to change it the same way. **More…** has the rest (alert hours, address search, delete). You can also use **Add a fence** in Settings → Groups & Fences and search for an address or use where you are. The web console can add them too (Fences tab → **+ Add fence**: search for an address or pick a spot on the map). Arrivals are announced right away. Departures wait until someone is clearly outside, with a buffer zone, so GPS jitter at the edge doesn't cause false alerts. In Settings → Fences you choose whether your own arrivals are announced and which alerts you get, and can mute single fences.
- **Tap a person** in the list to go to them on the map. **Long-press** to open their card: **Directions**, a switch for alerts about them, and their **trips**.
- **Trips** (opt-in): in Settings → Location Sharing, choose **Keep my trips** for 1, 3 or 7 days. A person's card shows their day as a timeline: where they stopped and for how long, and the trips between (🚗, 🚲 or 🚶, with times, distance and top speed), a day at a time. Tap a trip to see its route on the map; **Share** sends it to a chat as one line ("🚗 Sam: Home → School · 8:12–8:24 · 4.2 km in 12 min"). Starts and ends name the fence you were in, or the nearest street (looked up through your server, like address search). Only people who can see your location can see them, and choosing **Off** deletes them. **Notify me when someone finishes a trip** is off by default. The web console has them too (**⋯ → Trips…**). Distances are in miles or kilometres depending on your phone's region.
- **Trail** (opt-in): in Settings → Trail & trips, choose to keep your trail for 6, 12 or 24 hours. When someone taps you in the list, they see where you've been as a line on the map. Only people who can see your location can see it, and choosing **Off** deletes it.
- **Check in**: tap **Check in** to send "I'm here" with your location and an optional note ("Made it home").
- **Choosing whose alerts you get**: Settings → Alerts → **Who you get alerts about** has a switch per person (covers arrivals, departures, check-ins, low battery and quiet-phone alerts; an SOS always gets through). Settings → Fences has a switch per group and per fence. An arrival or departure only reaches you if the person, the group and the fence are all switched on. These choices stay on your phone.
- **Fence alert hours**: when adding or editing a fence (app or web console), turn on **Only alert at certain times** and choose days and hours, e.g. School on weekdays 07:00–17:00. Outside those hours nobody is notified, but the map still shows "At School".
- **Your icon**: Settings → Account → **Your icon** lets you pick a colour and an emoji for your map pin (or keep your initial). The admin can also change it in the web console (**⋯ → Icon and colour…**).
- **Driving**: when someone's phone has been going faster than about 25 km/h (15 mph), they show as **Driving** with a 🚗 badge on their pin, and their phone updates every minute until they've been stopped for 3 minutes. It's worked out from GPS speed on the phone, so it needs no Google services; fast cycling or a train can occasionally count too. Settings → Alerts → **Tell me when someone starts driving** is off by default; a short stop doesn't count as a new drive.
- **Quiet phones**: if a phone that shares its location hasn't been in touch for 6 hours (`SILENCE_HOURS`), the people it shares with get one alert ("Sam's phone hasn't been in touch for a while"), with where it was last. Paused phones are left alone.
- **Battery saver** (Settings → Location Sharing, on by default): below 20% and not charging, your phone updates every 15 minutes instead of more often. Others see "saving battery". An SOS still updates every 15 seconds.
- **Resting**: after 15 minutes in one spot (not driving, no SOS) a phone updates up to three times less often, never more than 20 minutes apart, so it isn't shown as "last seen". Android's low-power "significant motion" sensor wakes it the moment it's carried away (phones without that sensor rest a little less).
- **Phone health** (web console): under each person you see their app version, when their phone was last in touch, and warnings such as "Location not 'Allow all the time'" or "Battery restrictions on".
- **Groups**: if you're in more than one group, chips at the top of the map show one group at a time. In Settings → Groups you choose per group whether to share your location (for example share with Family but not with Friends). Someone already signed in can tap another group's invite link (or use **Join another group…** in Settings → Groups) to join it as themselves.
- **Wi-Fi positioning** (Settings → Sharing, off by default): for tablets and phones that can't find their own location. When there's no fix, the app sends the hardware addresses and signal strengths of nearby Wi-Fi networks to [beaconDB](https://beacondb.net), a free community service, and uses the rough location it answers with. Nothing about you or your account is sent, and networks named `…_nomap` are skipped.
- **Settings** is split into pages: Location Sharing, You, Look and Feel, Alerts & Safety, Groups & Fences, Watch & Ring and About. Each feature has its own on/off switches, for example to hide the SOS bar or Check in button, or to stop a kind of alert.
- **Map style** (Settings → Map in the app, bottom of the sidebar in the web console): Server default, **OpenStreetMap**, **Humanitarian** (softer colours) or **Topographic** (OpenTopoMap). All are free and need no key. In dark mode the map is softened a little; turn that off with **Soften the map in dark mode**. It only changes your own phone or browser.
- **Leave this server** (Settings → About): removes you from the server.
- **SOS "seen"**: when someone has an SOS, their banner (and the alert) has a **Seen** button. The person in trouble then sees "Seen by Mom, Sam", so they know help knows.
- **Pick me up / Call me**: long-press a person and tap **Pick me up** (sends them your location) or **Call me**. Only that person gets it.
- **Place reminders** (Settings → Fences → Reminders): "When I get to School, remind me to sign the form". A reminder is for you only, fires once when you arrive at (or leave) that fence, then disappears.

## Pebble watches and the Index 01 ring


Optional extras, all running through the FamLogic phone app (Thereabouts module) (no Google services needed). Settings → **Pebble & Index** has the switches.

**Installing:** Settings → **Pebble & Index** → **Set up your watch** walks you through it: tap **Download the watchapp** (and **Download the watchface**), then open the downloaded file and choose **Open with Pebble**. The files are also on the [releases page](https://github.com/Pretendgineering/FamLogic/releases/latest) (`famlogic-watch.pbw`, `famlogic-face.pbw`). It works on every Pebble, old and new (Pebble 2 Duo, Time 2 and Round 2 included). **Watch look** there chooses light, dark or the same as your phone.

**Pebble watchapp**:
- **Your family at a glance**: everyone with their status ("At Home", "Driving", "12 min ago"), with a coloured ring for what they're doing: amber driving, green moving, purple staying put, grey not heard from lately. Select a person for **Pick me up**, **Call me**, **Nudge** (Where are you? / a heart / thumbs up) or **I've seen your SOS**.
- **Hold Select anywhere for SOS**: after a moment a red panel counts 3, 2, 1 with a gentle buzz each second and a bar filling up; at zero it sends, buzzes twice and says **Sent**. Let go to cancel. While your SOS is on, the same hold (2 seconds) says you're OK.
- **Even faster:** on the watch, Settings → Quick Launch → pick Up or Down → FamLogic, and turn on **Quick Launch opens SOS** in the phone app. Then it's: hold Up on the watchface, then hold Select.
- **Radar and maps**: press Up at the top of the list for a radar with you in the middle and everyone in their real direction (distances are squeezed so near and far people both fit; anyone very far away sits on the outer ring). Select steps through people. Keep pressing Up for a small street map of each person (Select zooms out); Down goes back. Maps are drawn by your phone from your chosen map style, so the first one takes a few seconds. The original 2013 Pebble shows the radar only.
- **You** (top row): **Check in** with a preset or by speaking on watches with a microphone, **Pause/Resume sharing**, and **SOS**.
- **SOS from others** opens the watchapp full screen with a repeating buzz, plus a siren on watches with a speaker (Duo, Time 2), until you press Select (which also sends "seen") or Back.
- **"Where are you?"**: when someone asks, the watchapp (if open) offers **Tell them where I am**, which tells only them. The phone notification has the same button.
- **Timeline pins** for arrivals, departures, check-ins, trips, reminders and requests (on by default).
- **Heart rate in SOS** (off by default, Time 2 and other heart-rate watches) and **fall detection** (off by default, experimental: a hard fall starts a 30-second countdown; press any button to cancel).
- Your watch battery shows in your status ("watch 40%").

**Family watchface**: the time on top and a grid of up to 8 people (6 on the small round Pebble Time Round): each person's initial in their colour, ringed like in the watchapp, next to one word for where they are ("Home", "Driving", "Out", "3h"). Long fence names scroll like an LED sign for a few seconds after you flick your wrist. During someone's SOS the whole face turns red with their name and where they are; the watchapp sounds the alarm.

**Nudges** work from the phone too: long-press someone for **Where are you?**, ❤️ or 👍.

**Index 01 ring**: Settings → Pebble & Index → **Index 01 ring** gives you three private links: **SOS**, **Check in** and **Remind**. In the Pebble app, set a ring gesture's webhook to one of them (for example "double-click and hold" → SOS, "hold and talk" → Check in), and paste the **signing secret** if the app asks for one.
- **SOS** starts a silent SOS: your family is alerted as usual (with what you said, if anything) but your phone doesn't ring.
- **Check in** sends what you said as a check-in.
- **Remind** turns "When I get home, remind me to take the bins out" into a place reminder. Say "leave" or "leaving" for a reminder when you leave instead. The fence's name has to be in what you say.
- Anyone with a link can act as you, so keep them private; **Make new links** replaces them.

Design notes and the message format: [PEBBLE.md](PEBBLE.md).
