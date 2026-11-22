# FamLogic architecture

How FamLogic is put together, and the plan for the pieces still to come. The order things get built is in [ROADMAP.md](../ROADMAP.md).

## The parts

```
                ┌───────────────── your server (one Docker container) ─────────────────┐
 Android app ──▶│  Go server                                                           │
 (parent,       │   ├─ core: people, roles, devices, invites, modules, events stream   │
  child,        │   ├─ module: Thereabouts (location, SOS, fences, trips)              │
  display)      │   ├─ module: Chat            (planned)                               │
 Web console ──▶│   ├─ modules: Calendar, To-dos, Chores, Meals (planned, Markdown)    │
 Pebble / Index │   ├─ CalDAV (planned)                                                │
 AI agents ────▶│   └─ API keys for agents (planned)                                   │
                │  /data: famlogic.json · backups/ · vault/ (Markdown) · media/       │
                └──────────────────────────────────────────────────────────────────────┘
```

- **One server, one data folder.** `famlogic.json` holds people, settings and Thereabouts state (as before). Family content that people and agents edit lives as Markdown in `vault/`. Photos (chat) go in `media/`. Backing up the folder backs up everything.
- **One Android app.** What it shows depends on the device's role and the modules that are on.
- **Web console** for parents/admins: family, devices, modules, settings, and each module's admin pages.

## Modules

Defined in `server/modules.go`. Each module has an id (stable, stored), a name, a description, and `available` (built yet or not).

| id | Module | Status |
|---|---|---|
| `thereabouts` | Thereabouts: location sharing, SOS, fences, trips, Pebble/Index | available |
| `chat` | Chat | planned |
| `calendar` | Calendar (+CalDAV) | planned |
| `todos` | To-dos & lists | planned |
| `chores` | Chores | planned |
| `meals` | Meals & menus | planned |
| `display` | Family display | planned |
| `agents` | AI & API access | planned |

- **First run:** a new server (nobody joined yet) shows a module chooser in the console. A data file from before modules is treated as an existing Thereabouts family: Thereabouts on, no chooser.
- **Apps** see the modules that are on and built as `module:<id>` in the server's `features`. `Modules.on()` in the app treats a server without the `modules` feature as Thereabouts-only.
- **Admin API:** `GET /api/admin/modules`, `PUT /api/admin/modules {enabled: {id: bool}, setupDone}`.
- A module chosen before it's built is remembered and switches on when it arrives.

## People, roles and devices

### Roles (built in 0.16.0; `server/roles.go`)
- `role` is parent, child or dependent (doesn't use the app); `admin` (parents only) opens the console's admin pages; `passwordAllowed` makes a child an "older child". The console shows one choice: Admin / Parent / Older child / Younger child / Doesn't use the app.
- Only the **main admin** (logged in with `ADMIN_PASSWORD`) grants or removes admin.
- **Younger children** join with a passwordless invite and get new devices with a passwordless re-issued code (`RedeemDeviceCode`); the server refuses password sign-in and password setting for them.
- **Console sessions** name who's logged in (`exp.m-<id>.sig`, or the original `exp.sig` for the main admin). Admin endpoints need admin; a parent's session also works on the app's own API (`device()` accepts it), which is how the console gives parents exactly what their phone shows.

### Devices and how one APK becomes the right app (Phase 1 design)

The app's first screen offers two paths:

1. **Join my family**: with an invite link/QR, or by signing in.
   - A **parent invite** (made in the console, as today) → the person picks a username and password → **parent** device.
   - A **child join code** (made by a parent for a child they've created in the console) → no password; the device is bound to that child → **child** device. Codes are single-use and expire (e.g. 48 h); a lost device is revoked in the console and a new code issued.
   - **Signing in** with username and password works for parents (and for older children whose parents gave them a password).
2. **Use as a family display**: for a wall tablet, kitchen console or Android TV.
   - The device shows a 6-character **pairing code** (and a QR linking to the console's approval page).
   - In the console: **Devices → Pending → Approve**, choose a name ("Kitchen") and what it shows (calendar, chores, menus, who's where, chat). Until approved it shows nothing but the code.
   - A display has **no personal account** and limited, read-mostly rights (it may tick off chores if allowed). It's unpaired from the console at any time.

Every device gets its own token, stored hashed on the server, listed in **Devices** with: who (or which display), role, app version, last seen, and actions (rename, revoke, re-pair). Today each person has one token (one phone); Phase 1 moves tokens into a device list so a person can have a phone and a tablet.

On Android TV the display mode is chosen automatically when the app starts on a TV (leanback launcher), and the setup screen is D-pad friendly.

Parents and admins already log in to the console with their own accounts (0.16.0); the admin password is the main admin's owner/recovery credential.

## Vault: family content as Markdown (Phase 3 design)

`/data/vault/` is an ordinary Obsidian-compatible folder. FamLogic reads and writes it, watches it for changes made elsewhere (Obsidian Sync/Syncthing, an agent, a text editor), and treats the files as the source of truth for chores, lists, menus and schedules. Chat and location stay in the database (too much, too fast).

```
vault/
  FamLogic.md                 ← home note: links to everything (an Obsidian "map of content")
  People/
    Sam.md                    ← one note per person; chores and lists link here
  Lists/
    Shopping.md
    Packing - Beach trip.md
  To-dos/
    Sam.md                    ← personal to-dos, or one shared "Family.md"
  Chores/
    Feed the cat.md
    Bins out.md
  Calendar/
    2026-12-05 Dentist (Sam).md
  Meals/
    2026-W49.md               ← one note per week
    Recipes/
      Lasagne.md
```

Conventions (so Obsidian's graph and Tasks/Dataview plugins work, and agents have a clear format):

```markdown
---
type: chore
assignees: ["[[Sam]]", "[[Mia]]"]
every: week            # day | week | 2 weeks | month | weekdays | mon,thu
due: 2026-12-02
points: 2
---
# Bins out

Put the green and black bins at the kerb on Sunday evening.

- [x] 2026-11-24 done by [[Sam]]
- [ ] 2026-12-01
```

```markdown
---
type: list
---
# Shopping

- [ ] Milk
- [ ] Bread #bakery
- [x] Eggs
```

```markdown
---
type: event
start: 2026-12-05T15:30
end: 2026-12-05T16:15
who: ["[[Sam]]"]
where: Smile Dental
---
# Dentist (Sam)
```

- Front matter carries the structured fields; the body stays human. Unknown fields are kept untouched, so people and agents can add their own.
- People are `[[links]]` to `People/<name>.md`, which makes the Obsidian graph a family mind map (who does which chores, who's at which events).
- Task lines use the standard `- [ ]` / `- [x]` syntax (Obsidian Tasks-compatible dates and tags allowed).
- Writes are atomic (write to a temp file, then rename) and every change is also kept in `backups/`.
- Conflicts: if a file changed on disk since FamLogic last read it, FamLogic re-reads it before applying an edit, and never overwrites an edit it hasn't seen.

## API and AI agents (Phase 3 design)

- **API keys** made in Settings → AI & API, each with a name ("Hermes on the NAS"), scopes and an optional expiry. Scopes: `read`, `lists`, `chores`, `calendar`, `meals`, `chat:post`, `location:read` (off by default). Keys are shown once and stored hashed; every use is logged.
- **REST endpoints** under `/api/v1/` work on vault items as structured JSON *and* raw Markdown (`GET/PUT /api/v1/vault/{path}`), so an agent can either use fields or just edit text.
- **OpenAPI description** at `/api/v1/openapi.json`, and a **skill file** (`docs/agents/famlogic-skill.md`) for Hermes and similar agents: what FamLogic is, the endpoints, the Markdown conventions above, and safety rules (never delete without being asked, prefer adding to lists).
- Works the same with a cloud model or a local Ollama container: the agent only needs the server URL and a key.
- Later: an MCP endpoint, so MCP-capable agents can use FamLogic as a tool directly.

## Calendar and CalDAV (Phase 5 design)

- FamLogic serves CalDAV at `/dav/` (one family calendar plus one per person), backed by the vault's `Calendar/` notes. Phones sync with DAVx⁵ (Android) or natively (iOS), plus Thunderbird and others.
- Outside calendars (school, clubs) are added by ICS link and shown read-only.

## Chat (built in 0.16.0)

- `server/chat.go` (storage) and `server/chat_http.go` (API). Conversations: the **family** chat (everyone who uses the app, computed live), named **groups**, and **direct** one-to-one chats (one per pair).
- Stored in `chat.db`, an embedded pure-Go SQLite (modernc.org/sqlite, so the container still has no CGO and nothing extra to run): tables `conversations`, `conversation_members`, `messages`, `reads`. Photos are re-encoded to JPEG (≤1600 px, which also drops EXIF location) into `media/`.
- API: `GET /api/chat`, `POST /api/chat/direct`, `POST /api/chat/groups`, `PATCH /api/chat/{id}` (rename, add, remove/leave), `GET|POST /api/chat/{id}/messages` (JSON or multipart with a photo), `POST /api/chat/{id}/read`, `GET /api/chat-media/{name}`.
- Delivery: each message adds a `chat` event addressed to each other participant (`Event.To`), so it travels the same long-poll stream as SOS; the app shows it as a messaging-style notification with Reply.
- Access: participants read and post; a **parent** can read (not post in) any conversation with a child in it; the main admin reads all (`/api/admin/chat`). Parents peeking don't leave read receipts.
- Backups: `VACUUM INTO backups/chat-<date>.db` daily.

## Family display (Phase 6 design)

- The app's display mode: a full-screen, always-on board with panels chosen in the console (today's agenda, chores with tick-off, this week's menu, who's where, recent chat).
- Dark, low-motion design that slowly shifts to avoid burn-in; works with a D-pad on Android TV.

## Watches and the ring

The Pebble watchapp, Family watchface and Index 01 webhooks belong to the Thereabouts module today (see [PEBBLE.md](PEBBLE.md)). Next, as modules arrive: quick to-dos and list items from the watch and ring ("add milk to the shopping list"), chat quick replies, and chores ticked off from the wrist.
