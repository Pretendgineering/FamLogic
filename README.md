# Thereabouts

Simple alternative family tracker, mostly made for myself as a vibe coding project. I perpetually do not know what I'm doing.

____________________

Thereabouts does one thing: each phone reports where it is to **your own server**, and everyone in the family sees everyone else on an OpenStreetMap map. There are no accounts, no cloud, no ads, no Google Play Services, and no location history.

- **Server**: one small container (~19 MB) with no database to run. Everything is kept in one JSON file.
- **Android app**: join with an invite link. You get a map of everyone, last-seen times, battery levels, distances and a "Directions" button.
- **Web dashboard**: see the map from a computer, invite people, rename or remove them.

> **Who runs the server?** You do. There's no central Thereabouts service. Every family runs its own server, and the app simply connects to whatever address you type in. The Docker image and APK published from this repo are just ready-made builds of the code: other people download them and run them on their own hardware, and none of their data ever touches anyone else's server. You can also build both yourself from this repo.

## What's in the box

| | |
|---|---|
| Map | OpenStreetMap (osmdroid in the app, Leaflet on the web). The tile server can be changed. |
| Location | Android's own location service, so it works on de-Googled phones (GrapheneOS, LineageOS, /e/). |
| Privacy | Only the **latest** position is stored. Pausing wipes it from the server. Removing a member deletes them. |
| Battery | Updates every 1/2/5/15 minutes (your choice) using balanced-power location. |
| Joining | The admin creates a one-time invite link. The person taps it on their phone, and the app opens with everything filled in. They choose a username and password. |
| New phone? | Sign in with your username and password. You're back as yourself, and your old phone is signed out (the latest sign-in wins). Forgot your password? The admin sends a one-time **Reset sign-in** link. |
| Alerts | Low-battery alerts for family members, each switchable in the app. They arrive with normal location updates, so no push service is needed. |
| Reliability | Restarts after reboot. The app walks you through "Allow all the time" and the battery-saver exemption. |
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

### TrueNAS SCALE (24.10 "Electric Eel" or newer)

1. **Datasets → Add Dataset** under your apps pool, e.g. `tank/apps/thereabouts`, with the **Apps** preset. The preset makes the `apps` user (568) the owner.
2. **Apps → Discover Apps → ⋮ → Install via YAML**, name it `thereabouts`, and paste this. Change the password, name and dataset path:
   ```yaml
   services:
     thereabouts:
       image: ghcr.io/spasm0dic/thereabouts:latest
       restart: unless-stopped
       user: "568:568"            # TrueNAS "apps" user, which owns the dataset
       ports:
         - "8080:8080"            # change the left number if 8080 is taken
       environment:
         ADMIN_PASSWORD: "change-me-please"
         FAMILY_NAME: "Our Family"
       volumes:
         - /mnt/tank/apps/thereabouts:/data
   ```
3. Open `http://<truenas-ip>:8080`.

If the app logs say `can't write to /data`, the dataset isn't owned by user 568. Fix it with **Edit Permissions** on the dataset, or change `user:` to match the owner.

Phones also need to reach it from outside your home. Tailscale is available as a TrueNAS app and is the simplest option (see below).

### Settings (environment variables)

| Variable | Default | What it does |
|---|---|---|
| `ADMIN_PASSWORD` | *(required)* | Password for the web dashboard. At least 8 characters. |
| `FAMILY_NAME` | `Family` | Shown in the app and dashboard. |
| `PUBLIC_URL` | worked out from the request | The address phones use, e.g. `https://family.example.com`. Set it if invite links show the wrong address. |
| `TILE_URL` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | Map tiles. Any `{z}/{x}/{y}` tile server works. |
| `TILE_ATTRIBUTION` | `© OpenStreetMap contributors` | Credit shown on the map. Keep it matching your tile server. |
| `INVITE_HOURS` | `72` | How long an invite link works. |
| `LISTEN` | `:8080` | Address and port inside the container. |
| `DATA_DIR` | `/data` | Where `thereabouts.json` is kept. Back up this one file. |

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

## 2. Install the app

Download `thereabouts.apk` from the [Releases](../../releases) page (or from the latest run of the **Android** workflow under the Actions tab) and install it. Android will ask you to allow installing from your browser or file manager.

**GrapheneOS and other phones without Google:** this works as-is, because the app uses Android's own location service. Without a network location provider, the app uses GPS, so indoor fixes can be slower and battery use a little higher. Turning on GrapheneOS's own network location (Settings → Location) lets the app use Wi-Fi positioning instead, which saves battery. Also set Thereabouts's battery usage to **Unrestricted** when the app asks.

## 3. Add your family

1. In the dashboard, click **Invite someone** and type their name.
2. Send them the link (text message, Signal, email...).
3. On their phone they tap the link, then **Open in Thereabouts**. They choose a **username and password** (a password manager is the easy way to keep them), then tap **Join**.
4. The app asks for location access. Choose **Allow all the time**, and say yes to the battery prompt, so that sharing keeps working when the phone is locked.

Do the same for your own phone.

If tapping the link doesn't open the app, they can open the app and type the server address and the code shown on the invite page.

### New phone, reinstalling, or forgot your password

- **New phone or reinstall:** open the app, choose **Sign in**, and enter the server address, your username and your password. You're back as yourself, with the same name, color and settings. Only one phone is signed in per person: the old one is signed out and says so.
- **Forgot your password:** in the dashboard, click **Reset sign-in** next to the person's name and send them the link. It lets them choose a new password and sign in, keeping their place in the family. It works once and expires after a few days.
- **Joined before version 0.4.0?** The app shows a banner. Tap **Set up** to choose a username and password.

**Password rules:** at least 12 characters, and not a common or easy-to-guess password (for example `password1234` or `P@ssw0rd2024!`), and not containing your username or name. There are no "must include a symbol" rules: a few random words, like `blue canoe at midnight`, make a great password. Passwords are stored as slow, salted hashes (PBKDF2-SHA256, 600,000 rounds). After 5 wrong tries, that username is locked for 15 minutes.

## Using it

- **Map**: everyone's latest position. Faded pins mean the location is more than 30 minutes old.
- **List**: last seen, battery, how far away. Tap a person to zoom to them. **Directions** opens your maps app (OsmAnd, Organic Maps, Google Maps...).
- **Pause sharing** (Settings): your pin disappears for everyone, who see "Location sharing paused" instead. Resume any time.
- **Leave family** (Settings): removes you from the server.

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
| `POST /api/location?since=<cursor>` `{lat, lon, acc, speed, battery, charging, time}` | `Bearer <token>` | Report a fix; the reply carries new alerts `{events, cursor}` |
| `GET /api/members?since=<cursor>` | `Bearer <token>` | Everyone's latest position, map settings, your feature settings, and new alerts |
| `PATCH` or `POST /api/me` `{name?, paused?, shareLowBattery?}` | `Bearer <token>` | Rename yourself / pause / feature switches |
| `DELETE /api/me` | `Bearer <token>` | Leave |
| `/api/admin/*` | dashboard login cookie | Invites and members |

Device tokens are stored only as SHA-256 hashes, and member passwords as PBKDF2-SHA256 hashes. The dashboard cookie is HMAC-signed, `HttpOnly` and `SameSite=Strict`. Wrong passwords (dashboard and members) are slowed down to stop guessing, and a member's username locks for 15 minutes after 5 wrong tries.

The common-password list in `server/commonpasswords.txt` comes from [SecLists](https://github.com/danielmiessler/SecLists) (MIT License, © 2018 Daniel Miessler).

## Roadmap

Planned: places and geofences with arrive/leave alerts, circles (share with different groups), trail history, check-in and SOS buttons, low-battery alerts, QR code invites, and profile pictures on map pins. Each one can be switched on or off in the app. See [ROADMAP.md](ROADMAP.md) for the design.
