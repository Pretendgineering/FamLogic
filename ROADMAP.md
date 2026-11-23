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
- **Themes,** chosen per device (app) or per browser (console), each with light and dark: **Default** (a muted Dracula), Warm paper, Forest, Ocean, and High contrast. (Purple was dropped in 0.20.)

### ✅ Phase 3b: Home screen, parents' controls and tablets (0.19.0)
- **Home screen** with tiles: a live map that opens Thereabouts, chat with the latest message, SOS, check-in, and module tiles as they arrive. Adults arrange their own; parents arrange children's.
- **Parents' controls:** one family **parent PIN** (set by an admin) locks settings on children's devices and displays; per child, parents start from the *younger* or *older* preset and switch sections and features on or off.
- **Chat moderation:** a child's new group waits for a parent's OK; parents can pause, block or delete chats with a child in them and delete messages; anyone can delete their own messages and **mute** a chat (1 h, 8 h, until tomorrow, always).
- **Device mode:** phone, interactive tablet or **display tablet** (full screen, screen on while charging, no location sharing, settings behind the PIN).
- **Tablets and landscape:** a navigation rail, the map beside the people list, the chat list beside the open chat, chats as a shade in portrait; bigger pictures and buttons for younger children.
- Small things: rotating keeps what you typed; a camera icon that takes a new photo or picks one; senders' pictures in chat; a clearer selected tab.

### ✅ Phase 3c: Access & Control, grandparents and a friendlier app (0.20.0)
- **Module names** with a vibe: Thereabouts, **Chitchat**, **Datebook**, **Odds & Ends**, **Fair Share**, **Rhythms**, **Pantry**, **Corkboard**, **Front Room**, **Steward** (nudges stay nudges). **Plain names** switch (Customize, and the console's Look) for Map, Chat, Calendar…
- **Access & Control** page in the console: everyone in a list that opens into their switches. Per child: a preset, then each module **Off / View only / Can add / Full** and single features; bigger buttons and picture icons; their home screen (drag to reorder, sizes); **open their settings for 10 minutes** from afar. The parent PIN lives here.
- **Grandparents:** a role with the whole app but no parents' controls or children's chats; bigger buttons and picture icons as options.
- **Children's settings:** none for younger children, only Customize/Account/About for older ones; the secret way in is holding the family name for 5 seconds, then the parent PIN.
- **Home screen:** tiles in half, full-width and big sizes, dragged by a handle and resized with a corner grip; half tiles pair up or fill the row; in landscape the map sits on the right beside the tiles.
- **Picture icons** (a tiny map, a talking face, an alarm, a house, a waving hand, a camera): the default for younger children. A bigger SOS button with an icon.
- **Layouts:** map and navigation rail on the right; chats split the list open in portrait (younger children; everyone else gets the chat full screen, since 0.22.0); photos wait with an ✕ until Send; children can't delete messages or mute chats unless a parent allows it; family chat and group icons.
- **Settings:** Title Case, Customize (your icon, colours, names, map) third, Account second, **Control Deck** as a single switch; the app no longer draws under the status bar.
- **Nudges:** 👍 ❤️ 👋 😊 🏠 🤠 👽 👹 (and 🍆 🍑 between grown-ups), from the app, from 👍/❤️ buttons on check-in notifications, and from the watch, which also offers them as a reply to a check-in. **FamLogic Face** watchface: serif type and the app's colour themes.

### ✅ Voice for chat (0.21.0)
- Built into the server image: Whisper (speech to text) and Piper (reading aloud), private and the same everywhere; the phone's own speech where it works better (Automatic: the Google edition uses it when present).
- Younger children: a big push-to-talk smiley that pops and glows while held, sends what they said and reads it back; new messages read aloud; "Oops, try again!" when nothing was understood. Everyone else: a small talk button that types into the message box. A per-child switch in Access & Control.
- Next for voice: the Pebble (hold to talk on watches with a microphone), the TV and Control Deck, and talking to your own agent (Phase 5).

### ✅ Home and chat polish (0.22.0)
- Arranging Home: tiles follow your finger and the others make room as you drag; the corner grip stretches an outline that snaps to half width, full width or big.
- One-button tiles (SOS, Check in) are the button, edge to edge. On Home the tab bar slides away (the tiles lead everywhere); it slides back in the other sections.
- Chats open full screen in portrait; younger children keep the split-open list, now pinned while a chat is open and edge to edge.

### ⏳ Android widgets
- **Map snapshot** of who's where; **check in** in one tap; **SOS** (hold); **one person's card** (where, battery, last seen); **latest chat message** with Reply; **talk to Mom** (a face that opens a direct chat or push-to-talk, for kids); **quick nudges** (send ❤️ to someone); **where's my ride?** (whoever is driving to you, and how far away).
- With the coming modules: **today's agenda**, **chores due now**, the **current routine's** checklist, the **shopping list**, **tonight's dinner**.

### ⏳ Settings overhaul
- A full review of the order and names of every setting in the app and the console (after 0.20's first pass: Title Case, Customize third, Account second).

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
- **Front Room, the TV launcher, as its own APK** (Android TV's launcher needs a separate build); same server.
- **Control Deck (countertop mode), round two:** a layout designed for a kitchen counter or wall, beyond full-screen home tiles.
- **Tablet layout, next:** the signed-in person's chores, to-dos and routine beside the map, filtered by their tags; a big talk button on kids' tablets.
- **Devices:** several devices per person, the console's Devices page with role, version, last seen and Revoke; display/launcher pairing with a code approved in the console.

### ⏳ Phase 7: Calendar
- Shared family calendar, events as Markdown notes with tags; **CalDAV** so phone calendars (DAVx⁵ on Android, iOS natively) and Thunderbird sync both ways; outside calendars (school, sports) by ICS link.

### ⏳ Phase 8: Meals and menus
- Weekly meal plans and recipes; ingredients to the shopping list; easy for an agent to plan.

### ⏳ Chat, next steps
- "Message" from a person's card on the map; chat on the Pebble (quick replies) and from the Index 01 ring; parents editing a child's switches from the app (today: the web console).

### ⏳ Later and parked
- **Pebble App Store listing** (draft in [docs/PEBBLE-APPSTORE.md](docs/PEBBLE-APPSTORE.md)), and **F-Droid / IzzyOnDroid** for the app.
- **Shareable invite message** with an app download link, once the app is in a store.
- **Profile pictures** (emoji icons cover the simple case; they stay as the fallback).
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
