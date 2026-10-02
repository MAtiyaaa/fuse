# Phone Link

Phone Link lets a phone on the same Wi-Fi see and manage the library on a device running Fuse:
what is playing, what Cartridge is downloading, browsing the library, fixing a game's name,
details and art, starting bulk art fills, and looking at and downloading the device's screenshots
and recordings. It never deletes anything, never shows or changes keys or passwords, and never
changes Phone Link's own settings.

## How it works

- Fuse runs a small web server (Ktor, CIO engine) while Phone Link is on. It serves a phone web app
  and a JSON API. The default port is **47300**; when it is taken (for example by another app),
  Fuse uses the next free port. Cartridge's own phone remote uses 47280 and 47281, so the two never
  collide.
- **Local network only.** Requests from addresses outside private and link-local ranges
  (10/8, 172.16/12, 192.168/16, 169.254/16, loopback, fc00::/7, fe80::/10) are refused, and so is
  any request whose `Host` is a name instead of an address (this stops DNS rebinding: a web page
  on the internet can't reach Phone Link through a name that points at the device).
- **Only its own pages change things.** A request other than GET with an `Origin` from another
  page is refused, request bodies are limited to 64 KB, and every answer carries `nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and a Content Security Policy that only
  allows the app's own files.
- **Sign-in.** The username and password are set on the device in Settings, Accounts, Phone Link. The password
  is stored as a salted PBKDF2-HMAC-SHA256 hash in Fuse's secret store. Five wrong tries lock
  sign-in for a minute. A signed-in phone gets a random session token in an HTTP-only,
  SameSite=Strict cookie (`fuse_session`), kept until "Sign out all phones" or a password change.
- Settings, Accounts, Phone Link, Pair a phone shows a QR code with the address
  (`http://<device address>:<port>/`), the sign-in and how many phones are signed in. A device on
  several networks lets you pick which address the code holds. The code only holds the address;
  signing in still needs the username and password.
- Phone Link runs while Fuse runs (on Android, while the Fuse process is alive, so a phone stays
  connected while a game is playing). Turning the switch off stops the server.
- The web app is plain HTML, CSS and JavaScript (in `ui/link/src/web/`), bundled into the app at
  build time. No build step, no external requests: icons are inline SVG.

## API

All responses are JSON unless stated. Errors are `{"error": "message people can read"}`: with a
4xx status for a bad request (400: an unknown slot, or a match or image that wasn't offered to this
phone; 404: the game is gone), and with 200 when the request was fine but the answer is "not
possible right now" (no source configured, the source didn't answer). Every route except `GET /`,
the static files, `GET /api/session`, `POST /api/login` and a download link (see Captures) needs
a session (401 `{"error": "Sign in"}` otherwise). `GET /api/session` always answers 200, signed
in or not.

### Session
| Route | Body | Result |
|---|---|---|
| `GET /api/session` | | `{"signedIn": bool, "device": "AYN Thor", "version": "0.1.4", "captures": bool}` (`captures`: the device has screenshots and recordings to offer; Android only for now) |
| `POST /api/login` | `{"username", "password"}` | 200 `{"ok": true}` and the cookie; 401 wrong; 429 `{"error", "retryAfterSeconds"}` |
| `POST /api/logout` | | `{"ok": true}` |

### Now
`GET /api/now`:
```json
{
  "playing": GameSummary | null,
  "playingSince": 1727700000000 | null,
  "cartridge": {
    "enabled": true, "installed": true, "connected": true,
    "current": {"title": "Pepsiman", "platform": "psx", "progress": 0.42} | null,
    "queue": [{"romId": 12, "title": "...", "platform": "psx", "state": "downloading|queued|paused|failed", "received": 123, "total": 456}],
    "queued": 3,
    "recent": [{"romId": 12, "title": "...", "platform": "psx", "finishedAt": 1727700000000, "gameId": 5 | null}]
  },
  "fill": FillProgress | null
}
```
`FillProgress`: `{"done": 12, "total": 340, "current": "Pepsiman" | null, "added": 40, "details": 9,
"finished": false, "cancelled": false, "needsYou": [{"id": 5, "title": "Pepsiman"}]}`. `needsYou` lists
games with several close matches (Identify game picks one); the web app shows the count and the
device lists them in Settings.

### Library
- `GET /api/systems` → `[{"id": "psx", "name": "PlayStation", "shortName": "PS1", "gameCount": 12,
  "accent": "#5C6BC0", "logo": url | null, "art": url | null}]`
- `GET /api/games?query=&system=&sort=title|recent|added&offset=0&limit=60` →
  `{"total": 340, "items": [GameSummary]}`
- `GameSummary`: `{"id": 5, "title": "Pepsiman", "system": "psx", "systemName": "PlayStation",
  "year": 1999 | null, "cover": url | null, "icon": url | null, "hero": url | null,
  "logo": url | null, "favorite": false, "lastPlayedAt": ms | null}`
- `GET /api/games/{id}` → `GameSummary` plus `{"description", "developer", "publisher",
  "genres": [], "players", "rating": 0..100 | null, "series", "fileName", "sizeBytes",
  "playMinutes", "searchAs": {"current", "custom": bool, "default"},
  "media": {"square"|"icon"|"cover"|"banner"|"background"|"logo": {"url", "source"} | null},
  "screenshots": [url]}`

### Fix a game
| Route | Body | Result |
|---|---|---|
| `PUT /api/games/{id}/search-as` | `{"name": "Pepsiman"}` (empty goes back to the title) | `{"ok": true, "searchAs": {...}}` |
| `GET /api/games/{id}/identify` | | `{"query", "candidates": [Candidate]}` or `{"error"}` |
| `POST /api/games/{id}/identify` | `{"providerId", "providerGameId"}` of a listed `Candidate` | `{"ok": true}` (names the game, fills details and art) |
| `GET /api/games/{id}/art/{kind}` | kind: square (box art), icon, cover, banner, background, logo, screenshot | `{"options": [ArtOption]}`, `{"needsMatch": [Candidate]}` or `{"error"}` |
| `POST /api/games/{id}/art/{kind}` | `{"url"}` of a listed `ArtOption` | `{"ok": true}` |
| `POST /api/games/{id}/fill` | | `{"ok": true}` (fills what is missing for this game) |

`Candidate`: `{"provider": "IGDB", "providerId": "IGDB", "providerGameId", "title", "platformName", "year",
"confidence", "preview": url | null}` (`provider` is the name to show, `providerId` the one to send back).
`ArtOption`: `{"url", "thumb", "provider", "width", "height", "style", "author"}`.

Phones can only pick what Fuse listed to them: the server remembers the matches and images it sent
(per game) and refuses anything else, so a phone can't make Fuse fetch an arbitrary address.

### Bulk
| Route | Body | Result |
|---|---|---|
| `POST /api/fill` | `{"mode": "missing" | "everything", "system": id | null}` | `{"ok": true}` |
| `POST /api/fill/cancel` | | `{"ok": true}` |
| `POST /api/system-art` | | `{"ok": true}` |

### Captures
Screenshots and recordings Fuse saved on the device (Android: Pictures/Fuse and Movies/Fuse), to
look at and download. Read only: nothing here changes or deletes a capture.

| Route | Body | Result |
|---|---|---|
| `GET /api/captures` | | `{"available": bool, "items": [Capture]}`, newest first |
| `GET /api/captures/{id}/thumb` | | A small JPEG (`Cache-Control: private`) |
| `GET /api/captures/{id}` | | The file, inline, with `Accept-Ranges: bytes`: one `Range` gives 206 and `Content-Range`, one past the end 416, several ranges the whole file |
| `POST /api/captures/download` | `{"ids": [id]}` (at most 500) | `{"url": "/api/download/<token>", "name", "size", "count"}`; 404 when one is gone |
| `GET /api/download/{token}` | | One capture as an attachment (with ranges, so a download can resume), or several as a zip written as the files are read ("Fuse captures 2026-10-01.zip"; no compression, Zip64 past 4 GB) |

`Capture` is `{"id", "name", "video", "mime", "size", "takenAt", "width", "height", "durationMs",
"thumb", "url"}`.

- **Ids** are opaque tokens the server makes up when it lists captures; a MediaStore id, a path or
  anything else the phone sends is never accepted.
- **Download links** are made only for a signed-in phone, by a POST from Phone Link's own page. They
  are random (128 bits), work for 10 minutes and only for the captures named, and need no cookie:
  some phone browsers hand downloads to the system's download manager, which doesn't send one.
- At most six files are sent at once, so a game keeps the storage.

### Images and live updates
- Image URLs in responses are either public `https://` URLs (provider CDNs) or
  `/api/img/<token>` for art stored on the device. Tokens are opaque and only map to files Fuse
  itself returned; nothing else on the device can be read.
- `GET /api/events` is a Server-Sent Events stream: `event: now` (the `/api/now` payload when it
  changes), `event: fill` (FillProgress), `event: library` (`{}`: refresh lists), `event:
  captures` (`{}`: a capture was added or removed), and a comment ping every 20 s.

## Not available on purpose
No route deletes games, captures or any other file, reads or writes API keys, credentials, Phone
Link settings or any other setting beyond the fixes and bulk actions above.
