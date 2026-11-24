# FamLogic

A self-hosted family hub: location sharing (**Thereabouts**), chat (**Chitchat**) and (soon) a shared calendar (**Datebook**), lists (**Odds & Ends**), chores (**Fair Share**), routines (**Rhythms**) and meals (**Pantry**), on **your own server**, with **press-to-talk voice** to your own AI agent across phones, tablets, the TV and the watch.

- **Private:** no third-party accounts, no cloud, no ads, no tracking. Your family's data stays in one folder on your server.
- **Modular:** at first setup you choose what your family uses, and you can change it any time.
- **Works without Google:** the default app has no Google code at all (an optional Google edition adds a few extras).
- **For every age:** younger children sign in with a code from a parent, no password, and get a picture-first app with big buttons and no settings to stumble into. Parents choose exactly what each child can use (each module off, view only, can add, or full, plus single features), approve their group chats and can see, pause or delete their chats. **Grandparents** get the app without the parents' controls, with bigger buttons and picture icons as options.
- **Talk instead of typing:** hold the talk button (a microphone, or a smiley for children) and your words become a message; a small speaker beside each message reads it aloud, in the sender's own male or female voice. Built into the server (Whisper and Kokoro, nothing leaves your house), or the phone's own speech. Younger children can message a parent almost on their own. *Coming:* talking to your own AI agent (Hermes, OpenClaw…) to tick off chores or hear what's next.
- **Looks like itself:** serif headings over clean sans text, in a dozen colour themes (Default, Hannah's Theme, Hobbit Door, Boring Blue, Lofi Cabin, Logic Noir, Suspicious Orange, And Chill, Night Shift, Gamer Mode, Grandma's Couch, High contrast), light or dark, chosen per device. Prefer plain words? **Look and Feel → Plain names** says Map, Chat, Calendar and Lists instead.
- **Open source:** [AGPLv3](LICENSE). Use it, change it and share it freely; if you run a changed version for others (for example as a hosted service), you must offer them your changes too. For other licensing, contact the maintainer.

## Devices

| Device | What it does |
|---|---|
| **Android phones** | The FamLogic app: a home screen of tiles, the map, chat, alerts, SOS, and everything else that's switched on. One app for parents and children. |
| **Android tablets** | The same app laid out for the big screen: the map beside the people list, chats beside the chat list. Set a tablet as an **interactive tablet** (someone uses it) or a **display tablet** (a family dashboard: full screen, stays on while charging, doesn't share its location, settings behind the parent PIN). *Coming:* that person's chores and to-dos beside the map, and a big talk button for kids. |
| **Android TV** *(coming, as its own APK)* | A **home-screen launcher**: set it as the TV's home screen and it becomes a family dashboard, with a scrolling row of cards (today's agenda, who's where, to-dos for this time of day, the current routine like "Morning" or "Bedtime") above shortcuts to your most-used apps. Connect it to your server in its settings; talk to your agent with a remote button. |
| **Pebble watches** | A watchapp (family list, radar and maps, check-in, hold-to-SOS, nudges like 👍 ❤️ 👋 and replies to check-ins) and the **FamLogic Face** watchface in the app's colours, on every Pebble from the original to the Pebble 2 Duo, Time 2 and Round 2. *Coming:* talk to your agent from the watch. |
| **Pebble Index 01 ring** | Silent SOS, voice check-ins and voice place reminders. |
| **Any web browser** | The web console: family and roles, devices, modules, the map and chat. |

## Modules

| Module | Status |
|---|---|
| **Thereabouts**: everyone on a map, SOS, check-ins, fences with arrive/leave alerts, trips, driving, Pebble and Index 01 ([guide](docs/THEREABOUTS.md)) | ✅ |
| **Chitchat** (chat): a family chat, group chats and one-to-one, with photos, read receipts, chat icons and per-chat mute; parents approve children's groups and can read, pause or delete their chats ([guide](docs/CHAT.md)) | ✅ |
| **Datebook** (calendar, with CalDAV sync), **Odds & Ends** (to-dos and lists), **Fair Share** (chores), **Rhythms** (routines that follow the family's day), **Pantry** (meals, menus and recipes), all taggable to people or groups | planned |
| **Tablet layouts** and **Control Deck** mode (a family screen on the wall or counter) | ✅ |
| **Front Room** (the Android TV launcher) and **Corkboard** (the family display) | planned |
| **Steward** (AI agents) and **Android widgets** | planned |
| **Voice and AI**: press-to-talk and read-aloud everywhere, routed to your own agent (Hermes, OpenClaw…); family content as Markdown (Obsidian-friendly) that agents can edit; optional end-of-day summaries for parents | planned |

See [ROADMAP.md](ROADMAP.md) for the order and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it fits together.

## Install

### 1. Run the server

Any always-on machine with Docker: a home server, NAS, Raspberry Pi 4/5 or small VPS (amd64 or arm64).

```sh
curl -O https://raw.githubusercontent.com/pretendgineering/famlogic/main/docker-compose.yml
# edit docker-compose.yml: set ADMIN_PASSWORD (and FAMILY_NAME)
docker compose up -d
```

In the app (and anywhere else you enter the server), you can type just the address and port, such as `192.168.1.5:8080` or `family.example.com`: FamLogic works out `http://` or `https://` itself.

Open `http://<server>:8080`, log in with the admin password, and choose your family's modules. On a NAS or app platform (TrueNAS, Unraid, Portainer…), create a container from `ghcr.io/pretendgineering/famlogic:latest` with port 8080, a persistent volume at `/data` and the settings below.

**Phones need to reach the server from anywhere,** not just your Wi-Fi. Pick one:
- **A VPN such as [Tailscale](https://tailscale.com) or WireGuard** (easiest, nothing exposed): use `http://<vpn-ip>:8080`.
- **HTTPS through a reverse proxy** (Caddy, Nginx Proxy Manager, Traefik, Cloudflare Tunnel). With Caddy: `family.example.com { reverse_proxy localhost:8080 }`. Alerts hold a request open for up to 50 seconds, so keep the proxy's read timeout at 60 s or more. Don't put plain `http://` on the internet; the app warns you if you try.

### 2. Install the app

Download **`famlogic.apk`** from the [latest release](https://github.com/Pretendgineering/FamLogic/releases/latest) onto each phone or tablet and open it.

- **`famlogic-google.apk`** (the Google edition) is the same app plus Google Play services for quicker indoor location and driving detection from motion sensors. Either installs over the other.
- **Automatic updates** with [Obtainium](https://github.com/ImranR98/Obtainium): add this repository and set *Filter APKs by regular expression* to `^famlogic\.apk$` (or `^famlogic-google\.apk$`).
- **Pebble:** in the app, **Settings → Pebble & Index → Set up your watch** downloads the watchapp and watchface; open them with the Pebble app.

### 3. Add your family

In the web console, open **Family → Invite someone**, choose their role, and send the link (or show the QR code). They tap it, the app opens with everything filled in, and they choose a username and password, or, for a younger child, nothing at all.

| Role | Signs in with | Gets |
|---|---|---|
| **Admin** | username + password | everything a parent gets, plus the console's admin pages. Only the main admin (with `ADMIN_PASSWORD`) makes admins. |
| **Parent** | username + password | the whole app, and the console's parent tools (the map, chat, their children's chats, Access & Control) |
| **Grandparent** | username + password | the whole app for themselves, without parents' controls or the children's chats; bigger buttons and picture icons as options |
| **Older child** | username + password | the app, without family settings (parents can switch things off) |
| **Younger child** | a one-time code from a parent | a picture-first app: home, chat with big pictures, SOS (parents can switch the map and more on). New phone: **Family → ⋯ → New join code** |
| **Doesn't use the app** | – | part of the family, so events and reminders can include them (e.g. a baby's check-ups), without signing in |

Parents and admins log in to the web console with their own username and password; the main admin can also use the admin password alone.

**Access & Control** (in the web console) lists everyone; open a person to set what they can use. For a child: start from the *Younger child* or *Older child* preset, then set each module to **Off**, **View only**, **Can add** or **Full**, switch single features (SOS, nudges, starting chats, sending photos, deleting their own messages, muting chats…), choose bigger buttons and picture icons, and arrange their home screen. An admin sets the family's **parent PIN** there too.

Younger children have no settings button; older children only get You, Look and Feel, and About. To open everything on a child's device, **hold the family name at the top for 5 seconds** and type the parent PIN, or use **Open their settings for 10 minutes** in Access & Control (or from a parent's phone).

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
| `VOICE` | on | Talking and reading aloud, built into the image (about 400 MB of memory for a few seconds while someone talks). `off` turns it off. |
| `PUID`, `PGID` | `65532` | The user and group FamLogic runs as and writes files as. Set them to yours (run `id` on the host) when the notes vault is a folder on the host, so the files are yours and other apps can edit them. |
| `VAULT_DIR` | `/data/vault` | Where the notes vault is inside the container (see below). |
| `DATA_DIR`, `LISTEN` | `/data`, `:8080` | Where data lives, and the port inside the container. |

### The notes vault (Markdown)

Your family's notes (people now; lists, to-dos, chores, meals, the calendar and routines as they arrive) are **Markdown files** in an Obsidian-compatible folder that other apps and AI agents can read and edit. FamLogic picks up changes made there.

- **Inside the container (the default):** nothing to do. The vault is `/data/vault`, inside the data volume, and is backed up with it.
- **A folder on the host:** in `docker-compose.yml`, uncomment `- ./vault:/data/vault` under `volumes` and set `PUID`/`PGID` to your user. Open that folder in Obsidian, sync it with Syncthing, or point an agent at it.
- **How notes are written:** the formatting guide, for people and AI agents, is [docs/agents/famlogic-vault.md](docs/agents/famlogic-vault.md). The server also writes it into the vault as `AGENTS.md` and serves it at `/api/v1/vault/guide`.
- The console's **Settings → Server** shows where the vault is and whether FamLogic can write there.

## Updating and backups

- **Update the server:** `docker compose pull && docker compose up -d` (restarting alone doesn't fetch a newer image). The running version shows in the console's Settings → Server.
- **Backups:** everything is in the data folder: `famlogic.json` (people and settings), `chat.db` (messages), `media/` (photos) and `vault/` (notes, unless you keep them elsewhere). The server keeps daily copies of the first two in `backups/`, and **Settings → Server → Download backup** saves one now. To restore, stop the container, put the files back, and start it.

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
- [docs/agents/famlogic-vault.md](docs/agents/famlogic-vault.md): how the notes vault is written (for people and AI agents)
- [docs/PEBBLE.md](docs/PEBBLE.md), [docs/PEBBLE-APPSTORE.md](docs/PEBBLE-APPSTORE.md): Pebble design notes and store plans
- [ROADMAP.md](ROADMAP.md)

## License

FamLogic is free software under the [GNU Affero General Public License v3.0](LICENSE) (versions up to 0.17.0 were released under GPLv3). Running a modified version as a service for others requires offering them its source; every FamLogic server links to its source in the web console and the app.

The family chat's monogram uses [UnifrakturMaguntia](https://unifraktur.sourceforge.net) (SIL OFL). Voice uses [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and OpenAI's Whisper *small.en* model (MIT), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) with ONNX Runtime (MIT), the [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) voice model (Apache-2.0) and espeak-ng's data (GPLv3), all inside the server image.

Headings use [Liberation Serif](https://github.com/liberationfonts/liberation-fonts) (SIL Open Font License 1.1; see `server/web/fonts/OFL.txt`). The common-password list in `server/commonpasswords.txt` comes from [SecLists](https://github.com/danielmiessler/SecLists) (MIT License, © 2018 Daniel Miessler).
