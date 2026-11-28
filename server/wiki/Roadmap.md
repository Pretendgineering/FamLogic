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
8. **Every kind of family.** Don't assume the children go to school, that weekdays look alike, or that there are two parents. Homeschooling, unschooling, co-ops, shift work and shared custody are all normal: places are whatever the family names them, routines and check-ins are open-ended (any days, any times, changed as often as life does), and examples in the apps say "Co-op", "Grandma's" or "Practice" as often as "School".
9. **Everything connects.** Any piece of information (a person, a pet, a car, a bill, a recipe, an appointment) can be linked to anything it's relevant to, entered by talking to Steward (self-hosted or cloud AI), seen and edited in Obsidian by those who like to, and kept local and private. FamLogic remembers it all and reminds you of what you need when you need it.

### The long view
FamLogic aims to be the one family platform: location (in place of Life360), the family calendar, lists and chores (FamilyWall, Yuvomi, Google Keep), car upkeep (LubeLogger), activity (Strava), budget and more, for any age, family shape, lifestyle and level of tech comfort. Self-hosted first; later perhaps a hosted cloud version by subscription, with the strictest privacy policy we can write (your data is yours, never sold, never used for ads or training, exportable and deletable in one click). We're in alpha: this is the direction, not a promise of dates.

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
- **Module names** with a vibe: Thereabouts, **Chitchat**, **Datebook**, **Docket**, **Upkeep**, **Rhythms**, **Pantry**, **Corkboard**, **Front Room**, **Steward** (nudges stay nudges). **Plain Labels** switch (Customize, and the console's Look) for Map, Chat, Calendar…
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
A module's home tile is a **shortcut when small** (an icon, a name, a count) and a **live mini version when 2×2 or larger**: Upkeep shows the chores due, ticked right on the tile; Docket will show its lists and Datebook the next few events the same way. Opening the tile still goes to the full screen. One tile wide or tall, a tile becomes a strip of its newest items (across or down) with as many as fit.

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

### ✅ 4. Guided setup (the on-ramp), 0.37.0 (first version: modules, family name and PIN, people, Home and one more place, phones, a short tour; Settings → Server → Run setup again)
A walkthrough in the web console that sets everything up properly the first time, by asking questions rather than showing settings, and can be run again later (Settings → Run setup again) to revisit any step:
- **About you and the server:** your name, the family's name, the admin password, and the **parent PIN** (what it's for, and why children's devices need it).
- **Who's in your family?** Add each person with their role, in plain words: "a parent", "a grandparent", "a child", "someone who doesn't use the app (a baby)".
- **For each child:** "Can they read yet?" (picks the younger or older preset and picture icons), "Do they have their own phone or tablet?", "Should they be able to start chats, send photos, pause their location?", "Male or female reading voice?". Each answer sets the matching switches, with a short line on what it does.
- **Modules:** which parts the family wants (Thereabouts, Chitchat, and the ones coming), with a sentence each.
- **Places and groups:** add Home and the places you go often (a co-op, lessons, work, Grandma's) as fences (address search), and whether anyone needs a separate group (grandparents, friends).
- **Getting phones connected:** invites with QR codes for each person, in the order they were added, ticked off as each phone joins.
- **A short tour** at the end: where each setting lives now, so they can find it again.
- Setup can't be skipped on a new server, only paused and resumed; everything it sets can be changed later.
- **Next: questions that suggest modules.** "Do you have pets?", "Do you drive?", "Do you own or rent your home?", "Do you homeschool?", "Want help with a budget?" Each answer suggests modules to switch on (and examples to start with), but the family always makes the final choice.

### ✅ 5. Android widgets, 0.37.0 (first version: Family, and Check in + SOS)
- **Map snapshot,** **check in** in one tap, **SOS** (hold), **one person's card**, **latest chat message** with Reply, **talk to Mom** (for kids), **quick nudges**, **where's my ride?**. Module widgets come with each module.

### ⏳ 6. Modules, one at a time
In this order (each is finished before the next starts):
1. ✅ **Docket** (called Odds & Ends until 0.41.0; to-dos and lists), 0.34.0: shared lists and someone's to-dos as Markdown, sections, `@who`, tick from the tile, Steward can add and tick.
2. ✅ **Upkeep** (chores), 0.27.0. Next for it: chores on the Pebble, a "spend stars" list of rewards, and talking to Steward from the app ("I finished the dishwasher").
3. ✅ **Rhythms** (routines), 0.36.0: checklists that come round at a time on chosen days, current routine per person, ticks clear each time.
4. ✅ **Datebook** (calendar), 0.35.0: events as Markdown, repeats, two-way CalDAV with phones (DAVx5, iPhone, Thunderbird), agenda tile, Steward can read and add. Outside calendars by link since 0.40.0.
5. ✅ **Pantry** (meals and recipes), 0.36.0: the week's meals as Markdown, recipes, ingredients to the Shopping list in one tap.
6. **Steward** (the agent bridge): your own AI agent, using FamLogic's API with scoped keys and the vault guide; voice in, voice out.
7. ✅ **Corkboard** (the TV app, 0.43.0). Front Room (Control Deck round two) still to come.

### ✅ Steward everywhere, 0.39.0
- A small Steward button in the corner of every screen (app and console, not Chat): tell it what to add, change or remove there. It knows which page it was asked from, and the page updates after it answers.
- It uses FamLogic's own actions where one fits (chores, events, lists, meals, recipes) and otherwise reads the vault guide and reads and writes notes directly (grown-ups only), so it already works for modules still being built.
- Shown only when Steward is on (set up during first-run setup, or later in Settings → AI & API).

### ✅ 0.40.0: Steward polish, outside calendars, setup questions, Who's Who
- **Steward:** Undo puts back everything its last answer changed (leaving anything changed again since); each answer lists what it changed; hold to talk to it in the web console too.
- **Datebook:** outside calendars by link (school terms, sports, holidays). Parents add them; everyone sees them, read-only, in the app, the console and on phones through calendar sync.
- **Setup:** a few questions about the family (pets, a car, a home, routines, money, health, AI help) suggest modules; the same questions are under Settings → Modules → Help me choose.
- **Who's Who (profiles):** birthday, allergies, medicines, sleep, likes, dislikes, sizes, doctor, emergency contacts and notes for each person, in their People note. Everyone sees them; parents change anyone's, older children and grandparents their own. Print or share one as a page for a sitter, with the parents' numbers.

### ✅ 0.41.0: Docket, and Steward sorts your day
- **Odds & Ends is now Docket:** what's on the docket. One-off jobs, errands and projects (not the regular chores, which are Upkeep), and shared lists like Shopping.
- **Ramble it off:** tell Steward everything you need to do today and it sorts each thing: regular jobs become chores, anything at a set time goes on the calendar, one-off jobs go on your Docket, and things to buy go on Shopping. Everything can still be done by hand; Steward is optional.

### ✅ 0.42.0: Homestead
- **Homestead** is a simple profile of your home: tick what it has (furnace, smoke alarms, fridge filter, gutters, a lawn, a septic tank and more) and which days the bins and recycling go out. FamLogic keeps the matching upkeep as ordinary chores in Upkeep (for anyone) and puts bin days on the calendar. Untick something and its chores retire; each chore's history is the log.
- Steward can set up the home and tick off its jobs ("I changed the furnace filter").
- Driveway (vehicles) and Menagerie (pets) will work the same way: a profile and a log for each, feeding chores, the calendar and, later, the budget.

### ✅ 0.43.0: Corkboard, the TV app
- **A FamLogic app for Google TV** (its own download, famlogic-tv.apk): the family's day on the big screen. Today's feed, what's coming up, routines on now, meals, chores and who's where, moved through with the remote, plus shortcuts to your TV apps (Jellyfin, Spotify, SmartTube or any you pick).
- **Follows the sun:** light in the morning, dimming towards evening, dark at night, from sunrise and sunset at home.
- **Quiet time:** parents and grandparents start it from the TV tile on their phone or the console's TV page (30 minutes, an hour, two, or until ended); the TV shows a calm quiet-time screen until it ends.
- **Messages to the TV:** type or say one ("Inside voices please, the baby's asleep") and it shows big on the TV until someone presses OK.
- Can be the TV's home screen (see the Corkboard guide).

### ✅ 0.44.0: The TV as one dashboard
- **One screen, no scrolling:** a greeting for the time of day, and the family's Routines (on now and later today), Upkeep and Docket side by side.
- **Buttons down the right** for the map, the feed, Steward (by the remote's microphone), Datebook and Pantry; choose which in Customize.
- **The launcher** shows 1 to 6 big picture tiles; parents can upload a picture for any app on the console's TV page.

### ✅ 0.51.0: Pictures, colour, and a console that fits together
- **Picture icons** for every module and tile (Settings → Look and Feel → Picture icons): Chitchat is an envelope with a pencil, Upkeep a broom sweeping up dust, Homestead a cosy house, and new pictures for Dossier, Driveway, Vitals, Ledger, Huddle, Corkboard, Steward, pets, contacts and the glance tiles.
- **Themes you can see:** the whole background, cards and lines take a soft wash of the theme's colour, in the app and the console. The SOS bar's tint follows the theme; its words, picture and alarm stay red.
- **Younger children:** everyone they share a group with is already in their chats (a chat is made with the first tap), no times or dates in chats unless a parent switches them on, plain labels (Map, Chat) to start with, and a rounded map.
- **Dossier holds the pets and the family's contacts** (Menagerie and Rolodex are part of it now): a list on the left, the one you picked on the right, a quick pop-up to add one, and printing (one page each, or everyone).
- **Homestead looks after more than one place:** a grandparent's flat, a cabin, a barn, each with its own upkeep. Add your own jobs, and change how often any job comes round.
- **The web console's Home** is a grid of tiles each person arranges (Arrange: drag, resize, add from the drawer, remove).
- **Family** has everything about people in one place: open someone to set what they can use (Access & Control is part of it now), invites, groups, the parent PIN, and paired displays listed apart from people. Displays are never on the map and never warn about location or battery.
- **Settings → Look** (for everyone): a theme picker like the app's, light or dark, plain labels, and the order of the bar on the left. Backups live only in Settings → Server and backup. The Settings icon is a gear.
- **The main admin is a family member:** at setup (or from Family) they make their own account and sign in with it; the admin password stays for recovery.

### ✅ 0.50.1: Home tiles that grow, and nine new ones
- **Every tile shows more as it grows:** Upkeep gets a column per person at 4 or 5 wide; Datebook a column a day at 3 or more wide; Rhythms shows every routine on now side by side; Pantry shows today's meals, then the week; Dossier shows faces with allergy and medicine badges; Homestead the next jobs and bin days; Huddle the next meeting's topics; Corkboard quiet time and the TV's last message.
- **New tiles:** Today (routines on now, chores due and what's on), Who's home, Weather (from Open-Meteo, free; only your home's position, rounded to about a kilometre, is sent), Clock, Countdown (birthdays and all-day events), Sticky note (one note the whole family shares), Feed, and the buttons Quick add (straight onto Shopping or your to-dos) and Quiet time (nap time on the TV in one tap).
- Tab names stay on one line, shrinking to fit.

### ✅ 0.50.0: Steward on small computers, and Steward's own button
- **A small prompt:** Steward now sends about 900 tokens with each request instead of about 4,500, opening the skill it needs (chores, calendar, lists...) as it goes, the way Hermes Agent does. The fixed part never changes, so model servers keep it cached. Answers are quicker and cheaper everywhere, and small models on small computers become practical.
- **Built in:** Settings → AI checks this computer and offers small open models (Qwen3.5, Liquid LFM3, Phi-4 mini, Gemma 4) with download sizes, memory needs, licences and a guess at how quick each would be. Download one and Steward runs it right here, with nothing else to set up.
- **Or a computer at home, or the cloud,** with a second model to hand over to when the first can't answer (it's down, too slow, or says a request is too much for it), never after it has changed something.
- **Test it:** real family requests, run on a made-up family and checked, with times. `scripts/steward-bench.sh` compares models on a graphics card or a small CPU.
- **Outside agents:** Hermes, Claude Desktop or any MCP client can use the same skills with a key (MCP address and a Hermes skill in Settings → AI).
- **Steward's button** sits in the corner of every screen (only when Steward is set up), moving over beside a screen's own button, and above a chat's message box or the map's sheet. The Steward tile is gone.
- **Home tiles** go up to 5 x 5, taller than wide too; one-button tiles are 1 x 1 (just the icon) or 2 x 1 (with its name); a corner handle resizes both ways at once.

### ✅ 0.49.0: Kids' polish, new names, and a Home of up to 5 across
- **For younger children:** picture icons everywhere (Datebook, Docket, Rhythms and Pantry have theirs now); no settings at all, just a colour button with each theme as a bar in its own colour (a parent still gets in the secret way); the family feed beside Home only if a parent switches it on (console → Access); bigger faces in Chitchat, and on tablets the camera sits beside the talk bar, matching it.
- **New names:** Who's Who is now **Dossier**, Outings is **Vitals**, and Garage is **Driveway** (plain label: Vehicles). The themes And Chill and Suspicious Orange are now **Spider-Man** and **Safety Cone**.
- **Home, 2 to 5 tiles across** (Look and Feel → Home Screen). Narrow tiles show just their icon, as shortcuts. One-button tiles (SOS, Check in, Pickup, Nudges, the TV, Dossier, Homestead, chat shortcuts) stay at most one row tall and two wide. **Nudges** (pick the nudge, then who) and **Pickup** (pick who) work now. Tile names stay on one line, and the map has rounded corners like every other tile.
- **The TV tile** asks which display first when there's more than one; quiet time and messages can go to one display or all of them.
- **Displays are known by name:** pairing a display with a name that's already listed takes over that listing (the old pairing is signed out), and a display that's signed out is gone from the list.

### ✅ 0.48.0: The TV's screen saver
- **Screen saver:** after the TV sits idle, a family clock (drifting, with what's next and who's home), the family photo album (drag photos or a folder onto the console), a photo link (an Immich shared album, a photo feed, a webcam), calm themes (gradient, starry sky, the season), or a mix. Any button wakes it; hold OK to ask Steward.
- **Family updates** pop up in a corner as they happen, over the screen saver and (if you like) the home screen.
- Tidier TV: no empty launcher tiles, apps on the launcher aren't repeated in Apps, Settings in popups (Quick Access Modules, Hidden apps, Remote buttons, Screen saver), and no more hopping in the app grid.

### ✅ 0.47.0: Huddle, family meetings
- **Huddle:** a parent writes a short agenda (or asks Steward to), and the family clicks through it together on the TV, a tablet or the console: hand out jobs or ask for volunteers, keep notes, then send the jobs to the Docket. Each meeting is a note in the vault's Meetings/ folder, so it's the minutes too.
- On the TV, the side buttons share the height, so more of them fit.

### ✅ 0.46.0: Calendar views, routine pictures, and a roomier TV
- **Calendar views** in the app, the console and on the TV: 3 days, the work week, the week and the month, each event in its person's (or outside calendar's) colour.
- **Routine pictures:** each step gets a picture (🪥 Brush teeth, 🎒 Pack school bag), or one you choose by starting the step with an emoji.
- **On the TV:** the map lists everyone to zoom to; the feed fills the screen and opens where things happened; hide apps from the drawer; change a tile's picture from the TV's files; a little more fits on screen; the launcher stays centred; a clearer selection.

### ✅ 0.45.1: Clearer on the TV
- Steward has its own round button at the bottom right; the selection eases from button to button and is easy to see everywhere (it fills with the accent); the app drawer starts on the first app.
- The Docket tile says "3 to do" or "3 left" instead of "3 to get"; the talk bar says "Hold to talk", and "let go to type it" where it types rather than sends.

### ✅ 0.45.0: The TV as a launcher
- **Apps:** every app on the TV in one drawer; press and hold OK on an app to add it to the launcher, move it, see its App info or uninstall it.
- **Home and the remote:** an optional accessibility helper lets FamLogic take over from Google TV's own home screen, start when the TV turns on, and give remote buttons new jobs.
- **Fits every TV:** the screen stretches to fit any resolution or display size, with a setting for TVs that cut off the edges.
- **Messages** rise in with a soft chime, and can be read aloud; a wide FamLogic banner for the TV's app row; displays are named by their own name in the console.

### 💡 Further out
- **Ledger (budget):** bills on the calendar, spending, groceries and paying off debt (see Future modules).
- **Cameras (RTSP):** the family's cameras (doorbell, nursery, driveway) on the TV and in the app; a camera's view popping up on the TV when the doorbell rings.

### 💡 Future modules (switch-on placeholders since 0.39.0: Vitals, Driveway, Ledger; Steward keeps their notes until each is built. Menagerie and Rolodex became part of Dossier in 0.51.0)
Every one is a module the family can switch off, like the rest, and keeps its data as Markdown in the vault, printable or exportable where it helps.
- **Health & activity (Vitals, grown from walks and runs):** family walks and each person's runs or bike rides (route, distance, time, pace), shared to the feed if you like. Then:
  - **Steps** from the phone and the Pebble, shown anywhere someone wants them (Home, a tile, the watch, the family display), or nowhere.
  - **Meals and macros:** log what you ate by talking to Steward, by picking a Pantry recipe (its nutrition worked out once from the ingredients), or from a grocery receipt; calories, protein, carbs, fat and any goals you set. Ties into groceries (what's in the house), the meal plan and the budget.
  - **Health data:** from Android's Health Connect and the Pebble's health service: sleep, heart rate, weight, water, medication taken, mood, cycle, or anything else a person wants to track, as dated notes they own.
  - **Scheduling:** workouts, walks and meal times on the calendar and in routines.
  - Every part, and every metric, is switched on or off per person; nothing is tracked unless someone chose it. Graphs and trends over any period; everything exportable.
- **Vehicles (Driveway):** each vehicle as a note. Fuel log by talking to Steward ("I just filled up the Sienna, 15 gallons, 56 dollars" → a fuel entry with miles, cost and price per gallon), with graphs and averages over time (mpg, cost per mile, monthly spend); oil changes and maintenance as reminders on the calendar by date or mileage; registration and insurance renewals. Later: OBD-II (a Bluetooth adapter through the phone) for mileage and fault codes.
- **Budget:** fixed recurring expenses, flexible variable spending, and debt payoff (snowball, avalanche, custom) with payoff calculators and interest; Steward can help build a budget from what you tell it. Imports transactions from SimpleFIN, or a bank statement uploaded by hand (CSV/PDF, OCR when needed), then sorted into categories. Ties into groceries, cars and bills. Needs a lot of honing.
- **Bills on the calendar:** each bill's due day and amount shows in its own lane of the Datebook, so you see what's going out (and coming in) and when.
- **Grocery spending:** what you bought and what it cost, by rambling to Steward or (better) a photo of the receipt read by OCR. Feeds the budget, a rough food inventory, price tracking over time, and meal planning.
- **Trash and recycling day:** set the day(s) once; it shows on the calendar and can become a chore for someone.
- **Pets:** each pet as a note: feeding and walking schedules (as routines or chores), medications, vet visits and vaccinations as reminders; prints as one page for a sitter.
- **Profiles (everyone):** allergies, medications, sleep and nap schedule, likes and dislikes (foods, activities), sizes, birthday, doctor, parents' names and phone numbers, emergency contacts. Prints as a one-page sheet for babysitters and grandparents.
- **Home (owners and renters):** recurring upkeep like air filters, smoke alarm batteries, gutters, HVAC service, water heater flush, with sensible defaults to pick from, as calendar reminders and chores; warranties and manuals linked.
- **Contacts:** the family's address book as Markdown, two-way with phones through CardDAV (like Datebook's CalDAV); linked to profiles, pets (the vet), cars (the mechanic) and the home (the plumber).

### ⏳ Later and parked
- **Voice, beyond chat:** the Pebble (hold to talk), the TV and Control Deck, read-aloud with quiet hours.
- **Devices page** in the console (several devices per person, last seen, Revoke); pairing displays with a code.
- **Pebble App Store listing**, and **F-Droid / IzzyOnDroid** for the app; a shareable invite message with a download link once the app is in a store.
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
