# Integrations

Fuse works fully offline with the games and art already on your device. Every online service below is
optional, is used only after you set it up (GitHub update checks are the one exception and can be
turned off), and, apart from downloads and uploads you start, only reads data for display. This page lists, for each integration, what it is for,
what you provide, what the code does, and exactly what leaves the device and when.

All provider clients live in `core/integrations/src/commonMain/kotlin/io/github/matiyaaa/fuse/integrations/`.
They share one HTTP client (`FuseHttp`) whose User-Agent is `Fuse/<version> (+https://github.com/MAtiyaaa/fuse)`,
pace requests per host (`RateLimiter`), never throw network errors into the interface (`ApiResult`),
and redact credentials from every error message (`redact()`, see
[ARCHITECTURE.md](ARCHITECTURE.md#security-model)). No HTTP logging is installed.

## Contents

- [Summary: what leaves the device](#summary-what-leaves-the-device)
- [RetroAchievements](#retroachievements)
- [SteamGridDB](#steamgriddb)
- [IGDB](#igdb)
- [TheGamesDB](#thegamesdb)
- [ScreenScraper](#screenscraper)
- [Libretro thumbnails](#libretro-thumbnails)
- [System art (Art Book Next)](#system-art-art-book-next)
- [How scraping picks a match](#how-scraping-picks-a-match)
- [Fuse RomM](#fuse-romm)
- [RomM via Cartridge](#romm-via-cartridge)
- [Cartridge bridge protocol](#cartridge-bridge-protocol)
- [GitHub Releases](#github-releases)
- [The Store: Obtainium Emulation Pack](#the-store-obtainium-emulation-pack)
- [Steam and Windows launchers](#steam-and-windows-launchers)
- [RPCS3 compatibility list](#rpcs3-compatibility-list)
- [Jellyfin](#jellyfin)
- [Streaming (GameStream and Moonlight)](#streaming-gamestream-and-moonlight)
- [Emulators' own files](#emulators-own-files)

## Summary: what leaves the device

| Service | You provide | Sent by Fuse | When |
|---|---|---|---|
| RetroAchievements | Username and web API key | Your username (or ULID), your key, RetroAchievements game and console ids | When the achievement data Fuse has cached is older than its time to live and a screen needs it |
| SteamGridDB | API key | Your key, game titles you look up, SteamGridDB game ids, Steam app ids | When you fill or pick artwork |
| IGDB | Twitch Client ID and Client Secret | Your Client ID and Secret (to Twitch, for a token), the token, game titles, IGDB platform ids | When you fill metadata or artwork |
| TheGamesDB | API key | Your key, game titles, TheGamesDB platform and game ids | When you fill metadata or artwork |
| ScreenScraper | Developer credentials (not shipped yet) and optionally your account | Developer and account credentials, and either a file's CRC32/MD5 with its size, file name (no folders) and system id, or a title | When you fill metadata or artwork |
| Libretro thumbnails | Nothing | The system folder name and candidate game names in image URLs | When you fill artwork |
| System art (Art Book Next) | Nothing | The pack's system name (for example `snes`) in file URLs on raw.githubusercontent.com | When a platform's system art or the style picker is shown and the files are not cached yet |
| GitHub Releases | Nothing | A request for the latest release of Fuse or Cartridge, your IP address and the User-Agent with Fuse's version | Update checks (automatic check can be turned off) and "Install Cartridge"; downloads only after you confirm |
| Store (Android): Obtainium Emulation Pack, app sources | Optionally a GitHub token | Requests for the pack's newest release on github.com, each app's releases (GitHub's API, or the download page the pack names), and the APK you install; your IP address and the User-Agent (or the one the pack sets for a download page) | The catalogue when the Store opens and is older than 6 hours, or on "Check now"; an app's releases when its page or card is shown, for installed apps a little after start (at most twice a day, can be turned off); downloads only when you press Install or Update |
| RPCS3 compatibility list (rpcs3.net) | Nothing | A PS3 game's title id (for example `BLUS30443`), your IP address and the User-Agent | Only when you choose How It Runs in RPCS3 in a PS3 game's options; the answer is kept a week |
| Fuse RomM (off until you turn it on) | Your RomM server's addresses, then an approval in RomM, a pairing code or token made in RomM, or (for a server without client tokens) a user name and password | To your own server only: the token (or the sign-in) on each request, this device's name and a random device id when pairing, requests for the server's API description, systems, collections, games and firmware, the files you download, and the files of games you upload | While Fuse RomM is on: a quick check of each address when it connects, the library brought up to date every 30 minutes (you choose 5 minutes to a day) and when you ask; downloads and uploads only when you start them |
| Cartridge | Nothing | Nothing leaves the device through Fuse: Fuse reads Cartridge's local status and opens it with deep links. "Upload to RomM" hands Cartridge a game's file paths; Cartridge uploads the files to your own RomM server only after you confirm there. On Android Cartridge can read your play sessions (below) and adds their time to its own play sessions on your RomM server | On resume and when Cartridge reports a change; uploads only when you start one and confirm it in Cartridge; play sessions when Cartridge starts or comes back to the front |
| Jellyfin (off until you turn it on) | Your server's addresses, your Jellyfin user name and password | To your own server only: the user name and password at sign-in (kept only in Fuse's secret store, and shared sealed with your own Fuse Sync host when Share Sign-ins is on), then the access token, this device's name and a random device id, what you browse and search, a device profile of what this device can play, and where you are in what you play (start, progress every ten seconds, stop), plus favourites and watched marks you change. For a download, the request for the original file, its subtitle files and pictures. A UDP broadcast on the local network asks which Jellyfin servers are there when you look for one | While Jellyfin is on: a check that the server answers every 30 seconds, pages as you open them, Home's Jellyfin widgets every 5 minutes while one is on Home, and downloads only when you start one; where you stopped in a downloaded copy is sent once the server can be reached |
| Streaming (off until you add a computer) | The address of your computer running Sunshine, Apollo or GeForce Experience, optionally its network card address | To that computer only: a `serverinfo` request on its open GameStream port (47989). To wake it, a Wake-on-LAN packet with its network card address, broadcast on the local network and sent to its address. Moonlight, not Fuse, pairs and streams | When the Streaming tab is open and when you choose an app; the wake packet only when the computer doesn't answer and waking is on |

The "When" column describes the store that drives these clients (`DefaultFuseStore`). The "Sent by Fuse" column is what the clients in `core:integrations` can send.

Never sent anywhere by Fuse: ROM files, your folder paths, your play time, your collections, device
identifiers, analytics or crash reports, backups, diagnostics reports, or licence keys (a Vita
package's zRIF goes only to Vita3K on your device). Fuse contains no telemetry. The one way a game's files
leave the device is an upload you start, which Fuse RomM (or Cartridge, after you confirm there) sends
to your own RomM server. Your play time
leaves Fuse only to Cartridge on the same device, which may send it on to your own RomM server.

**Filling art by itself.** With "Find art by itself" on (Settings, Art and details; on by
default), Fuse runs "Fill missing art" on its own a few seconds after a scan and after a key is
saved, for games still missing art or details, with the same sources and requests as a fill you
start. Games it recently looked for without finding more are skipped. Each source is searched by the
game's title and, while nothing sure turns up, by up to two other names (a name a provider gave, the
cleaned file name, the title without its subtitle, "&" or "and", Roman numerals or digits, without a
leading "The", without accents). A source that runs out of requests, rejects its key or keeps
failing rests for a while (30 minutes for a quota or a rejected key, 5 after three failures in a
row); the others carry on, and Fuse remembers nothing as missing while one rests.

## RetroAchievements

**What for.** Show your RetroAchievements profile, recent unlocks, per-game progress, awards and
mastery on Home and on each game's page. Fuse only displays achievements; the emulator's own
RetroAchievements login unlocks them.

**You provide.** Your username and the web API key from retroachievements.org (Settings, Keys). They
are checked with `API_GetUserProfile` before being saved, and stored in the platform's secure store
under `ra.username` and `ra.apikey` (`SecretKeys`).

**How it works** (`retroachievements/RetroAchievementsClient.kt`).
`https://retroachievements.org/API/API_<Name>.php` with the key in the `y` parameter and the user in
`u`. Endpoints used: `GetUserProfile`, `GetUserSummary`, `GetUserRecentAchievements`,
`GetGameInfoAndUserProgress`, `GetUserCompletionProgress` (pages of up to 500), `GetUserAwards`,
`GetUserRecentlyPlayedGames` (up to 50), `GetGameHashes`, `GetGameList`, `GetConsoleIDs`. Badge and
icon paths are resolved against `https://media.retroachievements.org`. RetroAchievements does not
publish its rate limits, so the client sends one request at a time, at least 400 ms apart, and
treats a `200` answer that carries an `Error` field as a failure.

**Caching.** The client itself never caches; the data layer keeps responses in the `kv_cache` table
for these times (`RaCachePolicy`) and shows stale data offline:

| Data | Time to live |
|---|---|
| Profile | 10 minutes |
| Summary | 10 minutes |
| Recent achievements | 2 minutes |
| Recently played games | 5 minutes |
| Game progress | 15 minutes |
| Completion progress | 15 minutes |
| Awards | 30 minutes |
| Game list (per console) | 7 days |
| Game hashes | 7 days |
| Console ids | 30 days |

**Matching games.** Fuse links a local game to a RetroAchievements game by hashing the ROM on the
device the way rcheevos does (`RaHasher`: whole-file MD5, header-stripped NES, SNES, PC Engine,
Atari 7800 and Lynx, byte-order normalised N64, file name for arcade) and comparing it with the hash
lists it downloads (the store does the comparison, and caches each console's list for 7 days). None of the endpoints Fuse calls
accepts a hash, so hashes never leave the device. Disc images, Nintendo DS/DSi and
3DS are not hashed yet; Fuse reports them as unsupported instead of guessing.

**Leaves the device.** Your username or ULID and key in HTTPS query parameters, and RetroAchievements
game and console ids.

Docs: [RetroAchievements API](https://api-docs.retroachievements.org/).

## SteamGridDB

**What for.** Grids (wide capsules, portrait covers and square box art), heroes, logos and icons,
for games, platforms and apps. Square grids (512 x 512 and 1024 x 1024) are Fuse's box art, the
tile art of the Box art layout and Home.

**You provide.** A free API key from your
[SteamGridDB preferences](https://www.steamgriddb.com/profile/preferences/api), stored as
`sgdb.apikey`.

**How it works** (`steamgriddb/SteamGridDbClient.kt`). `https://www.steamgriddb.com/api/v2` with
`Authorization: Bearer <key>`: `/search/autocomplete/{term}`, `/games/id/{id}`,
`/games/steam/{appId}`, and `/grids|heroes|logos|icons/game/{gameId}` with style, dimension, MIME and
animation filters. NSFW, humour and epilepsy-warning assets are excluded by default. Requests are
paced 100 ms apart, at most four at a time.

**Key checks.** Saving a SteamGridDB, IGDB or TheGamesDB key (or choosing Test keys in Settings, Art and
details) makes one real request with it (`verifyKey` / `verifyCredentials`, returning a
`KeyCheck`), so Settings can say Working, Key rejected or Offline. Keys and client ids are stripped of
whitespace and invisible characters when saved.

**Leaves the device.** Your key (in a header), the titles you look up, SteamGridDB game ids and Steam
app ids. Images are downloaded from SteamGridDB's CDN.

Terms: [SteamGridDB terms](https://www.steamgriddb.com/terms).

## IGDB

**What for.** Descriptions, release year, genres, developers and publishers, plus covers (box art),
artworks (heroes) and screenshots.

**You provide.** Your own Twitch application's Client ID and Client Secret, created in the
[Twitch developer console](https://dev.twitch.tv/console). A client secret must never ship inside a
public app, so Fuse asks each user for theirs. Stored as `igdb.clientId` and `igdb.clientSecret`.

**How it works** (`igdb/IgdbClient.kt`). Fuse gets an app access token from
`https://id.twitch.tv/oauth2/token` (client-credentials grant), keeps it in memory until shortly before
it expires, and retries once with a fresh token when IGDB answers 401. Searches go to
`https://api.igdb.com/v4/games`, filtered to the game's IGDB platform when known and excluding
versions. Requests are paced to IGDB's limit of 4 per second. Images come from
`https://images.igdb.com`.

**Leaves the device.** Your Client ID and Secret (to Twitch only), the token and Client ID (to IGDB),
game titles and IGDB platform ids.

Docs and terms: [IGDB API](https://api-docs.igdb.com/),
[Twitch Developer Services Agreement](https://legal.twitch.com/legal/developer-agreement/).

## TheGamesDB

**What for.** Metadata, plus box art, fanart, banners, screenshots, title screens and clear logos.

**You provide.** Your own API key (public keys have a small monthly allowance per IP), stored as
`tgdb.apikey`.

**How it works** (`thegamesdb/TheGamesDbClient.kt`). `https://api.thegamesdb.net` with the key in the
`apikey` parameter: `/v1.1/Games/ByGameName` (with platform filter) and `/v1/Games/Images`. Every
response reports the remaining allowance, which Fuse surfaces. Requests are paced 250 ms apart, at
most two at a time.

**Leaves the device.** Your key, game titles, TheGamesDB platform ids and game ids.

Docs: [TheGamesDB API](https://api.thegamesdb.net/).

## ScreenScraper

**What for.** Identifying ROMs by checksum, and metadata and media from ScreenScraper's database.

**You provide.** Nothing yet in 0.0.1. ScreenScraper requires developer credentials (`devid`,
`devpassword`, `softname`), which it grants to free software on request. Fuse does not ship any; later
official builds may inject them from CI secrets. Until then the provider is shown as "needs
ScreenScraper developer credentials" and **makes no request at all**. An optional ScreenScraper
account (`ss.user`, `ss.password`) raises your quota and thread count once developer credentials exist.

**How it works** (`screenscraper/ScreenScraperClient.kt`). `https://api.screenscraper.fr/api2/`:
`jeuInfos.php` identifies a file by CRC32 or MD5 plus size, file name (any folder part removed) and
system id when Fuse has a hash for it; otherwise `jeuRecherche.php` searches by title. The request
pacing adapts to the per-user thread and per-minute limits each answer reports, and ScreenScraper's
documented status codes (closed API, quota used up, version blocked) become clear messages. Media URLs
are stored without credentials and re-signed just before a download.

**Leaves the device.** Developer and account credentials in HTTPS query parameters, and the file's
checksum, size, file name and system id, or the title.

Docs: [ScreenScraper API](https://www.screenscraper.fr/webapi2.php).

## Libretro thumbnails

**What for.** Box art, title screens, snaps and logos for systems that libretro covers.

**You provide.** Nothing; no key is needed.

**How it works** (`libretro/LibretroThumbnails.kt`). Fuse builds
`https://thumbnails.libretro.com/{System}/{Named_Boxarts|Named_Snaps|Named_Titles|Named_Logos}/{Name}.png`
for a few name candidates (from the title and the file name, with characters that are unsafe in file
names replaced by `_`) and probes which exist. Requests are paced 100 ms apart, at most two at a time.
The thumbnail repository has no licence file, so images are fetched at runtime and cached on the
device only; nothing from it is bundled with Fuse.

**Leaves the device.** The libretro system folder name and candidate game names, as part of the URLs.

## System art (Art Book Next)

**What for.** A logo, a tall artwork panel and a few facts (full name, maker, release year, hardware
type and colours) for each platform, from [Art Book Next](https://github.com/anthonycaccese/art-book-next-es-de),
Anthony Caccese's theme for ES-DE. The panels come in five styles: Classic, Outline, Noir, Circuit
and Screenshots.

**You provide.** Nothing; no key or account is needed.

**How it works** (`systemart/`). `SystemArtNames` maps each Fuse platform to the pack's system name
(for example `3ds` to `n3ds`, `ngc` to `gc`); PlayStation 5 and Switch 2 have no art in the pack.
A system the pack has nothing for takes its artwork panel from one of its own games instead (a
screenshot, else the background, already found for that game by your art sources; nothing extra is
fetched), drawn cut to the pack's slanted panel at the same place and size, and shows its name in
place of a logo.
`SystemArtPackClient` reads files from one pinned commit of the repository under
`https://raw.githubusercontent.com/anthonycaccese/art-book-next-es-de/<commit>/_inc/systems/`:
`logos/<name>.svg`, `artwork/<name>.png` (Classic) or `artwork-outline/`, `artwork-noir/`,
`artwork-circuit/` and `artwork-screenshots/`, and `_metadata-global/<name>.xml`. It checks with HEAD
requests that the logo exists (without it the fetch fails) and that the chosen style has a panel for
the system, falling back to Classic when it does not, then reads the metadata file and uses only its
top-level variables. Requests are paced 50 ms apart, at most four at a time. The pack is licensed CC
BY-NC-SA 2.0, so its files are fetched at runtime and cached on the device only; nothing from it is
bundled with Fuse, and its credits (`SystemArtPack.ATTRIBUTION`) are shown with the art.

**Leaves the device.** Only requests to raw.githubusercontent.com for the files above, which carry
the pack's system name and nothing personal (no titles, paths or accounts). GitHub sees your IP address
and the User-Agent with Fuse's version.

## How scraping picks a match

`scrape/ScrapeCoordinator.kt` asks providers in your order (Settings, Art and details; Local media
and RomM come first by default). Only providers that have the credentials they need are asked.
Metadata providers are tried first, then artwork-only ones. Fuse's own `TitleMatcher` scores each result and
explains the score; a result is accepted without asking only when it clears the chosen strictness
(Exact, Normal or Aggressive), otherwise you pick from the candidates. The coordinator never writes
anything: the data layer decides what to store, and `FillPlanner` guarantees that **art you set
yourself is never replaced** except by the explicit "reset custom art" action.

## Fuse RomM

Fuse's native RomM integration (`core:romm`, with the screens in `ui/shell/.../romm`). It is off
until you turn it on in setup ("Do you use RomM?") or Settings, Addons, Fuse RomM, and talks only
to the RomM server you name. Fuse RomM and [Cartridge](#romm-via-cartridge) are both supported;
turning one on turns the other off in Fuse and keeps both set up.

- **What the server can do:** `GET /api/heartbeat` (no sign-in) for its version, then RomM's own
  OpenAPI document. Device pairing, pairing codes, chunked uploads, scans and the rom identifier
  list are each used only when the server lists them, so an older RomM simply offers less.
- **Pairing:** with device pairing (`/api/auth/device/init` and `/token`) RomM shows a code to
  approve; Fuse polls at the interval RomM gives and backs off when told to slow down. A pairing
  code from RomM is exchanged at `/api/client-tokens/exchange`. Either gives a client token scoped
  to reading, or to reading, uploading and scanning; Fuse asks for the narrowest that does what you
  chose, and you can revoke it in RomM. A server without client tokens takes a user name and
  password, sent as HTTP Basic on each request.
- **Credentials:** the token (or the sign-in) is kept only in Fuse's secret store (`romm.credential`),
  never in the database, settings, logs, screenshots, diagnostics, crash output or exported
  settings, and is removed when you sign out. Errors are redacted like every other client's. With
  Fuse Sync's Share Sign-ins on, it also goes to your own Fuse Sync host, sealed with this device's
  secret, and from there to your household's other devices, sealed for each
  ([docs/sync.md](docs/sync.md#romm-and-jellyfin-for-the-household)); it never goes anywhere else.
- **Routes:** a local address, an outside one, or Automatic. Automatic checks home first with a
  1.5 second limit and never waits on an address that doesn't answer; a server that went away is
  checked again every minute.
- **The offline copy:** `/api/platforms`, `/api/collections`, `/api/collections/smart` and
  `/api/roms` a page at a time (only games changed since the last time, after the first), kept in
  Fuse's database. Games removed on the server are found through `/api/roms/identifiers` where it
  exists. The library is read without file lists (a PS4 or PS5 game can list tens of thousands);
  a game's files come from `/api/roms/{id}` the first time it is opened or downloaded, and are kept.
  A read that stops part way carries on from the page it reached, and a page RomM is slow to send is
  asked for again in smaller ones. The Fuse RomM tab, its grids and Search read the copy, so they work offline.
- **Matching:** a RomM game is joined to a library game by the link Fuse keeps, an MD5, a console's
  title id, the exact file name, or the same name with the same region, revision, version and disc
  when it is the only one on each side. A name that only looks alike is never enough, and a library
  game is matched at most once.
- **Downloads** (`/api/roms/{id}/content/{file}`) go through Downloads into the system folder Fuse
  already uses for that system, resumed with HTTP ranges and checked before being put in place.
- **Art and details for games not on this device** come from the sources set up in Settings, Art
  and details, in their order, exactly as for library games: the same requests (a game's name, its
  system, the providers' ids once known) go to the same services. What they find, and what you
  change, is kept in Fuse's database and asked for once.
- **Uploads** use `/api/roms/upload/start`, a `PUT` per piece and `/complete`, resumed where they
  stopped, and optionally a scan of that system (`/api/tasks/scan`) afterwards.
- **BIOS** (`/api/firmware`): planned against what Fuse's system health expects. A file already in
  place is never replaced, whatever the server has under the same name; firmware an emulator
  installs itself is downloaded to a `Firmware/<emulator>` folder with how to install it.

## RomM via Cartridge

With Cartridge instead of Fuse RomM, Fuse never talks to a RomM server itself. [Cartridge](https://github.com/abdu2304/cartridge)
(a RomM companion app by abdu2304, MIT licensed; Fuse installs it from the
[MAtiyaaa/cartridge](https://github.com/MAtiyaaa/cartridge) fork until Fuse's bridge is merged
upstream) signs in to RomM, downloads games into local folders and owns the RomM credentials. Fuse then:

- scans the folders Cartridge downloads into like any other library (RomM Structure A and B are both
  understood, see [ARCHITECTURE.md](ARCHITECTURE.md#scanning-pipeline));
- reads Cartridge's status (below) to show downloads, runs a quick rescan when Cartridge reports a
  library change (only folders whose modification time changed are read again), and remembers each
  downloaded file's RomM rom id (`ExternalLinks.rommRomId`) so "Open in Cartridge" can jump to that
  game;
- with bridge protocol 2 (a Cartridge newer than 0.9.10), reads every game Cartridge downloaded with
  RomM's details and pictures and applies them to the matching Fuse game (see
  [What Fuse does with it](#what-fuse-does-with-it));
- with bridge protocol 3, hands Cartridge a game to upload to RomM ("Upload to RomM" in a game's
  options, "Upload a game" on the Cartridge tab) and shows the uploads Cartridge reports (see
  [Uploads](#uploads-protocol-3));
- keeps a "RomM (via Cartridge)" slot in the scraper order. Before protocol 2, RomM artwork and
  metadata only reach Fuse through what Cartridge saves next to the games, which the local media
  scanner reads.

## Cartridge bridge protocol

The bridge is defined in `cartridge/CartridgeProtocol.kt` and is the same on Android and Linux. It is
local only, read-only for Fuse, and never carries a server address, token or password.

| Constant | Value |
|---|---|
| Cartridge package (Android) | `io.github.abdu2304.cartridge` |
| Minimum Cartridge version for the bridge | `0.9.10` |
| Protocol version | `3` for uploads, `2` for the queue and games tables (`1` still works); links use `v=1`, the upload link `v=3` |
| Link scheme | `cartridge://` |
| Release source for "Install Cartridge" | [MAtiyaaa/cartridge](https://github.com/MAtiyaaa/cartridge) releases, assets `Cartridge-android.apk` and `Cartridge-x86_64.AppImage` (upstream: [abdu2304/cartridge](https://github.com/abdu2304/cartridge)) |

Cartridge 0.9.10 adds the provider, status file and links below (protocol 1). Cartridge 0.9.11 "The
Bridge Expansion" (in review as [MAtiyaaa/cartridge#30](https://github.com/MAtiyaaa/cartridge/pull/30))
speaks protocol 3: protocol 2 adds the download queue game by game and the downloaded games with
RomM's details and pictures, and protocol 3 adds uploads. Cartridge's own
`docs/FUSE_BRIDGE.md` is the full contract. With an older Cartridge, Fuse only knows it is
installed and its version (`installedWithoutBridge`).

### Deep links

Fuse opens Cartridge with `cartridge://<route>?...&from=fuse&v=1`. Path segments and query values are
percent-encoded (a space is `%20`); when parsing, `+` is also read as a space.

| Route | Link |
|---|---|
| Home | `cartridge://home?from=fuse&v=1` |
| Library | `cartridge://library?from=fuse&v=1` |
| Downloads | `cartridge://downloads?from=fuse&v=1` |
| Consoles | `cartridge://consoles?from=fuse&v=1` |
| Settings | `cartridge://settings?from=fuse&v=1` |
| Sync | `cartridge://sync?from=fuse&v=1` |
| One platform | `cartridge://platform/{slug}?from=fuse&v=1` |
| One game | `cartridge://game/{romId}?from=fuse&v=1` |
| Search | `cartridge://search?q={query}&platform={slug}&from=fuse&v=1` (`platform` is optional) |
| Platform BIOS | `cartridge://bios/{slug}?from=fuse&v=1` |

Platform slugs are RomM slugs (`psx`, `snes`, `switch`, ...). Example:
`cartridge://search?q=Chrono%20Trigger&platform=snes&from=fuse&v=1`.

### Android: status provider

| Item | Value |
|---|---|
| Authority | `io.github.abdu2304.cartridge.status` |
| Permission | `io.github.abdu2304.cartridge.permission.READ_STATUS` |
| Status URI | `content://io.github.abdu2304.cartridge.status/status` (one row) |
| Recent URI | `content://io.github.abdu2304.cartridge.status/recent` (finished downloads, newest first) |
| Queue URI (protocol 2) | `content://io.github.abdu2304.cartridge.status/queue` (every download in the Downloads page's order) |
| Games URI (protocol 2) | `content://io.github.abdu2304.cartridge.status/games` (downloaded games with RomM's details) |
| Uploads URI (protocol 3) | `content://io.github.abdu2304.cartridge.status/uploads` (games handed over to upload, newest first) |

`/status` columns:

| Column | Type | Meaning |
|---|---|---|
| `protocol` | int | Bridge protocol version: 1 (Cartridge 0.9.10) or 2 |
| `version` | text | Cartridge's version name |
| `connected` | int 0/1 or null | Whether Cartridge can reach its RomM server; null when unknown |
| `active_downloads` | int | Downloads in progress |
| `queued_downloads` | int | Downloads waiting |
| `progress` | real 0..1 or null | Progress across the active queue |
| `current_title` | text or null | Game downloading now |
| `current_platform` | text or null | Its platform slug |
| `library_changed_at` | int | Epoch millis when Cartridge last changed anything on disk |
| `updated_at` | int | Epoch millis of this status |

`/recent` columns:

| Column | Type | Meaning |
|---|---|---|
| `rom_id` | int | RomM rom id |
| `title` | text | Game title |
| `platform_slug` | text | RomM platform slug |
| `path` | text | Local path the game was saved to |
| `finished_at` | int | Epoch millis |

`/queue` columns (protocol 2): `rom_id`, `title`, `platform_slug`, `state` (`downloading`, `queued`,
`paused`, `failed` or `done`), `received`, `total` (null while unknown) and `position`. Fuse leaves
out `done` rows (they are in `/recent`) and states it doesn't know.

`/uploads` columns (protocol 3): `id`, `title`, `platform_slug`, `state` (`waiting`, `uploading`,
`scanning`, `done`, `failed` or `cancelled`), `sent`, `total` (null while unknown), `files`, `rom_id`
(null until the game is on RomM), `error` and `updated_at`. Fuse leaves out states it doesn't know.

`/games` columns (protocol 2): `rom_id`, `path`, `title`, `platform_slug`, `summary`, `year`,
`genres` and `series` (JSON array text), `developer`, `publisher`, `rating` (0..100), `players`,
`cover`, `logo`, `screenshot` (content URIs the provider serves read-only, or null) and
`updated_at`.

Fuse maps the rows with `statusFromRow`, `downloadFromRow`, `queueItemFromRow` and `gameFromRow`:
rows without a rom id or title are skipped, counts are clamped at zero, progress to 0..1 and ratings
to 0..100. The Android app declares the permission, reads the provider on resume and listens for
changes with a `ContentObserver` on `/status` and one on `/games`, which only fires when the games or
their pictures change; `/games` is read only then.

### Linux: status file

Cartridge writes `$XDG_STATE_HOME/cartridge/status.json`, or `$HOME/.local/state/cartridge/status.json`
when `XDG_STATE_HOME` is unset or not an absolute path (`statusFilePath`). The JSON uses the same
fields in camelCase:

```json
{
  "protocol": 1,
  "version": "0.9.10",
  "connected": true,
  "activeDownloads": 1,
  "queuedDownloads": 0,
  "progress": 0.5,
  "currentTitle": "Chrono Trigger",
  "currentPlatform": "snes",
  "libraryChangedAt": 1790000000000,
  "updatedAt": 1790000000000,
  "recent": [
    {
      "romId": 42,
      "title": "Chrono Trigger",
      "platformSlug": "snes",
      "path": "/home/me/roms/snes/Chrono Trigger.sfc",
      "finishedAt": 1790000000000
    }
  ]
}
```

With protocol 2 the file also has `queue` and `games` arrays, and with protocol 3 an `uploads` array,
with the same fields in camelCase (pictures are absolute paths of files in Cartridge's own folder). A file without `protocol`, or one
that is not valid JSON, is ignored. Numbers and booleans are accepted quoted or unquoted. The Linux
app watches the file's folder and re-reads it after changes (files up to 16 MB).

### What Fuse does with it

On resume (and when the bridge reports a change), Fuse reads the status, shows download progress in
the Cartridge section and the Cartridge Downloads widget, and, when "Pick up new downloads on return"
is on (Settings, Accounts, Cartridge; on by default), rescans the folders Cartridge saved to
(`CartridgeSettings.autoRefreshOnReturn`) with a quick scan. With protocol 2 the Downloads panel,
the top line and Phone Link show each game in the queue.

When "Details and art from RomM" is on (Settings, Accounts, Cartridge; on by default,
`CartridgeSettings.rommDetails`), Fuse reads the downloaded games whenever they or the library change
and, for each one (`CartridgeDetails`):

- finds the Fuse game by its path (`CartridgeMatch`): the exact path, the same place written another
  way (`content://` document URIs, `/sdcard`, `/storage/emulated/0`), the one game inside a folder
  Cartridge reports (multi-disc games), the same folder and file name, or a file name no other game
  has. Games Fuse hasn't indexed yet are tried again after the next scan;
- checks that RomM's details are this game's (`RommGuard`): RomM's platform slug must resolve to the
  game's system (or one that shares its games, such as Game Boy and Game Boy Color; a slug Fuse
  doesn't know is no evidence), and RomM's title must read like the game's own file or cleaned name
  (title similarity 0.8 or more). A path alone is not trusted, since Cartridge names a download by its
  RomM rom id and that id can come to mean another rom. When the check fails nothing is written, and
  details an earlier version wrote for it are undone;
- before RomM's details first reach a game, keeps what it had (name, details and the art they
  replace, `kv_cache` namespace `cartridge.romm.before`), so they can be put back;
- links it to its RomM entry (`rommRomId`);
- applies RomM's description, year, developer, publisher, genres, series (as the franchise, which
  feeds automatic series collections), players and rating with source `ROMM`: they fill empty fields
  and replace scraped ones, while details the user edited are only ever filled;
- reads the cover, logo and screenshot from Cartridge (a `content://` URI on Android, a file on
  Linux), checks they really are pictures, and keeps a copy in Fuse's own data folder (`romm/<game>/`);
  a picture that can't be read is never recorded. They are added as `ROMM` media, replacing scraped
  art but never the user's picks or art from the game's folder, and a screenshot only when the game
  has none.

Once (and again from **Check RomM matches again** in Settings, Accounts, Cartridge), Fuse checks
every game holding RomM's details against its own names and puts back the ones that belong to
another game. Any game can also be reset by hand: **Reset Name and Details** in its options forgets
the name, details and art sources gave it, and keeps the user's own name and picks.

A game is written again only when Cartridge says its row or pictures changed, so a later "Fill
everything" isn't undone at every sync.

### Uploads (protocol 3)

"Upload to RomM" (a game's options, for games that didn't come from RomM) and "Upload a game" (the
Cartridge tab: a system, then a game) are offered when Cartridge reports protocol 3; an older
Cartridge gets an explanation and an offer to update. Fuse gathers the game's files the way Storage
counts them (`UploadFiles`): the launch file or `.m3u` first, then the other discs and tracks; for a
folder game everything inside it, each file with its folder relative to the game (`dlc`, `update`,
...), leaving out system clutter. It then hands Cartridge an upload request
(`CartridgeProtocol.uploadRequest`):

```json
{
  "v": 1, "from": "fuse", "title": "Pepsiman", "platform": "psx",
  "files": [
    { "name": "Pepsiman.m3u", "folder": "", "size": 58, "path": "/storage/emulated/0/ROMs/psx/Pepsiman/Pepsiman.m3u" },
    { "name": "Extra.bin", "folder": "dlc", "size": 1024, "path": "/storage/emulated/0/ROMs/psx/Pepsiman/dlc/Extra.bin" }
  ]
}
```

- **Android:** `Intent.ACTION_VIEW` with `cartridge://upload?from=fuse&v=3`, sent to Cartridge's
  package, with the request in the string extra `io.github.matiyaaa.fuse.extra.UPLOAD`
  (`CartridgeProtocol.EXTRA_UPLOAD`). Cartridge reads the files by path with its own All files
  access.
- **Linux:** Fuse writes the request to `~/.cache/fuse/cartridge-upload/upload-<time>.json` (your
  user only; requests older than a day are cleared) and opens
  `cartridge://upload?request=<that path>&from=fuse&v=3`.

Cartridge shows the game, its files and the console on RomM, and uploads only after you press
Upload there: the first file into the console's folder, then, once RomM has added the game, the
other files into its folder (RomM 5.3 or newer). Fuse shows the uploads it reports on the Cartridge
tab, as a ring in the top line while one runs, and in a message when one is on RomM or failed.

### Play sessions for Cartridge (Android)

Fuse shares its play sessions with Cartridge, so Cartridge's last played, Continue playing, This week
and play time count games started from Fuse (`services/PlayShareProvider.kt`). The contract is
Cartridge's `docs/FUSE_BRIDGE.md` ("Play sessions from Fuse").

- Read-only provider `content://io.github.matiyaaa.fuse.play/sessions?since=<epoch ms>`: one row
  per session (`session_id`, `rom_id`, `path`, `launch_path`, `title`, `title_original`, `platform`,
  `started_at`, `ended_at`, `source`), newest first, at most 2000. Nothing else of the library is
  shared.
- Behind `io.github.matiyaaa.fuse.permission.READ_PLAY` (normal). Fuse also grants Cartridge's
  package read access at every start, since Android only grants a normal permission when the app
  defining it was installed first.
- The database is opened read-only per query; the provider never writes.

## GitHub Releases

**What for.** Checking for new Fuse versions, and installing Cartridge.

**How it works** (`github/GitHubReleases.kt`). An unauthenticated request to
`https://api.github.com/repos/{owner}/{repo}/releases/latest` (60 requests per hour per IP), for
`MAtiyaaa/fuse` and `MAtiyaaa/cartridge`. Drafts and pre-releases are never offered. The asset for the
device is chosen by name: on Android an `.apk` (one named `universal`, then `android`, then any); on
Linux the file ending in `x86_64.AppImage`. GitHub's `sha256:<hex>` digest for the asset is kept with
it. When GitHub's hourly limit is reached, Fuse says so instead of failing silently.

**Approval and verification.** Nothing is downloaded until you confirm. The installer then downloads
the asset into Fuse's cache, verifies its SHA-256 digest when GitHub published one, and hands it to
the system installer on Android or places the new AppImage next to the running one and marks it
executable on Linux (`ReleaseInstaller`). A digest mismatch deletes the download and reports the
failure; on Linux an existing file is never overwritten. Every release also
carries a `SHA256SUMS.txt` you can check by hand (see [README.md](README.md#install)).

**Automatic checks.** "Check automatically" (Settings, About) is on by default and checks once a
day. Turn it off and Fuse only contacts GitHub when you press "Check for updates" or
"Install Cartridge".

**Leaves the device.** Your IP address, the User-Agent with Fuse's version, and which repository was
asked.

## The Store: Obtainium Emulation Pack

**What for.** Android only: the Store in Addons installs, updates and removes emulators and gaming
apps listed in the [Obtainium Emulation Pack](https://github.com/RJNY/Obtainium-Emulation-Pack)
(public domain). Fuse reads the pack as data; nothing about the apps is written into Fuse.

**The catalogue** (`obtainium/PackFetcher.kt`, `obtainium/PackDocument.kt`). The user picks an
edition on first use: Standard (`obtainium-emulation-pack-latest.json`) or Dual-Screen
(`obtainium-emulation-pack-dual-screen-latest.json`), which really differ (forks for devices with
a second screen, and companions only they use). Fuse reads the newest release's tag from where
`github.com/RJNY/Obtainium-Emulation-Pack/releases/latest` redirects (no API quota) and downloads
that release's file for the edition; when that fails it takes the same file from the project's
main branch. A download only replaces the saved catalogue when it parses as a real pack (at least
five usable apps), and the last good one is kept, so the Store opens offline with a note of its age.
It is refreshed when the Store opens and the copy is older than 6 hours, or on "Check now".

**What an app is.** Each entry's `additionalSettings` (a JSON string, as Obtainium exports it) is
read for the settings that choose a release and a file. An entry marked `trackOnly` is only
followed (its newest release is shown, with no Install); a source Fuse doesn't read is shown with
its download page. An entry's `id` is its package name except where the pack uses a generated
number (RetroArch, the track-only entries): then the package is learnt from the first install and
pinned.

**Releases** (`obtainium/PackResolver.kt`, `ApkPicker.kt`, `VersionText.kt`, `HtmlLinks.kt`),
following Obtainium's own rules so each app resolves as the pack intends:

- *GitHub sources*: `api.github.com/repos/{owner}/{repo}/releases?per_page=50` (and `/releases/latest`
  when the pack asks to verify it), sorted by the pack's method (date, natural name order, or names
  with a date fallback), skipping drafts, pre-releases unless allowed, and titles or notes the pack's
  filters reject, falling back to older releases only where the pack allows it. The version comes
  from the tag (or the title, or the date) through the pack's `versionExtractionRegEx` and
  `matchGroupToUse` template (`$1$3.$2$4$6$5`), the last match winning. 60 requests an hour without a
  token; a GitHub token (Settings, Store; kept in the secret store) raises that. When the limit is
  reached Fuse says so and waits for GitHub's reset time.
- *Download pages* (Dolphin, DuckStation, Eden, Play!, PPSSPP, RetroArch, ScummVM): the page is
  fetched with the pack's request headers, its links found (`<a href>`, and every address on the
  page when the pack asks or the page is JSON), resolved against the page's final address, filtered
  by the pack's patterns, sorted in natural order, through each intermediate page in turn; the last
  link is the newest. The version is read from the link or the whole page by the pack's pattern.
- *The file*: the pack's file filter (or its inverse), then the device's processor types
  (`arm64-v8a` also matches `aarch64` and `arm64`), then a universal build, then the pack's preferred
  index. Only a plain APK is installed: a release that is a zip or a split bundle is installed by
  hand from its page.

Releases are cached for 6 hours (kept 30 days, shown as "last known" while a fresh look fails).

**Installing** (`store/impl/AppStoreImpl.kt`, `ApkDownloader.kt`, Android `AndroidPackageBridge`).
Nothing downloads until you press Install or Update. Then:

1. Every address on the way is HTTPS. Redirects are followed one at a time and a hop to anything
   else stops the download; addresses only ever come from the resolver, never from text on screen.
2. The download goes to `cache/store` (never a user folder), at most 1 GB and only when there is room,
   and must be complete. GitHub's `sha256:` digest is checked when published.
3. Android reads the file (`getPackageArchiveInfo`). It must be the package the pack lists (or the
   package pinned for that app), and not older than what is installed; otherwise it is deleted and
   nothing installs.
4. It goes to `PackageInstaller` with `USER_ACTION_REQUIRED`: Android shows its own confirmation
   every time, one at a time. "Update all" queues the updates and Android asks about each one.
   Without "Install unknown apps" for Fuse, Android's settings open first and the install carries on
   once it is allowed. A signature conflict is reported as such, with Uninstall first.
5. The download is deleted once Android has it. Fuse remembers the upstream version and file it
   installed, which makes later update checks exact.

**Installed and updates.** What is installed is read from the PackageManager, refreshed by the
system's package broadcasts and on resume. An update is claimed only when it is real: after a Fuse
install, any newer release; otherwise when Android's version name and the release's version are the
same kind of numbers and the release is newer. Anything else shows "can't compare", never a made-up
update.

**Icons.** An installed app shows its own icon. Before that, Fuse looks once for the icon the app
publishes itself (`obtainium/AppIconFinder.kt`, cached for 30 days, or 7 when there is none): a
GitHub project's fastlane or F-Droid metadata icon, else the icon its Play Store build uses, and a
website's home-screen icon (`apple-touch-icon`, else its largest `icon`). Only HTTPS, checked with a
HEAD request to be a PNG, JPEG or WebP. Nothing is guessed: an app that publishes none keeps its
monogram, and an owner's avatar is never used.

**What Fuse does with each app.** Every app in both editions is either an emulator in Fuse's launch
catalog (an app page says whether Fuse starts games in it or, where the app doesn't allow that, opens
it) or an app Apps lists: Moonlight and Artemis under Streaming, frontends and helpers under Tools
(`KnownApps`). `StoreCoverageTest` reads both pack fixtures, so a new pack app fails until Fuse knows
it.

**Uninstalling** goes through `PackageInstaller.uninstall` (with `REQUEST_DELETE_PACKAGES`); Android
asks the user to confirm. Fuse never removes files to "uninstall".

**Leaves the device.** Your IP address and User-Agent to github.com, api.github.com (with your
token when you added one), raw.githubusercontent.com and the download pages the pack names, and
which app's releases or file was asked for.

## Steam and Windows launchers

Fuse does not run Windows games itself. It starts them through the launcher you use, with a per-game
launch only where the launcher documents one, and otherwise opens the launcher and says so ("Open in
X", `LaunchPlan.OpenAppOnly`). Sources and confidence for each entry are in
`core/launch/.../launch/android/AndroidPcDefs.kt` and [RESEARCH.md](RESEARCH.md#pc-and-steam-launchers-on-android).

### Android

| Launcher | Per-game launch | What Fuse needs |
|---|---|---|
| GameNative | Yes: action `app.gamenative.LAUNCH_GAME`, int `app_id`, `game_source` | Id files from GameNative's "Export for frontend": `.steam`, `.epic`, `.gog`, `.amazon`, `.pcgame`, each containing only the numeric id |
| GameHub Lite | Yes: action `gamehub.lite.LAUNCH_GAME`, `steamAppId`, `autoStartGame` | A `.steam` file with the Steam app id |
| GameHub Lite (local id) | Yes: `localGameId`, `autoStartGame` | A `.steam` or `.pcgame` file containing GameHub's local game id (copy it inside GameHub) |
| GameHub (original) | No | Fuse opens GameHub |
| Winlator Cmod, Cmod Glibc, Cmod PRoot | Yes: `XServerDisplayActivity` with `shortcut_path` | A `.desktop` from Winlator's "Export for Frontend"; Fuse passes its real file path |
| WinNative | Yes: `shortcut_path` | A `.desktop` exported by WinNative |
| Bannerlator | Yes: `com.winlator.star.XServerDisplayActivity` with `shortcut_path` (vendor guide) | A `.desktop` export |
| Winlator (mainline) | No: its game activity is not exported | Fuse opens Winlator |
| Winlator Frost | No: launch intent unknown | Fuse opens it |

Put these files in a `steam` or `win` platform folder, or add a folder of them as a Shortcuts source.
Because several launchers are published under the same spoofed package names, Fuse always checks that
the expected activity exists and is exported before using an entry.

### Linux

| Launcher | How |
|---|---|
| Steam | `steam -applaunch <appid>` (native or the `com.valvesoftware.Steam` Flatpak): a game from a Steam library by the app id in its manifest, or a `.steam` file's id |
| Steam (link) | `xdg-open steam://rungameid/<appid>` |
| `.desktop` shortcuts (Steam, Heroic, Lutris, emulator shortcuts) | `gio launch <file>` when GLib's `gio` is installed, otherwise the file's `Exec=` line with field codes removed |

### Steam's own games on a computer (Linux, Windows, macOS)

Settings, Library, Find Steam games (or setup) adds each Steam library that holds your games as a library
folder of its own. Its games come from Steam's own manifests (`steamapps/appmanifest_<appid>.acf`):
only fully installed games, without Steam's tools (Proton, the runtimes, redistributables), each at
its folder under `steamapps/common` and named as Steam names it. They start through Steam by app id
(`steam -applaunch <appid>` on Linux and Windows, `open steam://rungameid/<appid>` on macOS). A game
is missing only when Steam no longer lists it as installed; games installed later join by
themselves, and games left unticked when adding are hidden. Fuse reads Steam's files and never
changes them. Libraries added as shortcut files by 0.2.7 and earlier move onto Steam's libraries
on the first start, keeping each game's play time, favourite, edits and art.

**Leaves the device.** Nothing; these are local app launches.

## Jellyfin

An addon, off by default (Settings, Addons, Jellyfin). Fuse speaks to your own Jellyfin server
over its REST API with its own client (`core:jellyfin`), never to any other service. See
[docs/jellyfin.md](docs/jellyfin.md) for what it shows and how it plays.

- **Sign-in:** `Users/AuthenticateByName` with your user name and password. Fuse keeps the access
  token, the user id, the server's id and name, and the user name and password in its secret store
  only, never logged or backed up; the sign-in is kept so your household's other devices can sign
  in as you through Fuse Sync (sealed for each, to your own host only), and signing out forgets it.
  With Fuse Sync profiles each person can have their own account on the same server. Requests
  carry `Authorization: MediaBrowser Client="Fuse", Device,
  DeviceId, Version, Token`; pictures are fetched without the token.
- **Reaching the server:** `System/Info/Public` checks each address (home first in Automatic, with
  a short timeout), every 30 seconds while Jellyfin is on. Discovery sends "who is JellyfinServer?"
  on UDP 7359 for two seconds when you ask Fuse to look.
- **Playing:** `Items/{id}/PlaybackInfo` with a device profile built from what the player measured
  and the quality limit for the route in use; then the stream itself; then `Sessions/Playing`,
  `Sessions/Playing/Progress` and `Sessions/Playing/Stopped` so resume points and watched marks
  follow you.
- **Kept on the device:** answers for pages, per user, so they open offline; pictures, cached by
  the picture itself. Signing out clears the kept answers.
- **Downloads:** only when the server allows your user to download (`Policy.EnableContentDownloading`).
  Fuse fetches the original file (`Items/{id}/Download`), the external subtitle files the server
  keeps beside it and the pictures, through Downloads, into Films and Shows folders under the
  offline folder you choose. Playing a downloaded copy needs nothing from the server; where you
  stopped is kept and sent with `Sessions/Playing/Stopped` once the server can be reached.

## Streaming (GameStream and Moonlight)

Fuse starts streams from a computer at home; [Moonlight](https://moonlight-stream.org) does the
streaming, and [Sunshine](https://github.com/LizardByte/Sunshine), Apollo or GeForce Experience
serves it. Fuse only reads the host's open GameStream port:

- `GET http://<host>:47989/serverinfo` (no pairing) for its name, its id (how Moonlight knows it),
  its network card address when it shares it, and whether it is busy.
- **Wake-on-LAN:** when the computer doesn't answer and waking is on, the magic packet goes to the
  local broadcast address and the computer's address on the ports Moonlight uses (9, 7, 47998,
  47999, 48000, 48002, 48010), again every few seconds while Fuse waits (75 seconds by default).
- **Starting:** on Android, Moonlight's own shortcut entry (`com.limelight.ShortcutTrampoline`)
  with the computer's id, name and app; on a computer, `moonlight stream <host> <app>` (the
  Flatpak too). Pairing happens in Moonlight, once, as usual.

Fuse never pairs with the host, never writes to it, and never reads or changes Sunshine's
configuration files.

## RPCS3 compatibility list

**What it is.** RPCS3's public list of how each PS3 release runs, rated Playable, Ingame, Intro,
Loadable or Nothing (`rpcs3.net/compatibility?api=v1&g=<title id>`).

**When Fuse asks.** Only when you choose How It Runs in RPCS3 in a PS3 game's options. Fuse sends the
game's title id, taken from its file or folder name or from a game folder's `PARAM.SFO`, and nothing
else. For an id the list doesn't know, the service answers with a text search over other games, so
Fuse trusts only an entry whose key is exactly the asked id. Answers are kept a week in Fuse's cache.

**Code.** `core/integrations/.../rpcs3/Rpcs3Compatibility.kt` (parsing), `LibraryStore` (the request
and the cache).

## Emulators' own files

Fuse changes an emulator's files only when you ask, never automatically, and only what it can undo
exactly.

- **PCSX2 patches** (Linux, Windows, macOS). Fuse finds PCSX2's data folder the way PCSX2 does
  (portable mode next to the program; otherwise Documents\PCSX2, `$XDG_CONFIG_HOME/PCSX2` or
  `~/.config/PCSX2`, `~/.var/app/net.pcsx2.PCSX2/config/PCSX2` for the Flatpak, and
  `~/Library/Application Support/PCSX2`), following folders moved in `inis/PCSX2.ini`'s `[Folders]`.
  Patches are read from `patches/SERIAL_CRC*.pnach` and PCSX2's bundled `resources/patches.zip` (not
  readable inside a running AppImage). Turning one on adds `Enable = <name>` under `[Patches]` in
  `gamesettings/SERIAL_CRC.ini`; Fuse records it and only ever removes lines it recorded. Patches you
  set in PCSX2, ones on for every game (widescreen, no interlacing) and ones you turned off there are
  shown and never changed. The file as it was is kept in Fuse's data (`emulator-backups/`) the first
  time Fuse changes it, and every write is atomic. Rules from PCSX2's `pcsx2/Patch.cpp`,
  `pcsx2/VMManager.cpp` and `pcsx2/Pcsx2Config.cpp`.
- **Installed content (games, updates, DLC, licences).** Fuse-native, with nothing of RomM or
  Cartridge involved. Fuse reads the unencrypted parts of each file and plans the install:
  - `.pkg` headers as RPCS3 (`rpcs3/Crypto/unpkg.h`), Vita3K (`vita3k/packages/src/pkg.cpp`) and
    pkg2zip read them: content id, title id, content type, DRM type and patch flag, and a Vita
    package's plain PARAM.SFO.
  - A `.vpk`/`.zip`'s `sce_sys/param.sfo`, through its ZIP directory.
  - `.rap` (named by content id), `.edat` (NPD header), `.rif`/`work.bin`, and zRIF keys (the pkg2zip
    dictionary).
  - A 3DS `.cia`'s TMD.

  It reads what the emulator already holds: RPCS3's `dev_hdd0` (its config folder, or where
  `vfs.yml` moves it: `game/<title id>/PARAM.SFO`, `home/*/exdata`), Vita3K's pref path
  (`ux0/app`, `ux0/patch`, `ux0/addcont`, `ux0/license`), and Azahar's SD card (`title/<high>/<low>`
  TMDs).

  The order is: licences, the game, updates oldest first, then DLC. Each step goes through the
  emulator's own documented installer:
  - RPCS3: `--headless --installpkg <file>`, once per file. It copies `.rap`/`.edat` into exdata
    under the file's own name, so a `.rap` under another name is staged in Fuse's folder as
    `<contentId>.rap`.
  - Vita3K: `--pkg <file> --zrif <key>`, or a `.vpk`/`.zip` given as its content path. Vita3K starts
    the game after installing an archive, so Fuse stops it once its log says the install is done.
  - Azahar: `-i <file.cia>`, whose exit code is InstallStatus + 2.

  RPCS3 always exits 0, so no exit code is trusted. A step only counts when the title shows up in
  the emulator's storage afterwards. PS3 DLC goes into the game's own folder, so Fuse records the
  DLC it saw go in.

  Fuse never writes into an emulator's storage, settings or saves. zRIFs (found beside the game,
  made from a `.rif` the way Vita3K's `find_pkg_zrif` does, or pasted) are passed only in Vita3K's
  arguments and kept in memory for that run of Fuse.

  aPS3e, RPCSX, ARMSX3 and Vita3K on Android only install from their own menus (no exported or
  documented install entry), so there the page is a guide: the files in order, and the emulator to
  open.
- **Disc identity.** The serial and PCSX2 CRC are read from the image itself (SYSTEM.CNF and the boot
  program, as in `pcsx2/CDVD/CDVD.cpp` and `pcsx2/Elfheader.cpp`); nothing is written.
