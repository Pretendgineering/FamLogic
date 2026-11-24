# FamLogic roadmap

FamLogic is a self-hosted family hub built from modules; location sharing (the Thereabouts module) came first. How the pieces fit together is in [Architecture](Architecture.md).

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
- **Themes,** chosen per device (app) or per browser (console), each with light and dark: **Default** (a muted Dracula), Warm paper, Forest, Ocean, and High contrast. (Purple was dropped in 0.20; renamed and joined by many more in 0.23.)

### ✅ Phase 3b: Home screen, parents' controls and tablets (0.19.0)
- **Home screen** with tiles: a live map that opens Thereabouts, chat with the latest message, SOS, check-in, and module tiles as they arrive. Adults arrange their own; parents arrange children's.
- **Parents' controls:** one family **parent PIN** (set by an admin) locks settings on children's devices and displays; per child, parents start from the *younger* or *older* preset and switch sections and features on or off.
- **Chat moderation:** a child's new group waits for a parent's OK; parents can pause, block or delete chats with a child in them and delete messages; anyone can delete their own messages and **mute** a chat (1 h, 8 h, until tomorrow, always).
- **Device mode:** phone, interactive tablet or **display tablet** (full screen, screen on while charging, no location sharing, settings behind the PIN).
- **Tablets and landscape:** a navigation rail, the map beside the people list, the chat list beside the open chat, chats as a shade in portrait; bigger pictures and buttons for younger children.
- Small things: rotating keeps what you typed; a camera icon that takes a new photo or picks one; senders' pictures in chat; a clearer selected tab.

### ✅ Phase 3c: Access & Control, grandparents and a friendlier app (0.20.0)
- **Module names** with a vibe: Thereabouts, **Chitchat**, **Datebook**, **Odds & Ends**, **Upkeep**, **Rhythms**, **Pantry**, **Corkboard**, **Front Room**, **Steward** (nudges stay nudges). **Plain Labels** switch (Customize, and the console's Look) for Map, Chat, Calendar…
- **Access & Control** page in the console: everyone in a list that opens into their switches. Per child: a preset, then each module **Off / View only / Can add / Full** and single features; bigger buttons and picture icons; their home screen (drag to reorder, sizes); **open their settings for 10 minutes** from afar. The parent PIN lives here.
- **Grandparents:** a role with the whole app but no parents' controls or children's chats; bigger buttons and picture icons as options.
- **Children's settings:** none for younger children, only Customize/Account/About for older ones; the secret way in is holding the family name for 5 seconds, then the parent PIN.
- **Home screen:** tiles in half, full-width and big sizes, dragged by a handle and resized with a corner grip; half tiles pair up or fill the row; in landscape the map sits on the right beside the tiles.
- **Picture icons** (a tiny map, a talking face, an alarm, a house, a waving hand, a camera): the default for younger children. A bigger SOS button with an icon.
- **Layouts:** map and navigation rail on the right; chats split the list open in portrait (younger children; everyone else gets the chat full screen, since 0.22.0); photos wait with an ✕ until Send; children can't delete messages or mute chats unless a parent allows it; family chat and group icons.
- **Settings:** Title Case, Customize (your icon, colours, names, map) third, Account second, **Control Deck** as a single switch; the app no longer draws under the status bar.
- **Nudges:** 👍 ❤️ 👋 😊 🏠 🤠 👽 👹 (and 🍆 🍑 between grown-ups), from the app, from 👍/❤️ buttons on check-in notifications, and from the watch, which also offers them as a reply to a check-in. **FamLogic Face** watchface: serif type and the app's colour themes.

### ✅ Voice for chat (0.21.0)
- Built into the server image: Whisper (speech to text) and Piper (reading aloud; Kokoro since 0.25), private and the same everywhere; the phone's own speech where it works better (Automatic: the Google edition uses it when present).
- Younger children: a big push-to-talk smiley that pops and glows while held, sends what they said and reads it back; new messages read aloud; "Oops, try again!" when nothing was understood. Everyone else: a small talk button that types into the message box. A per-child switch in Access & Control.
- Next for voice: the Pebble (hold to talk on watches with a microphone), the TV and Control Deck, and talking to your own agent (Steward).

### ✅ Home and chat polish (0.22.0)
- Arranging Home: tiles follow your finger and the others make room as you drag; the corner grip stretches an outline that snaps to half width, full width or big.
- One-button tiles (SOS, Check in) are the button, edge to edge. On Home the tab bar slides away (the tiles lead everywhere); it slides back in the other sections.
- Chats open full screen in portrait; younger children keep the split-open list, now pinned while a chat is open and edge to edge.

### ✅ Layout, settings and looks (0.23.0)
- **Settings in seven pages,** edge to edge: Location Sharing, You, Look and Feel, Alerts & Safety, Groups & Fences, Watch & Ring, About (with Device Mode folded under Advanced). Lists that are only just too long squeeze their rows to fit instead of scrolling a few pixels.
- **Home screen:** flat, edge-to-edge tiles (squares appear only while arranging); a **drawer of tiles** to drag onto the screen; half-width tiles keep their size (the map too); the Chitchat tile's half size has quick buttons for the family chat and your last chat; **chat shortcut** tiles open one chosen chat, with that person's face; "coming soon" tiles for planned modules. Module switches in Look and Feel (admins: the family's; everyone else: hide on this phone).
- **Map:** opens where it last was and jumps (rather than flies) to the family, so there's no jittery first load.
- **Talking:** a wide talk bar above the message box for children and tablets; a friendlier, grinning smiley.
- **Themes:** Hannah's Theme, Hobbit Door, Boring Blue, Lofi Cabin, Logic Noir, Suspicious Orange, And Chill, Night Shift, Gamer Mode, Grandma's Couch. More icon colours, a colour mixer and many more emoji.

### ✅ Voices and layout (0.25.0)
- **Reading aloud:** Kokoro voices on the server (warm female and male), each person's chosen **reading voice**, a small speaker beside every text message, emoji and stretched words tidied before reading; a plain microphone for grown-ups, a smiley with sound waves for children; a paper airplane for the children's Chitchat; "Sent!" instead of reading the whole message back.
- **Home screen:** 2 or 3 tiles across (Look and Feel), tiles 1 to 3 columns wide and 1 or 2 rows tall.
- **Console:** the same edge-to-edge design language as the app (flat sections, hairlines, the map to the edges).

### ✅ Upkeep, the family feed and Steward keys (0.27.0)
- **Upkeep (chores)**, the first module on the Markdown vault: each chore is a note in `Chores/` (who, how often, when it's next due, optional **stars**), so Obsidian, the console, the app and AI agents all share it. Children tick theirs off with a big round tick (done straight away; parents are told and can undo); parents add chores, tick for someone, and see everyone's stars.
- **The family feed:** swipe right from Home for today's timeline ("Sam got to School", "Mia did *Unload the dishwasher* ★2"), with earlier days a tap away. Kept on the server for 60 days, with the same who-sees-what rules as alerts.
- **Steward API keys:** the console (Settings → AI & API) makes named keys with scopes (people, chores, feed, location), shown once, logged and revocable; agents act as a grown-up named after the key. An OpenAPI description at `/api/v1/openapi.json`, and rules for agents in the vault guide. ("Tell Sam to unload the dishwasher" works today with an agent like Hermes; talking to it from the app comes with Steward.)
- **Home:** the map tile takes any size (a plain preview when it's one tile wide or tall, its title bar from 2×2); dragging a tile now makes room wherever you hold it; the resize bars are easier to grab near the screen edge without triggering Back. Module switches live only in the console now: on a phone, tiles you don't want go in the drawer.

### Design rules
- **Say it short.** Descriptions, hints and empty states are one plain line; details live in the wiki.
- **No emoji in text.** Emoji are for people's own icons and nudges, never in labels, descriptions or "nothing here" messages.
- **The second colour is for small accents** (tile icons, the family name, selected chips and switches); the main colour is for actions.

### Design rule: tiles grow into mini versions
A module's home tile is a **shortcut when small** (an icon, a name, a count) and a **live mini version when 2×2 or larger**: Upkeep shows the chores due, ticked right on the tile; Odds & Ends will show its lists and Datebook the next few events the same way. Opening the tile still goes to the full screen. One tile wide or tall, a tile becomes a strip of its newest items (across or down) with as many as fit.

### ✅ Back to cards (0.26.0)
- After trying the flat, edge-to-edge look, the app and console go back to **rounded cards with a little depth** (Material You): settings in cards, home tiles as spaced cards; every arranging behaviour stays (tap to select, edge bars, gliding tiles, the drawer). The map's pull-up sheet stays.

## Focus: the core three, polished first

The map, chat and safety features are what families use every day. Next, each gets a round of real-use polish before any new module starts. After that, modules come **one at a time**, each finished (app, console, watch, docs) before the next.

### ✅ 1. Map (Thereabouts), 0.24.0
- The map fills the screen; the people are in a **pull-up sheet** (peek, half, full) that you drag or scroll; landscape tablets keep them beside the map.
- **Pins:** a "how long ago" tag, an arrow for the way someone's heading, a pulsing halo during an SOS, and one shared pin (stacked faces) for people in the same spot.
- **Fences edited on the map:** long-press to drop one, drag inside to move, drag the edge dot to resize, name and save in a bar at the bottom.
- **Trips** as a day **timeline** of stops and drives; **Share** a trip to a chat.
- **Resting** phones update less often and wake on movement (the significant-motion sensor); "last seen" worded the same everywhere, and honest about old locations.

### ✅ 2. Chat (Chitchat), 0.32.0
- "Message" from a person's card on the map; reply to a message; reactions (the friendly nudges).
- Photos: a full-screen viewer (pinch to zoom, save), several sent at once.
- Voice messages (tap the mic when the box is empty); search across your chats; a "new messages" button when you've scrolled back; "is typing…".

### ✅ 3. Safety, 0.33.0
- **SOS** opens full screen on everyone else's phone, even locked, with an alarm that rings on silent: who, where, how long ago, how far, and Call, Directions, I'm on my way and I've seen it.
- **Check-in times** for children (a parent sets them on the child's card): a fence, days and a window; the child is reminded at the start, parents are told if they haven't arrived or checked in by the end.
- **Fence alerts** switched on or off right in the fence editor, with a test alert.
- **Where's my ride?** Answer a "Pick me up" (or an SOS) with "On my way" and the other person sees how far you are and about how long until you get there.
- **Call buttons** on SOS, pick-up, low-battery, quiet-phone and missed check-in alerts, for everyone who adds their number (Settings → You).

### ⏳ 4. Guided setup (the on-ramp)
A walkthrough in the web console that sets everything up properly the first time, by asking questions rather than showing settings, and can be run again later (Settings → Run setup again) to revisit any step:
- **About you and the server:** your name, the family's name, the admin password, and the **parent PIN** (what it's for, and why children's devices need it).
- **Who's in your family?** Add each person with their role, in plain words: "a parent", "a grandparent", "a child", "someone who doesn't use the app (a baby)".
- **For each child:** "Can they read yet?" (picks the younger or older preset and picture icons), "Do they have their own phone or tablet?", "Should they be able to start chats, send photos, pause their location?", "Male or female reading voice?". Each answer sets the matching switches, with a short line on what it does.
- **Modules:** which parts the family wants (Thereabouts, Chitchat, and the ones coming), with a sentence each.
- **Places and groups:** add Home and School as fences (address search), and whether anyone needs a separate group (grandparents, friends).
- **Getting phones connected:** invites with QR codes for each person, in the order they were added, ticked off as each phone joins.
- **A short tour** at the end: where each setting lives now, so they can find it again.
- Setup can't be skipped on a new server, only paused and resumed; everything it sets can be changed later.

### ⏳ 5. Android widgets
- **Map snapshot,** **check in** in one tap, **SOS** (hold), **one person's card**, **latest chat message** with Reply, **talk to Mom** (for kids), **quick nudges**, **where's my ride?**. Module widgets come with each module.

### ⏳ 6. Modules, one at a time
In this order (each is finished before the next starts):
1. **Odds & Ends** (lists and to-dos) on the Markdown vault, with tags (`@Sam`, `#kids`) and time of day. The vault itself has started: its location setting, People notes and the agents' formatting guide.
2. ✅ **Upkeep** (chores), 0.27.0. Next for it: chores on the Pebble, a "spend stars" list of rewards, and talking to Steward from the app ("I finished the dishwasher").
3. **Rhythms** (routines and daily rhythms): checklists that switch with the time of day.
4. **Datebook** (calendar) with CalDAV and outside calendars by link.
5. **Pantry** (meals and recipes), feeding the shopping list.
6. **Steward** (the agent bridge): your own AI agent, using FamLogic's API with scoped keys and the vault guide; voice in, voice out.
7. **Corkboard / Front Room** (Control Deck round two and the Android TV launcher, its own APK).

### ⏳ Later and parked
- **Voice, beyond chat:** the Pebble (hold to talk), the TV and Control Deck, read-aloud with quiet hours.
- **Devices page** in the console (several devices per person, last seen, Revoke); pairing displays with a code.
- **Pebble App Store listing** (draft in [Pebble-App-Store](Pebble-App-Store.md)), and **F-Droid / IzzyOnDroid** for the app; a shareable invite message with a download link once the app is in a store.
- **Profile pictures** (emoji icons stay as the fallback).
- **Home Assistant** integration (presence, chores done, a dashboard).

### 🧊 Tabled (bottom of the list until further notice)
- **Google Find Hub trackers:** no official API. Alternative: family phones listening for the family's own cheap Bluetooth tags.
- **iPhone app:** notes below.

#### iPhone: how feasible?
Possible, with real limits. The server and its API already work for any client, so an iPhone app is "only" a second app (SwiftUI, or Kotlin Multiplatform sharing the app's logic). The hard parts are Apple's rules, not the code:
- **Background location** works (significant-change and region monitoring, plus fences via iOS geofences), but iOS decides when the app runs; updates are less regular than Android's always-on service.
- **Instant alerts** need Apple's push service (APNs). FamLogic avoids Google push on Android with its own always-on connection; iOS doesn't allow that, so the server would need to send pushes through Apple, which needs an Apple developer account ($99/year) and the server reaching Apple's servers.
- **Distribution:** the App Store (review, the developer account), or TestFlight for a family. No sideloading like an APK.
- **Pebble** works on iPhone (the new Pebble app supports iOS), so the watch could follow.

A sensible first step would be a smaller iPhone app (map, chat, SOS, check-in, push alerts), after the core three are polished on Android.

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
10. ✅ **0.12.0:** Pebble watchapp and Family watchface, timeline pins, fall detection and heart rate (opt-in), Index 01 ring webhooks, SOS "seen" receipts, place reminders. See [Pebble](Pebble.md).
11. ✅ **0.13.0:** two builds: FOSS and the Google edition (fused location, activity recognition).
12. ✅ **0.14.0:** Pebble round two: watchface grid, hold-Select SOS, radar and street maps on the watch, nudges, watch themes.
