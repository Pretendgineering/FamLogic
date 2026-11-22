# FamLogic

A self-hosted family hub: location sharing, chat and (soon) a shared calendar, to-dos, chores, routines and meal plans, on **your own server**, with **press-to-talk voice** to your own AI agent across phones, tablets, the TV and the watch. Mostly made for my own family as a vibe-coding project; I perpetually do not know what I'm doing.

- **Private:** no third-party accounts, no cloud, no ads, no tracking. Your family's data stays in one folder on your server.
- **Modular:** at first setup you choose what your family uses, and you can change it any time.
- **Works without Google:** the default app has no Google code at all (an optional Google edition adds a few extras).
- **Kid-friendly:** younger children sign in with a code from a parent, no password; parents can see their children's chats.
- **Voice first** *(coming)*: press and hold to talk, and hear things read out, on every device. It goes to an AI agent you host (such as Hermes or OpenClaw), so a child can say "I fed the cat" to tick off a chore, send a message, or hear what's next.
- **Open source:** GPLv3.

## Devices

| Device | What it does |
|---|---|
| **Android phones** | The FamLogic app: map, chat, alerts, SOS, and everything else that's switched on. One app for parents and children. |
| **Android tablets** | The same app, on a bigger screen. Wi-Fi-only tablets can share their location too. *Coming:* a tablet layout with the map beside that person's chores and to-dos, and a big talk button for kids. |
| **Android TV** *(coming)* | A **home-screen launcher**: set it as the TV's home screen and it becomes a family dashboard, with a scrolling row of cards (today's agenda, who's where, to-dos for this time of day, the current routine like "Morning" or "Bedtime") above shortcuts to your most-used apps. Connect it to your server in its settings; talk to your agent with a remote button. |
| **Pebble watches** | A watchapp (family list, radar and maps, check-in, hold-to-SOS, nudges) and a Family watchface, on every Pebble from the original to the Pebble 2 Duo, Time 2 and Round 2. *Coming:* talk to your agent from the watch. |
| **Pebble Index 01 ring** | Silent SOS, voice check-ins and voice place reminders. |
| **Any web browser** | The web console: family and roles, devices, modules, the map and chat. |

## Modules

| Module | Status |
|---|---|
| **Thereabouts**: everyone on a map, SOS, check-ins, fences with arrive/leave alerts, trips, driving, Pebble and Index 01 ([guide](docs/THEREABOUTS.md)) | ✅ |
| **Chat**: a family chat, group chats and one-to-one, with photos and read receipts; parents can read their children's chats ([guide](docs/CHAT.md)) | ✅ |
| **Calendar** (with CalDAV sync), **To-dos & lists**, **Chores**, **Routines** (checklists that follow the family's daily rhythm), **Meals & menus**, all taggable to people or groups | planned |
| **TV launcher** for Android TV, and a **tablet layout** | planned |
| **Voice and AI**: press-to-talk and read-aloud everywhere, routed to your own agent (Hermes, OpenClaw…); family content as Markdown (Obsidian-friendly) that agents can edit; optional end-of-day summaries for parents | planned |

See [ROADMAP.md](ROADMAP.md) for the order and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it fits together.

## Install

### 1. Run the server

Any always-on machine with Docker: a home server, NAS, Raspberry Pi 4/5 or small VPS (amd64 or arm64).

```sh
curl -O https://raw.githubusercontent.com/spasm0dic/thereabouts/main/docker-compose.yml
# edit docker-compose.yml: set ADMIN_PASSWORD (and FAMILY_NAME)
docker compose up -d
```

Open `http://<server>:8080`, log in with the admin password, and choose your family's modules. On a NAS or app platform (TrueNAS, Unraid, Portainer…), create a container from `ghcr.io/spasm0dic/famlogic:latest` with port 8080, a persistent volume at `/data` and the settings below.

**Phones need to reach the server from anywhere,** not just your Wi-Fi. Pick one:
- **A VPN such as [Tailscale](https://tailscale.com) or WireGuard** (easiest, nothing exposed): use `http://<vpn-ip>:8080`.
- **HTTPS through a reverse proxy** (Caddy, Nginx Proxy Manager, Traefik, Cloudflare Tunnel). With Caddy: `family.example.com { reverse_proxy localhost:8080 }`. Alerts hold a request open for up to 50 seconds, so keep the proxy's read timeout at 60 s or more. Don't put plain `http://` on the internet; the app warns you if you try.

### 2. Install the app

Download **`famlogic.apk`** from the [latest release](https://github.com/Spasm0dic/Thereabouts/releases/latest) onto each phone or tablet and open it.

- **`famlogic-google.apk`** (the Google edition) is the same app plus Google Play services for quicker indoor location and driving detection from motion sensors. Either installs over the other.
- **Automatic updates** with [Obtainium](https://github.com/ImranR98/Obtainium): add this repository and set *Filter APKs by regular expression* to `^famlogic\.apk$` (or `^famlogic-google\.apk$`).
- **Pebble:** in the app, **Settings → Pebble & Index → Set up your watch** downloads the watchapp and watchface; open them with the Pebble app.

### 3. Add your family

In the web console, open **Family → Invite someone**, choose their role, and send the link (or show the QR code). They tap it, the app opens with everything filled in, and they choose a username and password, or, for a younger child, nothing at all.

| Role | Signs in with | Gets |
|---|---|---|
| **Admin** | username + password | everything a parent gets, plus the console's admin pages. Only the main admin (with `ADMIN_PASSWORD`) makes admins. |
| **Parent** | username + password | the whole app, and the console's parent tools (the map, chat, their children's chats) |
| **Older child** | username + password | the app, without family settings |
| **Younger child** | a one-time code from a parent | the app, without family settings. New phone: **Family → ⋯ → New join code** |
| **Doesn't use the app** | – | part of the family for chores and events (e.g. a baby) |

Parents and admins log in to the web console with their own username and password; the main admin can also use the admin password alone.

**New phone?** Sign in with your username and password (your old phone is signed out). **Forgot your password?** An admin sends a one-time reset link from **Family → ⋯ → Reset sign-in**. Passwords need 12+ characters and can't be common ones; a few random words like `blue canoe at midnight` are ideal.

## Settings

Set in `docker-compose.yml` (or your container's environment).

| Variable | Default | |
|---|---|---|
| `ADMIN_PASSWORD` | *(required)* | The main admin's password for the web console (8+ characters). |
| `FAMILY_NAME` | `Family` | Shown in the apps and console. |
| `PUBLIC_URL` | from the request | The address phones use, e.g. `https://family.example.com`. Set it if invite links come out wrong. |
| `TILE_URL`, `TILE_URL_DARK`, `TILE_ATTRIBUTION` | OpenStreetMap | The default map tiles (any `{z}/{x}/{y}` server). Everyone can also pick their own map style. |
| `GEOCODER_URL` | OpenStreetMap Nominatim | Address search, done by your server. `off` turns it off. |
| `INVITE_HOURS` | `72` | How long an invite works. |
| `BACKUP_DAYS` | `7` | Daily backups kept in `/data/backups`. `0` turns them off. |
| `SILENCE_HOURS` | `6` | Tell people when a sharing phone has been quiet this long. `0` turns it off. |
| `DATA_DIR`, `LISTEN` | `/data`, `:8080` | Where data lives, and the port inside the container. |

## Updating and backups

- **Update the server:** `docker compose pull && docker compose up -d` (restarting alone doesn't fetch a newer image). The running version shows in the console's Settings → Server.
- **Backups:** everything is in the data folder: `famlogic.json` (people and settings), `chat.db` (messages) and `media/` (photos). The server keeps daily copies of the first two in `backups/`, and **Settings → Server → Download backup** saves one now. To restore, stop the container, put the files back, and start it.
- **Coming from Thereabouts or Libre360?** See [docs/UPGRADING.md](docs/UPGRADING.md): your family, fences and settings carry over.

## Building from source

```sh
cd server && go test ./... && ADMIN_PASSWORD=something-long DATA_DIR=./data go run .   # Go 1.26
cd android && ./gradlew assembleFossRelease    # or assembleGoogleRelease (JDK 17+, Android SDK)
cd pebble/app && pebble build                  # pebble-tool; same for pebble/face
```

Official APKs are signed with one project key and published by CI when the version in `android/app/build.gradle.kts` changes; every push to `main` publishes the server image. See [docs/RELEASING.md](docs/RELEASING.md).

## Docs

- [docs/THEREABOUTS.md](docs/THEREABOUTS.md): location sharing, SOS, fences, trips, Pebble and Index 01
- [docs/CHAT.md](docs/CHAT.md): chat
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, roles and devices, the Markdown vault, AI agents, CalDAV
- [docs/API.md](docs/API.md): the HTTP API
- [docs/PEBBLE.md](docs/PEBBLE.md), [docs/PEBBLE-APPSTORE.md](docs/PEBBLE-APPSTORE.md): Pebble design notes and store plans
- [ROADMAP.md](ROADMAP.md)

The common-password list in `server/commonpasswords.txt` comes from [SecLists](https://github.com/danielmiessler/SecLists) (MIT License, © 2018 Daniel Miessler).
