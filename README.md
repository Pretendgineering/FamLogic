# FamLogic

A self-hosted family hub: location sharing, chat, chores and more, on your own server. No cloud, no ads, no Google required.

## Features

- **Thereabouts:** family map, SOS, check-ins, fences, trips ([guide](server/wiki/Thereabouts.md))
- **Chitchat:** family, group and one-to-one chat with photos and voice ([guide](server/wiki/Chitchat.md))
- **Upkeep:** chores with optional stars, stored as Markdown (Obsidian-friendly)
- **Family feed:** the day's timeline, one swipe from Home
- **Steward:** scoped API keys for your own AI agent ([guide](server/wiki/Vault-Guide.md))
- **Roles:** parents, grandparents, older and younger children, each with their own controls
- **Devices:** Android phones and tablets, Pebble watches, Index 01 ring, web console

Coming: calendar, lists, routines, meals, TV launcher. See the [roadmap](server/wiki/Roadmap.md).

## Install

**Server** (Docker, amd64 or arm64):

```sh
curl -O https://raw.githubusercontent.com/pretendgineering/famlogic/main/docker-compose.yml
# set ADMIN_PASSWORD in docker-compose.yml
docker compose up -d
```

Open `http://<server>:8080` and log in. For phones away from home, use a VPN (Tailscale, WireGuard) or HTTPS behind a reverse proxy (read timeout 60 s+).

**App:** install `famlogic.apk` (or `famlogic-google.apk`) from the [latest release](https://github.com/Pretendgineering/FamLogic/releases/latest). Obtainium works for updates.

**Family:** in the console, **Family → Invite someone**, and send the link or QR code.

## Settings

| Variable | Default | |
|---|---|---|
| `ADMIN_PASSWORD` | *(required)* | Console admin password |
| `FAMILY_NAME` | `Family` | |
| `PUBLIC_URL` | auto | Set if invite links come out wrong |
| `PUID`, `PGID` | `65532` | Run as your user (for a host vault folder) |
| `VAULT_DIR` | `/data/vault` | Markdown notes folder |
| `VOICE` | on | `off` disables speech |
| `BACKUP_DAYS` | `7` | Daily backups in `/data/backups` |

More options: [Architecture](server/wiki/Architecture.md#server-settings).

**Update:** `docker compose pull && docker compose up -d`. Everything lives in `/data`.

**Backup and restore:** Settings → Server → Download backup gives one zip of everything (people and settings, chats with photos and voice clips, the feed, and the notes vault). Restore a backup there too: the server keeps what was there in `/data/backups/before-restore-…` and restarts. To move the vault to a folder outside the container: download a backup, set `VAULT_DIR` to the new mount, restart, then restore. (A zip saved as `/data/restore.zip` is also restored at the next start.)

## Build

```sh
cd server && go test ./... && go run .
cd android && ./gradlew assembleFossRelease
```

## Docs

For developers: [Style guide](docs/Style-Guide.md) and the [weekly look-over](docs/Maintenance.md).


The [wiki](server/wiki/Home.md). Every server also writes it into the notes vault as `_FamLogic/`, so it opens in Obsidian.

## License

[AGPLv3](LICENSE). Third-party fonts, models and data are credited in [Architecture](server/wiki/Architecture.md#credits).
