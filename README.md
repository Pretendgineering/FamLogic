# Libre360

Self-host friendly alternative family tracker, mostly made for myself as a vibe coding project. I perpetually do not know what I'm doing.

Libre360 does one thing: each phone reports where it is to **your own server**, and everyone in the family sees everyone else on an OpenStreetMap map. There are no accounts, no cloud, no ads, no Google Play Services, and no location history.

- **Server**: one small container (~19 MB) with no database to run. Everything is kept in one JSON file.
- **Android app**: join with an invite link. You get a map of everyone, last-seen times, battery levels, distances and a "Directions" button.
- **Web dashboard**: see the map from a computer, invite people, rename or remove them.

## What's in the box

| | |
|---|---|
| Map | OpenStreetMap (osmdroid in the app, Leaflet on the web). The tile server can be changed. |
| Location | Android's own location service, so it works on de-Googled phones (GrapheneOS, LineageOS, /e/). |
| Privacy | Only the **latest** position is stored. Pausing wipes it from the server. Removing a member deletes them. |
| Battery | Updates every 1/2/5/15 minutes (your choice) using balanced-power location. |
| Joining | The admin creates a one-time invite link. The person taps it on their phone and the app opens with everything filled in. |
| Reliability | Restarts after reboot. The app walks you through "Allow all the time" and the battery-saver exemption. |
| License | GPLv3. All dependencies are open source. |

## 1. Run the server

You need a machine that's always on (home server, NAS, Raspberry Pi 4/5, cheap VPS) with Docker.

```sh
curl -O https://raw.githubusercontent.com/spasm0dic/libre360/main/docker-compose.yml
# edit docker-compose.yml: set ADMIN_PASSWORD and FAMILY_NAME
docker compose up -d
```

Open `http://<server>:8080` and log in with your admin password.

To build the image yourself instead of pulling it, swap the `image:` line for `build: ./server` in `docker-compose.yml` and run it from a clone of this repo.

### TrueNAS SCALE (24.10 "Electric Eel" or newer)

1. **Datasets → Add Dataset** under your apps pool, e.g. `tank/apps/libre360`, with the **Apps** preset. The preset makes the `apps` user (568) the owner.
2. **Apps → Discover Apps → ⋮ → Install via YAML**, name it `libre360`, and paste this. Change the password, name and dataset path:
   ```yaml
   services:
     libre360:
       image: ghcr.io/spasm0dic/libre360:latest
       restart: unless-stopped
       user: "568:568"            # TrueNAS "apps" user, which owns the dataset
       ports:
         - "8080:8080"            # change the left number if 8080 is taken
       environment:
         ADMIN_PASSWORD: "change-me-please"
         FAMILY_NAME: "Our Family"
       volumes:
         - /mnt/tank/apps/libre360:/data
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
| `DATA_DIR` | `/data` | Where `libre360.json` is kept. Back up this one file. |

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

Download `libre360.apk` from the [Releases](../../releases) page (or from the latest run of the **Android** workflow under the Actions tab) and install it. Android will ask you to allow installing from your browser or file manager.

**GrapheneOS and other phones without Google:** this works as-is, because the app uses Android's own location service. Without a network location provider, the app uses GPS, so indoor fixes can be slower and battery use a little higher. Turning on GrapheneOS's own network location (Settings → Location) lets the app use Wi-Fi positioning instead, which saves battery. Also set Libre360's battery usage to **Unrestricted** when the app asks.

## 3. Add your family

1. In the dashboard, click **Invite someone** and type their name.
2. Send them the link (text message, Signal, email...).
3. On their phone they tap the link, then **Open in Libre360**, then **Join**.
4. The app asks for location access. Choose **Allow all the time**, and say yes to the battery prompt, so that sharing keeps working when the phone is locked.

Do the same for your own phone.

If tapping the link doesn't open the app, they can open the app and type the server address and the code shown on the invite page.

## Using it

- **Map**: everyone's latest position. Faded pins mean the location is more than 30 minutes old.
- **List**: last seen, battery, how far away. Tap a person to zoom to them. **Directions** opens your maps app (OsmAnd, Organic Maps, Google Maps...).
- **Pause sharing** (Settings): your pin disappears for everyone, who see "Location sharing paused" instead. Resume any time.
- **Leave family** (Settings): removes you from the server.

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

### Signing the APK so updates install cleanly

Android only installs an update if it's signed with the same key as the installed version. CI builds are signed with a throwaway key unless you add your own:

```sh
keytool -genkeypair -v -keystore libre360.jks -alias libre360 -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 libre360.jks   # copy the output
```

Then add these repository secrets (Settings → Secrets and variables → Actions): `LIBRE360_KEYSTORE_B64` (the base64 text), `LIBRE360_KEYSTORE_PASSWORD`, `LIBRE360_KEY_ALIAS` (`libre360`) and `LIBRE360_KEY_PASSWORD`. Keep `libre360.jks` safe and **never commit it**.

### Releases

Push a tag such as `v0.1.0`. CI then publishes `ghcr.io/spasm0dic/libre360:0.1.0` (plus `latest` from `main`) and attaches the APK to a GitHub release.

## How it works

```
 phone ──POST /api/location──▶  server (one JSON file)  ◀──GET /api/admin/members── web dashboard
 phone ◀──GET /api/members────          ▲
                                        └── tiles come straight from the tile server to each phone/browser
```

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/join` `{code, name}` | invite code | Returns a device token |
| `POST /api/location` `{lat, lon, acc, speed, battery, charging, time}` | `Bearer <token>` | Report a fix |
| `GET /api/members` | `Bearer <token>` | Everyone's latest position + map settings |
| `PATCH` or `POST /api/me` `{name?, paused?}` | `Bearer <token>` | Rename yourself / pause |
| `DELETE /api/me` | `Bearer <token>` | Leave |
| `/api/admin/*` | dashboard login cookie | Invites and members |

Device tokens are stored only as SHA-256 hashes. The dashboard cookie is HMAC-signed, `HttpOnly` and `SameSite=Strict`, and wrong passwords are slowed down to stop guessing.

## Roadmap

Planned: arrive/leave alerts for places, trail history, check-in and SOS buttons, low-battery alerts, and QR code invites. Each one can be switched on or off in the app. See [ROADMAP.md](ROADMAP.md) for the design.
