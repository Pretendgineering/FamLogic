# FamLogic roadmap

FamLogic is a self-hosted family hub built from modules. It grew out of Thereabouts (family location sharing), which is now its first module. How the pieces fit together is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Principles

1. **Self-hosted and private.** One small container, your data in one folder, no third-party accounts or cloud. Works without Google services (the Google edition only adds extras).
2. **Modular.** Each family switches modules on at first setup (Settings → Modules). Apps hide what's off. A module that's off stores nothing.
3. **One app for everyone.** The same APK becomes a parent's app, a child's app or a family display, depending on how the device joins and what a parent approves.
4. **Markdown for family content.** Chores, lists, menus and schedules are Markdown files (with YAML front matter and `[[links]]`) in a vault folder: readable in Obsidian, editable by people and AI agents, easy to back up.
5. **Agent-friendly.** A documented API with scoped keys, so an agent (Hermes with a cloud model or a local Ollama, or anything else) can add to-dos, plan meals or post a reminder in chat.
6. **Kid-proof.** Children never need to manage a password; parents can see children's chats, and can revoke any device.
7. **Off means off, and each switch says what it shares.** (From Thereabouts' ground rules: switches that affect others live on the server; personal alert choices live on the phone.)

## Prioritized plan

Status: ✅ done · 🔨 next · ⏳ queued

### ✅ Phase 0: FamLogic foundation (0.15.0)
- Renamed Thereabouts → FamLogic everywhere (new Android app ID, Docker image `ghcr.io/spasm0dic/famlogic`, data file `famlogic.json` moved over automatically).
- Module system on the server, first-run module chooser and Settings → Modules in the web console; apps learn which modules are on.
- Web console restructured: rail navigation with Home, Thereabouts, module pages, Family, Devices and Settings (Modules, Server, AI & API).
- Parent/child roles on every person, set in Family.

### ✅ Phase 1a and 2: Roles and Chat (0.16.0)
- Roles: admin, parent, older child (may use a password), younger child (one-time join code, no password), and family members who don't use the app. Only the main admin grants admin.
- Web console login with your own account; parents get parent tools, admins the admin pages.
- Chat: family chat, named groups and one-to-one, photos, read receipts, notifications with Reply, parents can read children's chats; stored in SQLite (`chat.db`).
- App: a tab bar for the modules that are on (Map, Chat), hidden when only one is.

### 🔨 Phase 1b: Devices
- **Device registry:** each person can have several devices (phone, tablet); the console's Devices page lists them with role, app version, last seen, and Revoke.
- **Device roles in one APK:** on first launch the app asks *Join my family* (parent or child, decided by the invite) or *Use as a family display* (shows a pairing code a parent approves in the console). See ARCHITECTURE.md → Devices.

### ⏳ Chat, next steps
- "Message" from a person's card on the map; chat on the Pebble (quick replies) and from the Index 01 ring.
- Delete your own messages; mute a chat.

### ⏳ Phase 3: Markdown vault and API
- The vault folder in the data directory, Obsidian-friendly (see ARCHITECTURE.md → Vault), watched for edits made outside FamLogic.
- API keys with scopes (read, lists, chores, calendar, chat-post), created and revoked in Settings → AI & API.
- OpenAPI description, and a ready-made **skill file** for Hermes and other agents.
- Optional later: an MCP endpoint.

### ⏳ Phase 4: To-dos and lists
- Quick add from the app, the web console, the Pebble watch and the Index 01 ring ("add milk to the shopping list").
- Shared lists (shopping, packing) and personal to-dos, assignable, with due dates; Markdown task lists underneath.

### ⏳ Phase 5: Calendar
- Shared family calendar; events as Markdown notes.
- **CalDAV** so phone calendars (DAVx⁵ on Android, iOS natively) and Thunderbird sync both ways.
- Subscribe to outside calendars (school, sports) by ICS link.

### ⏳ Phase 6: Family display
- The app's display mode for a wall tablet, kitchen console or Android TV: today's agenda, chores, this week's menu, who's where, recent chat.
- Paired and configured from the console, no personal account, always on, burn-in friendly (dark, shifting layout).

### ⏳ Phase 7: Chores
- Chore chart: who, what, how often; tick off on phones or the display; optional stars/points for children.

### ⏳ Phase 8: Meals and menus
- Weekly meal plans and recipes; ingredients to the shopping list; easy for an agent to plan.

### ⏳ Later and parked
- **Grandparents mode** (idea, to design): a third kind of family member, alongside parents and children, with its own set of features. To decide: which features it has.
- **Pebble App Store listing** (draft in [docs/PEBBLE-APPSTORE.md](docs/PEBBLE-APPSTORE.md)), and **F-Droid / IzzyOnDroid** for the app.
- **Shareable invite message** with an app download link, once the app is in a store.
- **Profile pictures** (emoji icons cover the simple case).
- **Google Find Hub trackers** (parked, Nov 2026): no official API; the only route is the unofficial GoogleFindMyTools approach with the admin's own Google account. Alternative: family phones listening for the family's own cheap Bluetooth tags.
- **Home Assistant** integration (presence, chores done, display on a dashboard).

## History: the Thereabouts module

Built as the standalone Thereabouts app before FamLogic:

1. ✅ **0.2.0:** alerts delivered with location replies; low-battery alerts.
2. ✅ **0.4.0:** accounts: sign back in with a username and password.
3. ✅ **0.5.0:** instant alerts over an always-on connection (no Google push), SOS and check-in.
4. ✅ **0.6.0:** circles (called Groups) and QR code invites.
5. ✅ **0.7.0:** places/geofences (called Fences) with arrive/leave alerts; opt-in trails.
6. ✅ **0.8.0:** address search, opt-in Wi-Fi positioning (beaconDB), Settings pages, purple Material You look, Hold for SOS bar.
7. ✅ **0.9.0:** per-person/group/fence alert switches, emoji icons, phone health, "phone went quiet" alerts, backups, fence alert hours, battery saver.
8. ✅ **0.10.0:** trips (journeys between stops) with names, speeds, routes and optional alerts; colour and emoji picker; keyless map styles.
9. ✅ **0.11.0:** driving detection from GPS speed.
10. ✅ **0.12.0:** Pebble watchapp and Family watchface, timeline pins, fall detection and heart rate (opt-in), Index 01 ring webhooks, SOS "seen" receipts, place reminders. See [docs/PEBBLE.md](docs/PEBBLE.md).
11. ✅ **0.13.0:** two builds: FOSS and the Google edition (fused location, activity recognition).
12. ✅ **0.14.0:** Pebble round two: watchface grid, hold-Select SOS, radar and street maps on the watch, nudges, watch themes.
