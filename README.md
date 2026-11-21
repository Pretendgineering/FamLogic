# Thereabouts

Simple alternative family tracker, mostly made for myself as a vibe coding project. I perpetually do not know what I'm doing.

____________________

Thereabouts does one thing: each phone reports where it is to **your own server**, and everyone in the family sees everyone else on an OpenStreetMap map. There are no third-party accounts, no cloud, no ads, no Google Play Services, and no location history unless you turn it on. Sign-in happens only on your own server.

- **Server**: one small container (~19 MB) with no database to run. Everything is kept in one JSON file.
- **Android app**: join with an invite link. You get a map of everyone, last-seen times, battery levels, distances and a "Directions" button.
- **Web dashboard**: see the map from a computer, invite people, rename or remove them.

> **Who runs the server?** You do. There's no central Thereabouts service. Every family runs its own server, and the app simply connects to whatever address you type in. The Docker image and APK published from this repo are just ready-made builds of the code: other people download them and run them on their own hardware, and none of their data ever touches anyone else's server. You can also build both yourself from this repo.

## What's in the box

| | |
|---|---|
| Map | OpenStreetMap (osmdroid in the app, Leaflet on the web), gently dimmed in dark mode. Everyone can pick a map style for themselves: OpenStreetMap, Humanitarian or Topographic. |
| Location | Android's own location service, so it works on de-Googled phones (GrapheneOS, LineageOS, /e/). Wi-Fi-only tablets can opt in to Wi-Fi positioning through [beaconDB](https://beacondb.net). |
| Look | Purple Material You style, light or dark following the system, in the app and the dashboard. |
| Privacy | Only the **latest** position is stored, unless someone chooses to keep a trail (off by default, 6 to 24 hours, deleted when turned off). Pausing wipes your position from the server. Removing a member deletes them. |
| Battery | Updates every 1/2/5/15 minutes (your choice) using balanced-power location. |
| Joining | The admin creates a one-time invite link. The person taps it on their phone, and the app opens with everything filled in. They choose a username and password. |
| New phone? | Sign in with your username and password. You're back as yourself, and your old phone is signed out (the latest sign-in wins). Forgot your password? The admin sends a one-time **Reset sign-in** link. |
| Groups | Separate groups such as Family, Grandparents or Friends. You see only people you share a group with, and choose per group whether to share your location. |
| Fences | Named areas like Home or School. Everyone in the fence's group is told "Sam arrived at School" or "Alex left Work", and people's status shows "At Home". Add them on the map, by address search, or where you're standing. |
| SOS | Hold the SOS bar for 3 seconds. Everyone gets an alarm-style alert within seconds, and your location is shared every 15 seconds until you say you're OK. |
| Check-in | One tap sends "I'm here" with your location and an optional note. |
| Alerts | SOS, check-ins, low battery, arrivals and departures, each switchable in the app, and mutable per person, per group and per fence. They arrive **within seconds** over a connection the app keeps to your own server: no Google services, no push service, nothing extra to install. |
| Reliability | Restarts after reboot. The app walks you through "Allow all the time" and the battery-saver exemption. The dashboard shows each phone's app version and anything that could stop it sharing, and others are told if a phone goes quiet for hours. Low battery? The phone updates less often so it lasts longer. |
| Backups | The server keeps a daily copy of its data for a week, and the dashboard can download one any time. |
| License | GPLv3. All dependencies are open source. |

## 1. Run the server

You need a machine that's always on (home server, NAS, Raspberry Pi 4/5, cheap VPS) with Docker.

```sh
curl -O https://raw.githubusercontent.com/spasm0dic/thereabouts/main/docker-compose.yml
# edit docker-compose.yml: set ADMIN_PASSWORD and FAMILY_NAME
docker compose up -d
```

Open `http://<server>:8080` and log in with your admin password.

To build the image yourself instead of pulling it, swap the `image:` line for `build: ./server` in `docker-compose.yml` and run it from a clone of this repo.

If the logs say `can't write to /data`, the container's user can't write to the data folder. Change the folder's owner, or set `user:` in `docker-compose.yml` to its owner.

### Updating the server

New versions are published as `ghcr.io/spasm0dic/thereabouts:latest`. **Restarting the container does not download a newer `latest`**, so pull it first:

```sh
docker compose pull && docker compose up -d
```

(On a NAS or other app platform, pull the image again, then redeploy the app.) To check which version is running, look under the family name in the dashboard, or in the app under **Settings → About**.

If the app says the server is out of date (for example SOS or Check in are missing, or adding a fence doesn't work), this is the fix.

### Settings (environment variables)

| Variable | Default | What it does |
|---|---|---|
| `ADMIN_PASSWORD` | *(required)* | Password for the web dashboard. At least 8 characters. |
| `FAMILY_NAME` | `Family` | Shown in the app and dashboard. |
| `PUBLIC_URL` | worked out from the request | The address phones use, e.g. `https://family.example.com`. Set it if invite links show the wrong address. |
| `TILE_URL` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | The "Server default" map. Any `{z}/{x}/{y}` tile server works; `{s}` (server letter) and `{r}` (`@2x` on sharp screens) are filled in too, so keyed services work, e.g. CARTO with `?api_key=…`. |
| `TILE_URL_DARK` | *(none)* | A separate "Server default" map for dark mode. Without one, the light map is dimmed at night. |
| `GEOCODER_URL` | `https://nominatim.openstreetmap.org/search` | Address search when adding fences. Searches go from your server (never from phones) to OpenStreetMap's Nominatim, at most once a second and cached for a day. Point it at your own Nominatim, or set `off` to turn address search off. |
| `TILE_ATTRIBUTION` | `© OpenStreetMap contributors` | Credit shown on the map. Keep it matching your tile server. |
| `INVITE_HOURS` | `72` | How long an invite link works. |
| `LISTEN` | `:8080` | Address and port inside the container. |
| `DATA_DIR` | `/data` | Where `thereabouts.json` is kept. Back up this one file. |
| `BACKUP_DAYS` | `7` | Daily copies of the data file kept in `DATA_DIR/backups`. `0` turns them off. |
| `SILENCE_HOURS` | `6` | Tell people when a phone that shares its location hasn't been in touch for this many hours. `0` turns it off. |

### Reaching it from phones (pick one)

Phones need to reach the server from anywhere, not just your Wi-Fi.

- **Easiest and most private: a VPN such as [Tailscale](https://tailscale.com) or WireGuard.** Put the server and the phones on it and use `http://<tailscale-ip>:8080`. Nothing is exposed to the internet.
- **Public HTTPS through a reverse proxy** (Caddy, Nginx Proxy Manager, Traefik, Cloudflare Tunnel). With Caddy it's this much:
  ```
  family.example.com {
      reverse_proxy localhost:8080
  }
  ```
  Don't put plain `http://` on the public internet: the app will warn you if you try.

  Instant alerts keep a request open for up to 50 seconds. That fits the common 60-second default of Nginx, Nginx Proxy Manager, Traefik and Cloudflare. If you've set your proxy's read/idle timeout below 60 seconds, raise it for this site (for example `proxy_read_timeout 90s;` in Nginx).

## 2. Install the app

Download `thereabouts.apk` from the [Releases](../../releases) page (or from the latest run of the **Android** workflow under the Actions tab) and install it. Android will ask you to allow installing from your browser or file manager.

**GrapheneOS and other phones without Google:** this works as-is, because the app uses Android's own location service. Without a network location provider, the app uses GPS, so indoor fixes can be slower and battery use a little higher. Turning on GrapheneOS's own network location (Settings → Location) lets the app use Wi-Fi positioning instead, which saves battery. Also set Thereabouts's battery usage to **Unrestricted** when the app asks.

## 3. Add your family

1. In the dashboard, click **Invite someone**, type their name, and pick the group they're joining (if you have more than one).
2. Send them the link (text message, Signal, email...), or let them scan the QR code with their phone.
3. On their phone they tap the link, then **Open in Thereabouts**. They choose a **username and password** (a password manager is the easy way to keep them), then tap **Join**.
4. The app asks for location access. Choose **Allow all the time**, and say yes to the battery prompt, so that sharing keeps working when the phone is locked.

Do the same for your own phone.

If tapping the link doesn't open the app, they can open the app and type the server address and the code shown on the invite page.

### New phone, reinstalling, or forgot your password

- **New phone or reinstall:** open the app, choose **Sign in**, and enter the server address, your username and your password. You're back as yourself, with the same name, color and settings. Only one phone is signed in per person: the old one is signed out and says so.
- **Groups** (dashboard): in the Groups tab, **+ New group** creates one; a person's **⋯ → Groups…** picks the groups they're in. Everyone starts in one group named after `FAMILY_NAME`.
- **Forgot your password:** in the dashboard, choose **⋯ → Reset sign-in** next to the person and send them the link. It lets them choose a new password and sign in, keeping their place in the family. It works once and expires after a few days.
- **Joined before version 0.4.0?** The app shows a banner. Tap **Set up** to choose a username and password.

**Password rules:** at least 12 characters, and not a common or easy-to-guess password (for example `password1234` or `P@ssw0rd2024!`), and not containing your username or name. There are no "must include a symbol" rules: a few random words, like `blue canoe at midnight`, make a great password. Passwords are stored as slow, salted hashes (PBKDF2-SHA256, 600,000 rounds). After 5 wrong tries, that username is locked for 15 minutes.

## Using it

- **Map**: everyone's latest position. Faded pins mean the location is more than 30 minutes old.
- **List**: last seen, battery, how far away. Tap a person to zoom to them. **Directions** opens your maps app (OsmAnd, Organic Maps, Google Maps...).
- **Pause sharing** (Settings): your pin disappears for everyone, who see "Location sharing paused" instead. Resume any time. You still get SOS and other alerts while paused.
- **SOS**: press and hold the red **Hold for SOS** bar at the bottom for 3 seconds. Everyone else gets an alert that keeps ringing until they look, and a red banner on their map. Your location updates every 15 seconds, and the app keeps retrying until the alert gets through. Tap **I'm OK** when you're safe. Pressing SOS also turns sharing back on if you'd paused it.
  - So an SOS always rings: in Settings → Alerts → SOS, tap **Let SOS ring in Do Not Disturb** and turn on "Override Do Not Disturb".
- **Fences**: long-press the map to add a fence, or use **Add a fence** in Settings → Fences and search for an address or use where you are. Tap a fence on the map or in Settings → Fences to change or delete it. The dashboard can add them too (Fences tab → **+ Add fence**: search for an address or pick a spot on the map). Arrivals are announced right away. Departures wait until someone is clearly outside, with a buffer zone, so GPS jitter at the edge doesn't cause false alerts. In Settings → Fences you choose whether your own arrivals are announced and which alerts you get, and can mute single fences.
- **Tap a person** to open their card: **Directions**, a switch for alerts about them, and their **trips**.
- **Trips** (opt-in): in Settings → Trail & trips, choose **Keep my trips** for 1, 3 or 7 days. Your journeys between stops are listed as "Home → School · 8:12–8:24 · 12 min · 4.2 km · average 21 km/h, top 48 km/h"; tap one to see its route on the map. Starts and ends name the fence you were in, or the nearest street (looked up through your server, like address search). Only people who can see your location can see them, and choosing **Off** deletes them. **Notify me when someone finishes a trip** is off by default. The dashboard has them too (**⋯ → Trips…**). Distances are in miles or kilometres depending on your phone's region.
- **Trail** (opt-in): in Settings → Trail & trips, choose to keep your trail for 6, 12 or 24 hours. When someone taps you in the list, they see where you've been as a line on the map. Only people who can see your location can see it, and choosing **Off** deletes it.
- **Check in**: tap **Check in** to send "I'm here" with your location and an optional note ("Made it home").
- **Choosing whose alerts you get**: Settings → Alerts → **Who you get alerts about** has a switch per person (covers arrivals, departures, check-ins, low battery and quiet-phone alerts; an SOS always gets through). Settings → Fences has a switch per group and per fence. An arrival or departure only reaches you if the person, the group and the fence are all switched on. These choices stay on your phone.
- **Fence alert hours**: when adding or editing a fence (app or dashboard), turn on **Only alert at certain times** and choose days and hours, e.g. School on weekdays 07:00–17:00. Outside those hours nobody is notified, but the map still shows "At School".
- **Your icon**: Settings → Account → **Your icon** lets you pick a colour and an emoji for your map pin (or keep your initial). The admin can also change it in the dashboard (**⋯ → Icon and colour…**).
- **Quiet phones**: if a phone that shares its location hasn't been in touch for 6 hours (`SILENCE_HOURS`), the people it shares with get one alert ("Sam's phone hasn't been in touch for a while"), with where it was last. Paused phones are left alone.
- **Battery saver** (Settings → Sharing, on by default): below 20% and not charging, your phone updates every 15 minutes instead of more often. Others see "saving battery". An SOS still updates every 15 seconds.
- **Phone health** (dashboard): under each person you see their app version, when their phone was last in touch, and warnings such as "Location not 'Allow all the time'" or "Battery restrictions on".
- **Groups**: if you're in more than one group, chips at the top of the map show one group at a time. In Settings → Groups you choose per group whether to share your location (for example share with Family but not with Friends). Someone already signed in can tap another group's invite link (or use **Join another group…** in Settings → Groups) to join it as themselves.
- **Wi-Fi positioning** (Settings → Sharing, off by default): for tablets and phones that can't find their own location. When there's no fix, the app sends the hardware addresses and signal strengths of nearby Wi-Fi networks to [beaconDB](https://beacondb.net), a free community service, and uses the rough location it answers with. Nothing about you or your account is sent, and networks named `…_nomap` are skipped.
- **Settings** is split into pages: Sharing, Groups, Fences, Alerts, Trail, Account, Phone setup and About. Each feature has its own on/off switches, for example to hide the SOS bar or Check in button, or to stop a kind of alert.
- **Map style** (Settings → Map in the app, bottom of the sidebar in the dashboard): Server default, **OpenStreetMap**, **Humanitarian** (softer colours) or **Topographic** (OpenTopoMap). All are free and need no key. In dark mode the map is softened a little; turn that off with **Soften the map in dark mode**. It only changes your own phone or browser.
- **Leave this server** (Settings → About): removes you from the server.

### Backups and restoring

The server writes a copy of `thereabouts.json` to `DATA_DIR/backups` once a day and keeps the last 7. **Download backup** at the bottom of the dashboard gives you a copy right now. Backups contain password and device-token hashes (not the passwords themselves), so keep them as safe as the server.

To restore: stop the container, replace `DATA_DIR/thereabouts.json` with the backup (rename it to `thereabouts.json`), and start it again.

## Upgrading from Libre360

The project was renamed from Libre360 to Thereabouts in version 0.3.0.

- **Server:** change the image to `ghcr.io/spasm0dic/thereabouts:latest` and redeploy. Keep the same data folder: the server renames `libre360.json` to `thereabouts.json` on first start, so your family, invites and settings carry over. You'll need to log in to the dashboard once more.
  - If you used the example `docker-compose.yml` with a *named volume*, keep your old volume name (`libre360-data`) under `volumes:` so the data isn't left behind.
- **App:** Thereabouts has a new Android package name, so it installs as a separate app. Install Thereabouts, join with a new invite, then uninstall Libre360 and remove Libre360's entry from the dashboard. Invite links sent before the rename (`libre360://…`) still open the new app.

## Building from source

**Server** (Go 1.24, standard library only):
```sh
cd server
go test ./...
ADMIN_PASSWORD=something-long DATA_DIR=./data go run .
```

**App** (JDK 17+, Android SDK):
```sh
cd android
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

### Signing and releases

Official APKs are all signed with one project key, so updates install cleanly over each other. To set up the key, cut a release or get the app onto F-Droid, see **[docs/RELEASING.md](docs/RELEASING.md)**. In short: bump `versionName`/`versionCode` in `android/app/build.gradle.kts`, merge to `main`, and CI publishes a signed GitHub release with the APK.

Every push to `main` also publishes the server image as `ghcr.io/spasm0dic/thereabouts:latest`.

## How it works

```
 phone ──POST /api/location──▶  server (one JSON file)  ◀──GET /api/admin/members── web dashboard
 phone ◀──GET /api/members────          ▲
                                        └── tiles come straight from the tile server to each phone/browser
```

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/invites/<code>` | invite code | What a code is for: `join`, or `reset` for an existing person |
| `POST /api/join` `{code, name, username, password}` | invite code | Join, or redeem a reset link. Returns a device token |
| `POST /api/signin` `{username, password}` | password | Sign this phone in. The previous phone is signed out |
| `POST /api/me/password` `{currentPassword, newPassword, username?}` | `Bearer <token>` | Set or change your password |
| `POST /api/me/circles/<id>` `{sharing}` | `Bearer <token>` | Share or stop sharing your location with one group |
| `POST /api/me/circles/join` `{code}` | `Bearer <token>` | Join another group with an invite code |
| `POST /api/places` `{circleId, name, lat, lon, radius}`, `POST`/`PATCH`/`DELETE /api/places/<id>` | `Bearer <token>` | Add, change or delete a fence in one of your groups |
| `GET /api/geocode?q=<address>` | `Bearer <token>` | Address search for fences (results: `[{name, lat, lon}]`) |
| `GET /api/members/<id>/trail` | `Bearer <token>` | Someone's trail, if they keep one and you can see their location |
| `GET /api/events/wait?since=<cursor>&wait=50` | `Bearer <token>` | Waits until there's a new alert (or `wait` seconds), then returns `{events, cursor}`. The app keeps one open for instant alerts |
| `POST /api/sos` `{loc?}`, `POST /api/sos/end` | `Bearer <token>` | Raise or end an SOS |
| `POST /api/checkin` `{note, loc?}` | `Bearer <token>` | Check in |
| `POST /api/location?since=<cursor>` `{lat, lon, acc, speed, battery, charging, time}` | `Bearer <token>` | Report a fix; the reply carries new alerts `{events, cursor}` |
| `GET /api/members?since=<cursor>` | `Bearer <token>` | Everyone's latest position, map settings, your feature settings, and new alerts |
| `PATCH` or `POST /api/me` `{name?, paused?, shareLowBattery?}` | `Bearer <token>` | Rename yourself / pause / feature switches |
| `DELETE /api/me` | `Bearer <token>` | Leave |
| `/api/admin/*` | dashboard login cookie | Invites and members |

Device tokens are stored only as SHA-256 hashes, and member passwords as PBKDF2-SHA256 hashes. The dashboard cookie is HMAC-signed, `HttpOnly` and `SameSite=Strict`. Wrong passwords (dashboard and members) are slowed down to stop guessing, and a member's username locks for 15 minutes after 5 wrong tries.

The common-password list in `server/commonpasswords.txt` comes from [SecLists](https://github.com/danielmiessler/SecLists) (MIT License, © 2018 Daniel Miessler).

## Roadmap

Done so far: fences with arrive/leave alerts, groups, trail history, check-in and SOS, low-battery alerts, QR code invites, address search and Wi-Fi positioning. Still planned: profile pictures on map pins, an F-Droid release, and more. See [ROADMAP.md](ROADMAP.md) for the design.
