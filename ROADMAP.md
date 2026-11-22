# FamLogic roadmap

FamLogic is a self-hosted family hub built from modules; location sharing (the Thereabouts module) came first. How the pieces fit together is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Principles

1. **Self-hosted and private.** One small container, your data in one folder, no third-party accounts or cloud. Works without Google services (the Google edition only adds extras).
2. **Modular.** Each family switches modules on at first setup (Settings → Modules). Apps hide what's off. A module that's off stores nothing.
3. **One app for everyone.** The same APK becomes a parent's app, a child's app or a family display, depending on how the device joins and what a parent approves.
4. **Markdown for family content.** Chores, lists, menus and schedules are Markdown files (with YAML front matter and `[[links]]`) in a vault folder: readable in Obsidian, editable by people and AI agents, easy to back up.
5. **Agent-friendly.** A documented API with scoped keys, so an agent (Hermes with a cloud model or a local Ollama, or anything else) can add to-dos, plan meals or post a reminder in chat.
6. **Kid-proof.** Children never need to manage a password; parents can see children's chats, and can revoke any device.
7. **Off means off, and each switch says what it shares.** Switches that affect others live on the server; personal alert choices live on the phone.

## Prioritized plan

Status: ✅ done · 🔨 next · ⏳ queued

### ✅ Phase 0: FamLogic foundation (0.15.0)
- FamLogic as a suite: one app, one Docker image (`ghcr.io/pretendgineering/famlogic`), one data folder.
- Module system on the server, first-run module chooser and Settings → Modules in the web console; apps learn which modules are on.
- Web console restructured: rail navigation with Home, Thereabouts, module pages, Family, Devices and Settings (Modules, Server, AI & API).
- Parent/child roles on every person, set in Family.

### ✅ Phase 1a and 2: Roles and Chat (0.16.0)
- Roles: admin, parent, older child (may use a password), younger child (one-time join code, no password), and family members who don't use the app. Only the main admin grants admin.
- Web console login with your own account; parents get parent tools, admins the admin pages.
- Chat: family chat, named groups and one-to-one, photos, read receipts, notifications with Reply, parents can read children's chats; stored in SQLite (`chat.db`).
- App: a tab bar for the modules that are on (Map, Chat), hidden when only one is.

### ✅ Phase 3: Design language (0.17.0)
- **Serif headings, sans text:** Liberation Serif (the free, metric-compatible Times New Roman, OFL-licensed and bundled, so it works offline) for titles, names, card headers and big numbers; a clean sans for body text and controls. "Firm but friendly": old meets new.
- **Themes,** chosen per device (app) or per browser (console), each with light and dark: **Muted Dracula** (the default), Current purple, Warm paper, Forest, Ocean, and High contrast.

### 🔨 Phase 4: Tasks, tags and the Markdown vault
- **Tags on everything:** to-dos, chores, routines, events and list items can be tagged to people (`@Sam`) and groups (`#kids`, `#parents`), so each person's screens show what's theirs.
- The **vault** folder of Markdown files (Obsidian-friendly; see ARCHITECTURE.md → Vault), watched for edits made elsewhere.
- **To-dos and lists:** shared lists (shopping, packing) and personal to-dos with due dates and **time of day** (morning, after school, evening), so screens show what matters now. Quick add from the app, console, Pebble and Index 01.
- **Chores:** who, what, how often; tick off anywhere; optional stars for children.
- **Routines and daily rhythms:** checklists like *Morning*, *After school* or *Bedtime*, and a **daily rhythm** set in the console (mini schedules per day, per person or group: "07:00 Morning, 15:30 After school, 19:30 Bedtime", "13:00 Nap time" for the little one). Screens switch to the current routine on their own.

### ⏳ Phase 5: Voice and the agent bridge
- **Press to talk** and **read aloud** as a main way to use FamLogic, everywhere: phones, kids' tablets, the TV, Pebble watches, the Index 01 ring.
- FamLogic is the **gateway**, not the AI: speech is turned into text, sent with who's talking (and their role) to **your own agent** (Hermes, OpenClaw or anything with a webhook or OpenAI-style API, on your home server or VPS), and the reply is read back.
- The agent uses FamLogic's API (scoped keys, OpenAPI, a ready-made **skill file**) to do things: "I fed the cat" ticks off the chore, "tell Mom I'm at the park" sends a chat message, "what's next?" reads the routine, parents ask "what still needs doing today?".
- **Speech** on the server first (optional Whisper for speech-to-text and Piper for text-to-speech containers: private and the same on every device), falling back to the device's own Android speech services.
- **Read aloud:** new messages and chores can be spoken on kids' devices and the TV (per device, with quiet hours).
- **TV remote:** a remote button mapped (with a button-remapper app) to FamLogic's talk shortcut.
- **Pebble:** hold a button to talk (watches with a microphone); the reply shows on the watch.
- **Parents' summary:** an optional end-of-day summary (chores done, messages, who went where), from the agent or FamLogic itself.

### ⏳ Phase 6: TV launcher and tablet layout
- **Android TV launcher:** FamLogic as the TV's home screen. Connect to the server in its settings (no personal account; a parent approves it in the console). A horizontally scrolling row of rounded cards, three at a time: today's agenda, a small map of who's where, to-dos for this time of day, the current routine (it changes with the daily rhythm: Morning, Nap time, Bedtime…), this week's menu, recent chat. Below: shortcuts to your most-used apps. D-pad friendly, dark and burn-in friendly.
- **Tablet layout:** the map beside the signed-in person's chores, to-dos and routine, filtered by their tags; a big talk button on kids' tablets; works as a kitchen wall display too.
- **Devices:** several devices per person, the console's Devices page with role, version, last seen and Revoke; display/launcher pairing with a code approved in the console.

### ⏳ Phase 7: Calendar
- Shared family calendar, events as Markdown notes with tags; **CalDAV** so phone calendars (DAVx⁵ on Android, iOS natively) and Thunderbird sync both ways; outside calendars (school, sports) by ICS link.

### ⏳ Phase 8: Meals and menus
- Weekly meal plans and recipes; ingredients to the shopping list; easy for an agent to plan.

### ⏳ Chat, next steps
- "Message" from a person's card on the map; chat on the Pebble (quick replies) and from the Index 01 ring; delete your own messages; mute a chat.

### ⏳ Later and parked
- **Grandparents mode** (idea, to design): a third kind of family member, alongside parents and children, with its own set of features. To decide: which features it has.
- **Pebble App Store listing** (draft in [docs/PEBBLE-APPSTORE.md](docs/PEBBLE-APPSTORE.md)), and **F-Droid / IzzyOnDroid** for the app.
- **Shareable invite message** with an app download link, once the app is in a store.
- **Profile pictures** (emoji icons cover the simple case).
- **Google Find Hub trackers** (parked, Nov 2026): no official API; the only route is the unofficial GoogleFindMyTools approach with the admin's own Google account. Alternative: family phones listening for the family's own cheap Bluetooth tags.
- **Home Assistant** integration (presence, chores done, display on a dashboard).

## History: location sharing (the Thereabouts module)

Location sharing came first; these versions built it:

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
