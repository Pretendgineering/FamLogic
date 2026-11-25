# API

FamLogic's HTTP API, used by the Android app, the web console, the Pebble bridge and (soon) AI agents.


```
 phone ──POST /api/location──▶  server (one JSON file)  ◀──GET /api/admin/members── web console
 phone ◀──GET /api/members────          ▲
                                        └── tiles come straight from the tile server to each phone/browser
```

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/invites/<code>` | invite code | What a code is for: `join`, or `reset` for an existing person |
| `POST /api/join` `{code, name, username, password}` | invite code | Join, or redeem a reset link. Returns a device token |
| `POST /api/signin` `{username, password}` | password | Sign this device in. Other devices stay signed in (up to 6; the one unused longest goes). Phones' locations win over tablets' |
| `POST /api/me/password` `{currentPassword, newPassword, username?}` | `Bearer <token>` | Set or change your password |
| `POST /api/me/circles/<id>` `{sharing}` | `Bearer <token>` | Share or stop sharing your location with one group |
| `POST /api/me/circles/join` `{code}` | `Bearer <token>` | Join another group with an invite code |
| `POST /api/places` `{circleId, name, lat, lon, radius}`, `POST`/`PATCH`/`DELETE /api/places/<id>` | `Bearer <token>` | Add, change or delete a fence in one of your groups |
| `GET /api/geocode?q=<address>` | `Bearer <token>` | Address search for fences (results: `[{name, lat, lon}]`) |
| `GET /api/members/<id>/trail` | `Bearer <token>` | Someone's trail, if they keep one and you can see their location |
| `GET /api/events/wait?since=<cursor>&wait=50` | `Bearer <token>` | Waits until there's a new alert (or `wait` seconds), then returns `{events, cursor}`. The app keeps one open for instant alerts |
| `POST /api/sos` `{loc?}`, `POST /api/sos/end` | `Bearer <token>` | Raise or end an SOS |
| `POST /api/checkin` `{note, loc?}` | `Bearer <token>` | Check in |
| `POST /api/location?since=<cursor>` `{lat, lon, acc, speed, heading?, battery, charging, time}` (heading: 1-360 degrees while moving) | `Bearer <token>` | Report a fix; the reply carries new alerts `{events, cursor}` |
| `GET /api/members?since=<cursor>` | `Bearer <token>` | Everyone's latest position, map settings, your feature settings (including `kid`: what you may use, `home` and `pinSet`), and new alerts |
| `PATCH` or `POST /api/me` `{name?, paused?, shareLowBattery?, home?, big?, pictures?, voice?}` | `Bearer <token>` | Rename yourself / pause / feature switches / arrange your home screen (adults; parents arrange children's) |
| `DELETE /api/me` | `Bearer <token>` | Leave |
| `DELETE /api/admin/members/{id}/devices/{dev}` | admin session | Sign out one device (the console's Devices page lists each one) |
| `/api/admin/*` | console login cookie | Admin pages (invites, members, roles, modules, backups). `GET /api/admin/session` says who's logged in |
| `/api/chat`, `/api/chat/{id}/messages`, … | `Bearer <token>` or a parent's console session | Chat: see [Architecture](Architecture.md#chat-built-in-0160) |
| `POST /api/chat/{id}/messages` `{text, replyTo?}` or multipart (`photo` or `audio` + `audioMs`, `text`, `replyTo`) | `Bearer <token>` | Send a message, a photo or a voice message (AAC .m4a, Ogg or WAV, up to 4 MB) |
| `POST /api/chat/{id}/messages/{mid}/react` `{kind}`; `POST /api/chat/{id}/typing`; `GET /api/chat/search?q=` | `Bearer <token>` | React with a nudge (`""` takes it back); say you're typing (shown for 6 s in `typing` on the messages); search the chats you can read |
| `POST /api/chat/{id}/moderate` `{action}` | a parent | `approve`, `pause`, `resume`, `block`, `unblock` or `delete` a chat with a child in it (the main admin: `/api/admin/chat/{id}/moderate`, any chat but the family chat) |
| `DELETE /api/chat/{id}/messages/{mid}` (or `POST …/delete`) | `Bearer <token>` | Delete your own message, or (parents) any message in a chat with a child in it |
| `POST /api/voice/transcribe` (body: 16 kHz mono 16-bit WAV, up to a minute) | `Bearer <token>` | Speech to text on the server: `{text}` (`""` if nothing was understood). Feature `voice-stt` |
| `POST /api/voice/speak` `{text, voice?}` | `Bearer <token>` | Reads text aloud (`voice`: `female`, `male` or empty): `audio/wav`; 422 if nothing's left to say once emoji and the like are removed. Feature `voice-tts` |
| `GET /api/modules` | `Bearer <token>` | The family's modules: `{modules:[{id, name, plain, description, available, enabled}]}` |
| `PUT` or `POST /api/modules` `{enabled:{id:bool}}` | `Bearer <token>` (an admin) | Switch modules on or off for the whole family, from the app |
| `GET /api/v1/vault/guide` | none | The notes vault's formatting guide for people and AI agents (`text/markdown`) |
| `GET /api/admin/vault` | admin session | Where the vault is, whether FamLogic can write there, the user it writes as, and the number of notes |
| `POST /api/pin/check` `{pin}` | `Bearer <token>` | Checks the family's parent PIN (204 or 403; locks after repeated wrong tries) |
| `PUT /api/admin/pin` `{pin}` (or `PUT /api/family/pin` from an admin's phone) | admin | Sets the parent PIN (4–8 digits; `""` removes it). `GET /api/admin/pin` says whether one is set |
| `GET`/`PUT /api/members/{id}/kid` `{preset?, modules?, features?, home?, big?, pictures?}` | a parent (`/api/admin/members/{id}/kid` for admins) | A child's (or grandparent's) settings: preset `younger` or `older`; each module (`thereabouts`, `chat`, `calendar`, `todos`, `chores`, `meals`) `off`, `view`, `add` or `full`; features (`sos`, `checkin`, `requests`, `nudges`, `trips`, `pause`, `chat_start`, `chat_photos`, `chat_delete`, `chat_mute`) on or off; home tiles (`map:big`, `chat:half`…); bigger buttons and picture icons. Grandparents take only `home`, `big` and `pictures`. A new preset starts afresh |
| `POST /api/members/{id}/unlock` `{minutes}` | a parent (`/api/admin/members/{id}/unlock` for admins) | Opens settings on that person's device for a while (default 10 minutes) |
| `PATCH /api/chat/{id}` `{emoji, color}` | parents (family chat), people in a group | The chat's icon |
| `GET /api/chores` | `Bearer <token>`, a key with `chores`, or the console | Upkeep: `{chores:[{id, title, who:[ids], every, due, when, stars, notes, done:[{date, by}], finished, file}], stars:{id:n}, today, canEdit, level}`. Chores are Markdown notes in the vault's `Chores/` folder |
| `POST /api/chores` `{title, who, every?, due?, when?, stars?, notes?}`; `PATCH`/`DELETE /api/chores/{id}` (or `POST …/delete`) | parents, keys, children with `full` (children with `add`: their own, without stars) | Add, change or retire a chore (retiring sets `archived: true`; the note stays) |
| `POST /api/chores/{id}/done` `{date?, by?}` | anyone it's for; parents and keys for anyone | Tick it off: stars go to `by`, parents are told, the feed records it, a repeating chore moves to its next date |
| `POST /api/chores/{id}/undo` `{date?}` | grown-ups and keys; a child for their own tick | Take a tick back (and its stars) |
| `POST /api/members/{id}/stars` `{add}` or `{set}` | parents, keys with `chores` | Give or spend stars |
| `GET /api/rewards`; `POST /api/rewards {title, cost}`; `DELETE /api/rewards/{id}` | anyone with Upkeep; parents and keys change the list | Rewards stars can buy, and asks (everyone's for parents, your own for children) |
| `POST /api/rewards/{id}/ask`; `POST /api/rewards/asks/{id}/approve` or `/decline` | the child asks; a parent answers | Approving takes the stars (409 if there aren't enough) and goes on the feed |
| `GET /api/lists`; `POST /api/lists {title, kind: list or todos, who?}`; `DELETE /api/lists/{id}` | `Bearer <token>`, keys with `lists`, the console | Odds & Ends: lists and to-dos (Markdown in `Lists/`, `To-dos/`); someone's to-dos are seen by them and grown-ups |
| `POST /api/lists/{id}/items {text, who?, due?, section?}`; `POST /api/lists/{id}/items/{item} {done}`; `DELETE …/items/{item}`; `POST /api/lists/{id}/clear` | anyone can tick; adding needs `add` | Items are found by their text, so notes edited in Obsidian still line up |
| `GET /api/events?from=<ms>&to=<ms>` (default: the next 31 days); `GET /api/events/{id}`; `POST /api/events {title, start, end?, who?, where?, notes?, every? or rrule?}`; `PATCH`/`DELETE /api/events/{id}` | `Bearer <token>`, keys with `calendar`, the console; changing needs `add` | Datebook: events are notes in `Calendar/`; GET expands repeats into occurrences |
| CalDAV at `/dav/` (and `/.well-known/caldav`) | your username and password (Basic auth) | Two-way sync with phones and computers (DAVx5, iPhone, Thunderbird): one family calendar; extras like alarms are kept in `DATA_DIR/caldav/` |
| `GET /api/routines`; `POST /api/routines {title, who?, starts?, days?, items}`; `POST /api/routines/{id}/items/{item} {done}` | `Bearer <token>`, keys with `lists` | Rhythms: routines in `Routines/` (and `Rhythms/` notes); `now` says whose routine is on; ticks clear when a routine comes round |
| `GET /api/meals?week=2026-W49`; `PUT /api/meals/{week} {date, meal, dish}`; `POST /api/recipes {title, ingredients, method}`; `POST /api/recipes/{id}/shop` | `Bearer <token>`, keys with `lists` | Pantry: the week's meals (`Meals/<week>.md`) and recipes (`Meals/Recipes/`); shop puts the ingredients on Shopping |
| `GET /api/feed?from=<ms>&to=<ms>` | `Bearer <token>`, a key with `feed` | The family feed (default the last 24 hours, at most 8 days): `{items:[event]}` newest first, the same visibility as alerts |
| `GET /api/steward`; `POST /api/steward {text}`; `DELETE /api/steward` | `Bearer <token>` (grown-ups, or everyone if the admin allows) | Talk to Steward: the server asks the family's model (Settings → AI & API), which acts as you through the same handlers and permissions. Feature `steward` (and `steward-kids`) |
| `GET`/`PUT /api/admin/steward {enabled, url, model, key?, everyone}`; `POST /api/admin/steward/test` | admin session | The model Steward talks through: any OpenAI-compatible address (Ollama, a Hermes server, a cloud provider). The key is never sent back |
| `GET /api/checkplans`; `POST /api/checkplans {memberId, placeId, days, from, until}`; `DELETE /api/checkplans/{id}` | parents set them; a child sees their own | Check-in times: a reminder at `from`, parents told (`checkin_missed`) if the child hasn't arrived or checked in by `until` |
| `POST /api/requests {to, kind: "coming"}` | `Bearer <token>` | "On my way": you show as driving to them (`rideTo`) until you're within 150 m or 90 minutes pass |
| `GET /api/v1/people` | a key with `people` | Everyone's id, name, role, access, stars and voice (no locations) |
| `GET /api/v1/openapi.json` | none | The OpenAPI description of what keys can use |
| `GET`/`POST /api/admin/keys` `{name, scopes, days?}`, `DELETE /api/admin/keys/{id}` | admin session | Steward keys: list (with the recent-use log), make one (the key is returned once), revoke |

**API keys** (`Authorization: Bearer fl_…`) work only while the family's `agents` module (Steward) is on, and only for their scopes.

Device tokens are stored only as SHA-256 hashes, and member passwords as PBKDF2-SHA256 hashes. The console cookie is HMAC-signed, `HttpOnly` and `SameSite=Strict`. Wrong passwords (console and app) are slowed down to stop guessing, and a member's username locks for 15 minutes after 5 wrong tries.

The common-password list in `server/commonpasswords.txt` comes from [SecLists](https://github.com/danielmiessler/SecLists) (MIT License, © 2018 Daniel Miessler).
