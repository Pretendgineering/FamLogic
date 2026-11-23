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
| `POST /api/signin` `{username, password}` | password | Sign this phone in. The previous phone is signed out |
| `POST /api/me/password` `{currentPassword, newPassword, username?}` | `Bearer <token>` | Set or change your password |
| `POST /api/me/circles/<id>` `{sharing}` | `Bearer <token>` | Share or stop sharing your location with one group |
| `POST /api/me/circles/join` `{code}` | `Bearer <token>` | Join another group with an invite code |
| `POST /api/places` `{circleId, name, lat, lon, radius}`, `POST`/`PATCH`/`DELETE /api/places/<id>` | `Bearer <token>` | Add, change or delete a fence in one of your groups |
| `GET /api/geocode?q=<address>` | `Bearer <token>` | Address search for fences (results: `[{name, lat, lon}]`) |
| `GET /api/members/<id>/trail` | `Bearer <token>` | Someone's trail, if they keep one and you can see their location |
| `GET /api/events/wait?since=<cursor>&wait=50` | `Bearer <token>` | Waits until there's a new alert (or `wait` seconds), then returns `{events, cursor}`. The app keeps one open for instant alerts |
| `POST /api/sos` `{loc?}`, `POST /api/sos/end` | `Bearer <token>` | Raise or end an SOS |
| `POST /api/checkin` `{note, loc?}` | `Bearer <token>` | Check in |
| `POST /api/location?since=<cursor>` `{lat, lon, acc, speed, battery, charging, time}` | `Bearer <token>` | Report a fix; the reply carries new alerts `{events, cursor}` |
| `GET /api/members?since=<cursor>` | `Bearer <token>` | Everyone's latest position, map settings, your feature settings (including `kid`: what you may use, `home` and `pinSet`), and new alerts |
| `PATCH` or `POST /api/me` `{name?, paused?, shareLowBattery?, home?}` | `Bearer <token>` | Rename yourself / pause / feature switches / arrange your home screen (adults; parents arrange children's) |
| `DELETE /api/me` | `Bearer <token>` | Leave |
| `/api/admin/*` | console login cookie | Admin pages (invites, members, roles, modules, backups). `GET /api/admin/session` says who's logged in |
| `/api/chat`, `/api/chat/{id}/messages`, … | `Bearer <token>` or a parent's console session | Chat: see [ARCHITECTURE.md](ARCHITECTURE.md#chat-built-in-0160) |
| `POST /api/chat/{id}/moderate` `{action}` | a parent | `approve`, `pause`, `resume`, `block`, `unblock` or `delete` a chat with a child in it (the main admin: `/api/admin/chat/{id}/moderate`, any chat but the family chat) |
| `DELETE /api/chat/{id}/messages/{mid}` (or `POST …/delete`) | `Bearer <token>` | Delete your own message, or (parents) any message in a chat with a child in it |
| `POST /api/pin/check` `{pin}` | `Bearer <token>` | Checks the family's parent PIN (204 or 403; locks after repeated wrong tries) |
| `PUT /api/admin/pin` `{pin}` (or `PUT /api/family/pin` from an admin's phone) | admin | Sets the parent PIN (4–8 digits; `""` removes it). `GET /api/admin/pin` says whether one is set |
| `GET`/`PUT /api/members/{id}/kid` `{preset?, sections?, features?, home?}` | a parent (`/api/admin/members/{id}/kid` for admins) | A child's switches: preset `younger` or `older`, then sections (`map`, `chat`) and features (`sos`, `checkin`, `requests`, `chat_start`, `chat_photos`, `trips`, `pause`) on or off, and their home-screen tiles. A new preset starts afresh from its defaults |

Device tokens are stored only as SHA-256 hashes, and member passwords as PBKDF2-SHA256 hashes. The console cookie is HMAC-signed, `HttpOnly` and `SameSite=Strict`. Wrong passwords (console and app) are slowed down to stop guessing, and a member's username locks for 15 minutes after 5 wrong tries.

The common-password list in `server/commonpasswords.txt` comes from [SecLists](https://github.com/danielmiessler/SecLists) (MIT License, © 2018 Daniel Miessler).
