# Integrations

Fuse works fully offline with the games and art already on your device. Every online service below is
optional, is used only after you set it up (GitHub update checks are the one exception and can be
turned off), and only reads data for display. This page lists, for each integration, what it is for,
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
- [RomM via Cartridge](#romm-via-cartridge)
- [Cartridge bridge protocol](#cartridge-bridge-protocol)
- [GitHub Releases](#github-releases)
- [Steam and Windows launchers](#steam-and-windows-launchers)

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
| Cartridge | Nothing | Nothing leaves the device through Fuse: Fuse reads Cartridge's local status and opens it with deep links. "Upload to RomM" hands Cartridge a game's file paths; Cartridge uploads the files to your own RomM server only after you confirm there | On resume and when Cartridge reports a change; uploads only when you start one and confirm it in Cartridge |

The "When" column describes the store that drives these clients (`DefaultFuseStore`). The "Sent by Fuse" column is what the clients in `core:integrations` can send.

Never sent anywhere by Fuse: ROM files, your folder paths, your play time, your collections, device
identifiers, analytics or crash reports. Fuse contains no telemetry. The one way a game's files
leave the device is an upload you start and confirm, which Cartridge sends to your own RomM server.

**Filling art by itself.** With "Find art by itself" on (Settings, Media and Scraping; on by
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

**Key checks.** Saving a SteamGridDB, IGDB or TheGamesDB key (or choosing Test keys in Settings, Media
and Scraping) makes one real request with it (`verifyKey` / `verifyCredentials`, returning a
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

`scrape/ScrapeCoordinator.kt` asks providers in your order (Settings, Media and Scraping; Local media
and RomM come first by default). Only providers that have the credentials they need are asked.
Metadata providers are tried first, then artwork-only ones. Fuse's own `TitleMatcher` scores each result and
explains the score; a result is accepted without asking only when it clears the chosen strictness
(Exact, Normal or Aggressive), otherwise you pick from the candidates. The coordinator never writes
anything: the data layer decides what to store, and `FillPlanner` guarantees that **art you set
yourself is never replaced** except by the explicit "reset custom art" action.

## RomM via Cartridge

Fuse has no RomM client and never talks to a RomM server. [Cartridge](https://github.com/MAtiyaaa/cartridge)
(a RomM companion app by abdu2304, MIT licensed) signs in to RomM, downloads games into local folders
and owns the RomM credentials. Fuse then:

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
is on (Settings, Cartridge; on by default), rescans the folders Cartridge saved to
(`CartridgeSettings.autoRefreshOnReturn`) with a quick scan. With protocol 2 the Downloads panel,
the top line and Phone Link show each game in the queue.

When "Details and art from RomM" is on (Settings, Cartridge; on by default,
`CartridgeSettings.rommDetails`), Fuse reads the downloaded games whenever they or the library change
and, for each one (`CartridgeDetails`):

- finds the Fuse game by its path (`CartridgeMatch`): the exact path, the same place written another
  way (`content://` document URIs, `/sdcard`, `/storage/emulated/0`), the one game inside a folder
  Cartridge reports (multi-disc games), the same folder and file name, or a file name no other game
  has. Games Fuse hasn't indexed yet are tried again after the next scan;
- links it to its RomM entry (`rommRomId`);
- applies RomM's description, year, developer, publisher, genres, series (as the franchise, which
  feeds automatic series collections), players and rating with source `ROMM`: they fill empty fields
  and replace scraped ones, while details the user edited are only ever filled;
- adds the cover and logo as `ROMM` media, replacing scraped art but never the user's picks or art
  from the game's folder, and a screenshot only when the game has none.

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

**Automatic checks.** "Check automatically" (Settings, Updates) is on by default and checks once a
day. Turn it off and Fuse only contacts GitHub when you press "Check for updates" or
"Install Cartridge".

**Leaves the device.** Your IP address, the User-Agent with Fuse's version, and which repository was
asked.

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
| Steam | `steam -applaunch <appid>` from a `.steam` file (native or the `com.valvesoftware.Steam` Flatpak) |
| Steam (link) | `xdg-open steam://rungameid/<appid>` |
| `.desktop` shortcuts (Steam, Heroic, Lutris, emulator shortcuts) | `gio launch <file>` when GLib's `gio` is installed, otherwise the file's `Exec=` line with field codes removed |

**Leaves the device.** Nothing; these are local app launches.
