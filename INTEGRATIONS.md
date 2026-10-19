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
- [The Store on a computer](#the-store-on-a-computer)
- [Fuse Ports](#fuse-ports)
- [Steam and Windows launchers](#steam-and-windows-launchers)
- [RPCS3 compatibility list](#rpcs3-compatibility-list)
- [Jellyfin](#jellyfin)
- [Streaming (GameStream and Moonlight)](#streaming-gamestream-and-moonlight)
- [Mod sites (GameBanana and Nexus Mods)](#mod-sites-gamebanana-and-nexus-mods)
- [Emulators' own files](#emulators-own-files)
- [ROM patches](#rom-patches)

## Summary: what leaves the device

| Service | You provide | Sent by Fuse | When |
|---|---|---|---|
| RetroAchievements | Username and web API key; your password once to sign the emulators in | Your username (or ULID), your key, RetroAchievements game and console ids; the password in one sign-in request | When the achievement data Fuse has cached is older than its time to live and a screen needs it; the sign-in only when you choose Sign Emulators In |
| SteamGridDB | API key | Your key, game titles you look up, SteamGridDB game ids, Steam app ids | When you fill or pick artwork |
| IGDB | Twitch Client ID and Client Secret | Your Client ID and Secret (to Twitch, for a token), the token, game titles, IGDB platform ids | When you fill metadata or artwork |
| TheGamesDB | API key | Your key, game titles, TheGamesDB platform and game ids | When you fill metadata or artwork |
| ScreenScraper | Developer credentials (not shipped yet) and optionally your account | Developer and account credentials, and either a file's CRC32/MD5 with its size, file name (no folders) and system id, or a title | When you fill metadata or artwork |
| Libretro thumbnails | Nothing | The system folder name and candidate game names in image URLs | When you fill artwork |
| System art (Art Book Next) | Nothing | The pack's system name (for example `snes`) in file URLs on raw.githubusercontent.com | When a platform's system art or the style picker is shown and the files are not cached yet |
| GitHub Releases | Nothing | A request for the latest release of Fuse or Cartridge, your IP address and the User-Agent with Fuse's version | Update checks (automatic check can be turned off) and "Install Cartridge"; downloads only after you confirm |
| Store (Android): Obtainium Emulation Pack, app sources | Optionally a GitHub token | Requests for the pack's newest release on github.com, each app's releases (GitHub's API, or the download page the pack names), and the APK you install; your IP address and the User-Agent (or the one the pack sets for a download page) | The catalogue when the Store opens and is older than 6 hours, or on "Check now"; an app's releases when its page or card is shown, for installed apps a little after start (at most twice a day, can be turned off); downloads only when you press Install or Update |
| Store (computer): each program's releases | Optionally a GitHub token (GitHub only) | Requests for a program's releases on GitHub, its own Forgejo or Gitea server, its download listing or Flathub, and the file you install; your IP address and the User-Agent | A program's releases when its page or card is shown, for installed programs a little after start; downloads only when you press Install, Update or Update All |
| Fuse Ports (computer, off until you turn it on) | Your own copy of each game (its folder, chosen by you) | Requests for a newer signed list of ports (`index.json` and its `.sig` on the `ports` release of Fuse's GitHub repository), and each port's build from its project's own release when you install it; your IP address and the User-Agent. Your game's files and folders never leave the device: they are only linked into Fuse's copy | The list when Ports opens (at most every 6 hours) or on Look Again, only while Fuse carries the key it is signed with; a build only when you press Install or Update |
| GameBanana | Nothing | A game's title (or the words you search with), the GameBanana game you chose and the mods you open, your IP address and the User-Agent; then the mod's file from GameBanana's own address | Only when you open GameBanana in a game's cheats, textures or mods, a mod there, or add one of its files; answers are kept a while |
| Nexus Mods (computer, off until you add your key) | Your own personal API key from your Nexus Mods account | To api.nexusmods.com only: your key in the `apikey` header with "Fuse" and its version, the Nexus Mods game you chose for a game and the mods and files you open; for an account that isn't Premium, the one-time key and expiry of the Mod Manager Download link you paste. Then the file from the address Nexus Mods gives (https only). The key stays on this device: it is never shared with your household or Fuse Sync | Whose key it is when Settings, Accounts, Nexus Mods is shown (once a run per key); the games list when you choose a game (kept a week), a game's lists when you open Nexus Mods for it (kept an hour), a mod's files when you open it; downloads only when you add a file |
| RPCS3 compatibility list (rpcs3.net) | Nothing | A PS3 game's title id (for example `BLUS30443`), your IP address and the User-Agent | Only when you choose How It Runs in RPCS3 in a PS3 game's options; the answer is kept a week |
| Fuse RomM (off until you turn it on) | Your RomM server's addresses, then an approval in RomM, a pairing code or token made in RomM, or (for a server without client tokens) a user name and password | To your own server only: the token (or the sign-in) on each request, this device's name and a random device id when pairing, requests for the server's API description, systems, collections, games and firmware, the files you download, and the files of games you upload; with Send Changes to RomM on (off until you turn it on), your favourites, games you hide, your play sessions and the trophies you earn in emulators (a note of your own on each game, kept to you) for games from RomM; a Cloudflare Access service token, when you add one, to that outside address only, over https | While Fuse RomM is on: a quick check of each address when it connects, the library brought up to date every 30 minutes (you choose 5 minutes to a day) and when you ask; downloads and uploads only when you start them |
| Fuse Sync, Fuse Library (with Fuse Sync on; Share This Device's Games can turn it off) | Nothing beyond Fuse Sync | To your own Fuse Sync host only: the list of this device's games (titles, systems, sizes, dates, file names with their sizes and SHA-1 and MD5 hashes, and the ids Fuse uses for their art), this device's local addresses and the port it answers on, what it is downloading, and requests you make for games. To another of your own devices, directly on your network or passed through your host: the files of a game that device asks for, with a ticket from your host | While Fuse Sync is on: the list a few seconds after the library changes, and once a minute while its files' hashes are being worked out; what is downloading when it changes, at most every two seconds; game files only when one of your devices asks for them |
| Fuse One, Play Together (with Fuse One on) | Nothing beyond Fuse One | To your own Fuse One host only, while a game you started with Host a Game is played: that it is open to join, how (RetroArch's netplay or PPSSPP's ad hoc play), the emulator, this device's home network addresses and the port the emulator listens on, with the game and profile Fuse One already says it is playing | Every minute while the game is played, as Fuse One already says what it plays; the other devices ask the host when it wakes them and when a game's Play Together is opened |
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

**Signing the emulators in** (`retroachievements/RaConnect.kt`, `launch/achievements/RaSignIn.kt`,
`RaEmulatorSignIn.kt`). Settings, Accounts, Sign Emulators In asks for the account's password once
and posts it, with the user name, to `https://retroachievements.org/dorequest.php` as `r=login2`, the
request RetroAchievements' own client library makes (rcheevos v12.1.0, `rc_api_init_login_request_hosted`).
The answer's login token, not the password, is what emulators sign in with: Fuse keeps it in the
secure store (`ra.token`, `ra.token@<profile>` for a profile's own account) and writes it where each
emulator's own code keeps it: RetroArch's `retroarch.cfg` (`cheevos_username`, `cheevos_token`),
PCSX2's `PCSX2.ini` `[Achievements]` (PCSX2 moves the token to its own secrets file when it starts),
DuckStation's `settings.ini` `[Cheevos]` (the token encrypted as DuckStation encrypts it, with this
computer's own id), PPSSPP's `ppsspp.ini` and `PSP/SYSTEM/ppsspp_retroachievements.dat`, and
Dolphin's `RetroAchievements.ini`. Switching profiles signs the emulators Fuse signed in in as that
profile's account. Fuse's change records hold a fingerprint of each token, never the token; a token
an emulator had before is kept in the secure store so Sign Them Out puts it back. On Android each
emulator still signs in from its own settings.

**Leaves the device.** Your username or ULID and key in HTTPS query parameters, and RetroAchievements
game and console ids. When you sign the emulators in, your password once, in the body of one HTTPS
request; it is never kept or logged.

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
place of a logo. The From your games style does the same for every system, keeping the pack's logos
and colours, and a system's Media page lets you pick the game and the screenshot.
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
- **Saves** (RomM 5, only when the server lists `/api/devices` and `/api/saves/{id}/downloaded`,
  and only with RomM, or RomM, Then Fuse One, chosen in Save Sync on this device): this device registers once
  (`POST /api/devices`, its name and platform); before a game from RomM starts, Fuse lists its saves
  (`GET /api/saves?rom_id=`), brings the newest down (`/api/saves/{id}/content`, then
  `/api/saves/{id}/downloaded`) and after it closes sends the save up (`POST /api/saves` with the
  game, slot, emulator and device). What decides is the last version this device and RomM agreed
  on, kept on the device: one side changed since, and it goes the other way; both changed, and the
  person chooses, the other kept aside. A save made of folders or of several files (a PSP or PS3
  save folder, a Switch save) goes as one zip, packed the same way every time, and is matched by
  RomM's own hash of a zip's contents (`hash_zip_contents`, RomM 5.4: the MD5 of each file's
  `name:md5`, in name order). Slots are named for what the save is on its console (`PSP Save
  Data`, `PS2 Memory Card`), with the person's name added on a device with more than one person,
  whose saves then come only from their own slot; saves sent before 0.4.4 are in `autosave` and
  are still read. A save that can't reach RomM waits, in order, in the device's own file and goes
  when RomM answers again, across restarts. Save states use `/api/states` the same way.
- **Your changes** (off until you turn on Send Changes to RomM, RomM 5.4, and only what the server
  lists): a favourite goes into the person's favourites collection (`is_favorite`; found in
  `GET /api/collections`, made with `POST /api/collections` the first time,
  `POST`/`DELETE /api/collections/{id}/roms` with only that game's id); a game hidden or shown is
  `PUT /api/roms/{id}/props` with `hidden` alone; a play session is `POST /api/play-sessions`
  (the game, start, end and length, at most 100 at a time; RomM skips one it has). Only for games
  from RomM (linked or matched). They wait in order in Fuse's database while RomM is away and go
  when it answers; a change RomM never takes (the game gone) is let go, one it can't take now (away,
  busy, the sign-in not allowed) stays. Pairing asks for `roms.user.write` and `collections.write`
  for it; a sign-in without them is told to pair again.
- **Trophies across devices, without a Fuse One host** (RomM 5.4's notes, where the server lists
  `/api/roms/{id}/notes`): the trophies earned in RPCS3, shadPS4, Vita3K and Xenia for a game from
  RomM are kept in a note of the person's own on that game, titled "Fuse trophies", tagged `fuse`
  and kept to them (`is_public` false). Its text is a line saying what it is, a line for each
  trophy, and each unlock (which trophy, its name, when, and the Fuse device and its name) as a
  line of JSON in a `fuse-trophies` block, which is all Fuse reads back. A game's page reads it
  (`GET /api/roms/{id}/notes`, the person's own note of that title only, never someone else's
  public one), at most every 10 minutes, and its unlocks join the game's history; with Send
  Changes to RomM on, what was earned here is added (`POST` the first time, then `PUT
  /api/roms/{id}/notes/{note_id}` with `content` alone), and waits with the other changes while
  RomM is away. Unlocks are only ever added, each trophy's earliest kept, so devices agree
  whichever writes first.
- **Cloudflare Access:** a service token for the outside address (Cloudflare's documented
  `CF-Access-Client-Id` and `CF-Access-Client-Secret` headers), kept only in Fuse's secret store
  (`romm.access`) and sent only to that address's host over https, on every request there
  (unsigned ones and downloads included), never to the home address or anywhere else.
- **Scan progress:** a scan Fuse starts after an upload is followed with `GET /api/tasks/{task_id}`
  every two seconds (at most half an hour): its counts show in RomM's status line, and a word says
  how many new games it found.
- **RomM on this computer** (Linux, Windows and macOS, only with Docker or Podman already installed):
  Set Up RomM on This Computer writes a Compose file in Fuse's folder (`romm-home`), after RomM's own
  `examples/docker-compose.example.yml`, with `rommapp/romm:5.4.0` and MariaDB, the chosen library
  mounted read-only, RomM's assets and config in Fuse's folder, and port 8080 for the home network
  or this computer alone. Its database passwords and sign-in key are made once and kept only in
  Fuse's secret store, handed to Compose in its environment, never written to a file. It runs
  (`compose up -d`, `stop`, `ps`) only when the person starts or stops it; no metadata service is
  set up for it.
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
the system installer on Android, or on Linux puts the new AppImage in place of the one Fuse runs
from, at the same path, so shortcuts and Steam's entry keep working (`ReleaseInstaller`). The version
before is kept beside it as a hidden `.previous` file, and versioned copies earlier updates left
there are removed. A digest mismatch deletes the download and reports the failure, and nothing is
replaced. Every release also
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

## The Store on a computer

**What for.** On Linux, Windows and macOS, the Store in Addons installs, updates and removes the
emulators and companions Fuse lists (`store/impl/DesktopCatalogue.kt`) from each project's own
releases, as the project publishes them; the programs and their builds are their developers'.

**Where releases come from** (`obtainium/PackResolver.kt`): GitHub's API
(`api.github.com/repos/{owner}/{repo}/releases?per_page=50`); a Forgejo or Gitea server's own API,
which answers in GitHub's shape (`https://{server}/api/v1/repos/{owner}/{repo}/releases?limit=50`,
Eden's server and anything added by its link), with no token sent there; a plain download listing
(RetroArch's build server, Redream's page); or Flathub's appstream for the few programs that publish
no build of their own (`flathub.org/api/v2/appstream/{id}`), installed and updated by Flatpak
itself. A program follows its stable releases, or its pre-releases when the person chooses them on
its page or on Updates.

**Added by its link** (`obtainium/ReleaseLink.kt`): a GitHub, Codeberg or other Forgejo or Gitea
address (HTTPS only; whatever follows the project in the address is dropped). Before it is added,
its newest release, the file this computer would install, its size and the room it needs are
shown; nothing is downloaded until Install.

**Before installing.** What this computer can't run is said from the program's published needs (its
processor features, its graphics), and the room it needs: its download, plus three times that for
an archive while it is unpacked, plus 64 MB, against what is free where Fuse puts programs
(`StoreRoom`). With too little room, nothing is downloaded.

**Earlier versions.** Each update moves the version it replaces, whole, to Fuse's own folder
(`previous-versions/<program>/<time it was kept>/`), out of the programs folder so it is never found
as a second copy; the newest three are kept and older ones let go. Going back to one keeps the
version in place in turn. A failed update puts the version before straight back.

**Who keeps a program up to date.** One Fuse put there, Fuse. One found on the computer is left to
whoever installed it: Flatpak (updated through Flatpak, asking for the password when it was
installed for every account), the system's packages, or the person. EmuDeck's installs are found
by EmuDeck's own documented layout ("File Management on SteamOS", emudeck.github.io: its files in
`~/.config/EmuDeck/`, its emulators as AppImages and program folders in `~/Applications`) and left
to EmuDeck's Manage Emulators. RetroDECK is one Flatpak, `net.retrodeck.retrodeck`, with its
emulators inside it (its wiki, "What is Included in RetroDECK?"); Fuse says which emulators it
already has and never reaches inside it.

**Update All** lists every update first (from and to, size, and whether Fuse or Flatpak makes it),
updates Fuse's own and Flathub's Flatpaks, holds while a game is played, and ends with one notice
of what was and wasn't updated.

**Leaves the device.** Your IP address and User-Agent to the project's release host (GitHub with your
token when you added one, the Forgejo or Gitea server, the listing or Flathub), and which project
was asked; the download when you install.

## Fuse Ports

**What for.** On a computer, Addons, Ports lists games rebuilt to run natively (source ports), each
the program its own project publishes, played with the person's own copy of the game: Diablo
through DevilutionX, RollerCoaster Tycoon 2 through OpenRCT2, Fallout 2 through Fallout 2
Community Edition, and recompilations made from the person's own ROM: Majora's Mask through Zelda
64: Recompiled. Off until turned on in Settings, Addons, Fuse Ports; nothing is fetched before.

**The list** (`ports/index.json`, `core:model` `Ports.kt`, read by `core:integrations`
`PortsIndexReader`): each port's builds (system, processor, HTTPS address, size, SHA-256, the kind of
file and the program inside it), what it needs from the game (`needs`: names, any one of which will
do, compared as the port itself compares them), how the game's files reach it (by their folder's
path in its arguments, or an `overlay`), its arguments with `{install}`, `{home}` and `{data:<id>}`,
where its saves are, and how the nightly check sees it start. Every fact about a port is cited in
its `source`, from the port's own code and documentation at the version listed. A port that fails a
check (an id, an address that isn't HTTPS, a missing digest, a path out of its folder, a placeholder
Fuse doesn't fill) is left out; a list of another format is refused whole.

**Signed.** The list inside Fuse is part of Fuse. A newer one is fetched from the `ports` release of
Fuse's repository (`index.json` and `index.json.sig`) and believed only when its Ed25519 signature
(RFC 8032, checked by the JDK's own `Ed25519`) verifies with the public key Fuse carries
(`PortsSignature.PUBLIC_KEY`) and its revision is newer; a kept copy is checked again each time it
is read. The private key is a repository secret the ports workflow signs with; until it exists,
Fuse carries no key, fetches nothing, and shows no port as verified. **Verified** is shown only when
a signed list says the nightly check saw exactly this build (by its SHA-256) install and start.

**Installing** (`PortsStore`, `DesktopPortFiles`): the build for this computer is downloaded over
HTTPS (each redirect followed by Fuse and checked), compared with the list's size and SHA-256 (any
other file is deleted, never installed), unpacked by Fuse into a new folder beside the copy in use
(an AppImage kept as it is and made runnable; zips and tarballs without links of their own, never
outside their folder, nothing in them run), and only then put in place. Fuse's folder of ports is in
its own data folder: `ports/<id>/build` (the copy, replaced by an update), `ports/<id>/home` (its
saves and settings, kept from one version to the next and when the port is removed) and
`ports/<id>/<id>.fuseport` (how it starts).

**The game's files.** The folder the person chooses is checked for what the port needs and said by
name when something lacks. Then either its path goes in the port's arguments (OpenRCT2's
`--rct2-data-path`), or it is linked in (`overlay`): each entry of the folder is a link in Fuse's
copy, where the port reads the game's files beside itself; the port's own paths (its settings, the
folders its engine writes, `PortPlan.step`) are never linked, so nothing it writes reaches the
person's folder: its own folders are links into its home instead. Nothing is copied, and the
person's files are never changed; removing a port removes the links themselves, never what they
point at. Windows makes symbolic links only in Developer Mode or for an administrator: a folder is
joined with a junction instead, a file on the same drive with a hard link, and otherwise Fuse says
to turn on Developer Mode. The copy can be the person's own already in the library
(`copiesInLibrary`): the games of the port's game's name, offered only where the folder has what the
port needs.

**Recompilations.** A recompilation is made from one ROM (`rom` in its `data`): the library's games
of its system and name, or a file the person picks, are believed only by the XXH3-64 hash of the
ROM's big-endian form (`Xxh3`, written from xxHash 0.8's scalar code and checked against the
reference library), as N64: Recompiled's runtime checks it (librecomp `select_rom` and
`check_stored_rom`: padded to four bytes, `.v64` and `.n64` orders turned round). The ROM goes where
the port looks for it in its home: a link to the person's file when it is already in that form,
else a copy turned into it in the home; the person's file never changes. Its variables
(`env`, `APP_FOLDER_PATH={home}` for Zelda 64: Recompiled, `src/game/config.cpp`) keep its
settings, mods and saves in its home. Its Linux build is a tarball inside the release's zip
(`ZIP_TAR_GZ`). On Windows it keeps its files in the person's own AppData, so it is Linux only.

**Playing.** A set-up port is a game of the library (the Ports system), started by `PortAdapter`
from its launcher exactly as written there, from the build's folder; Play, Fast Start, Steam entries
and Direct entries all start the same command. Saves are its home's saves folder (`PortSaves`,
format `port.<id>`), kept in step like any other game's and only ever put back into the same port.
Ports keep no achievements Fuse can read. Play With on the original game's page offers the ports
set up for it beside its emulators; chosen, Play, Fast Start and its Direct entry start the port's
game in its place. Each port's own settings file (`settings` in the list) is Fuse Tune's, read
from the port's settings code by `tools/tune`. A Windows build marked to run through umu-launcher runs on
Linux as `umu-run <program>` with `WINEPREFIX` (a prefix in the port's home) and `GAMEID=umu-default`
(umu-launcher's README), and the paths it is given as Windows sees them through Proton (`Z:`). As
it starts, it gets the Proton the person uses as `PROTONPATH` (umu-launcher's README, a path to a
Proton directory): Steam Play's tool for every other title (`CompatToolMapping`, entry `0`, in
Steam's `config/config.vdf`, read only) when it is installed, else the newest Proton installed (a
tool in `compatibilitytools.d`, named by its `compatibilitytool.vdf` as ValveSoftware/Proton's
template writes it, or Valve's own in a library's `steamapps/common`), each counted only with its
`proton` script there (`SteamProton`); with none, umu downloads Steam's runtime and Proton itself
the first time. Read at every start, so a change in Steam counts at once. None of the ports listed
now needs it.

**The nightly check** (`.github/workflows/ports.yml`, `tools/ports/check.py`): every night on main
each port's Linux build is downloaded, checked against the list, unpacked the same way and started
without the game's files on a virtual screen: DevilutionX and OpenRCT2 print their versions,
Fallout 2 Community Edition must still be running after five seconds or end by itself, never by a
crash. The list is then marked with what passed, its revision raised, signed and put on the `ports`
pre-release (never the latest release, and never taken as a version of Fuse). Windows builds aren't
checked yet, so they never show as verified.

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

### Fuse's games in Steam's library (Fast Start and Direct entries)

A game's options, or a system page's, can put Fuse's games in Steam's library as non-Steam games
(`userdata/<user>/config/shortcuts.vdf`, written only while Steam is closed, with a copy kept for
Undo), each with Fuse's art and in a Steam collection for its system (`SteamGames`,
`DesktopSteam`). With the Fuse Decky plugin loaded, they go in while Steam runs instead, through
Steam's own running client (bridge protocol 2, see
[tools/decky/fuse/README.md](tools/decky/fuse/README.md)), with their pictures and the logo placed
as Fuse's own game page places it (bottom left, `SetCustomLogoPositionForApp` as `@decky/ui`
types it) unless the person placed it themselves; in Game Mode, Play From Steam in a game's options
has Steam start Fuse's entry itself (`RunGame`), with Steam's overlay and the controller layout given
to it. Collections still go in when Steam next closes: Steam's client has no typed way to change
them. And the plugin never restarts Steam: a change it makes needs none, and one written to Steam's
files must be written while Steam is closed, which a restart Steam starts itself doesn't wait for.
Settings, Library, Steam, How Steam Starts Them picks how they start:

- **Fuse Fast Start** (the default): the entry runs `Fuse --run <id>`. Fuse opens only what
  starting one game needs (its database, its secret store and Fuse One's rounds for the saves; no
  window, scan, art or network otherwise), starts the game exactly as Play does (the newest save
  put in place first), counts the session, keeps and sends the save, and leaves once all of that is
  done. Steam follows Fuse's process, so it sees the game for as long as it runs. Anything only the
  person can decide (two saves, another device still playing, a problem to read) opens Fuse on the
  game instead, as `--play` does; with Fuse already open, the open Fuse plays it. On a Linux build
  container, a release build reached the emulator 0.74 to 0.87 seconds after Steam's Play started
  Fuse (Fuse's own part about 0.4 seconds, the rest the Java runtime starting), measured by
  `FastStartTimingTest` with `-Pfuse.faststart.launcher` and again at the end of every
  `scripts/train-cds.sh`.
- **Straight in the Emulator** (Direct entries, Linux): the entry starts the emulator itself, with
  the command Play would run (`LibraryOps.directCommand`, from the same resolution as Play). Its
  launch options carry Fuse's mark and the plan's variables before `%command%`, as Steam documents
  launch options ("Set the variable, followed by `%command%`", ValveSoftware/Proton README,
  Runtime Config Options), then the emulator's arguments quoted as a POSIX shell reads them:
  `FUSE_GAME=12 %command% -f '/home/deck/ROMs/gba/Minish Cap (USA).gba'`. Nothing of Fuse's runs
  then: no save is put in place or sent, no time counted, no profile followed. A game that can't
  start on its own (an emulator that opens only its app, a program Steam can't find) goes in with
  Fast Start, and the question before writing says which and why. Elsewhere Steam doesn't read
  launch options through a shell, so every entry uses Fast Start, and Settings says so.

An entry is Fuse's only by what it starts: Fuse with `--run <id>` (or `--play <id>`, as 0.4.3 and
before wrote them, which still work and become Fast Start the next time Fuse writes them), or a
program whose launch options carry `FUSE_GAME=<id>` before `%command%`. Entries anyone else made
are never changed, and a game another tool already put in Steam is never added twice. Switching an
entry between the two keeps its id, so its art in Steam stays, and keeps the name the person gave
it. Look Them Over finds games no longer in Fuse, entries for an older copy of Fuse, older entries
that open Fuse's window first, Direct entries whose emulator is gone, and doubles, and puts right
only what it found.

**Time in Direct entries.** A Direct entry has no Fuse to count its time, so with the Fuse Decky
plugin loaded, Steam's own app lifetime events count it: Fuse names its Direct entries to the
plugin, the plugin notes when Steam starts and stops one of them (and only them), keeps each session
in its own settings folder until Fuse answers, and Fuse counts it once for the game. Without the
plugin, time played in a Direct entry isn't counted (Steam's own `localconfig.vdf` isn't documented
by Valve, so Fuse doesn't read it).

**Keep Steam in Step** (off until chosen) puts games that join the library into Steam by themselves,
starting as How Steam Starts Them says (or as a game's own Steam Entry says), and takes out Fuse's
entries for games that leave it. Games already in the library aren't added by it. Fuse offers it
once, after the first add the person makes themselves, and never again. **Undo a Change** lists Fuse's last ten writes, each with
what it did, and puts Steam's list back as it was before any of them.

**Shortcuts From Other Tools** (Settings, Library, Steam) reads the shortcuts Steam ROM Manager,
EmuDeck, a script or the person made (`SteamLearn`) and lists those that start one of Fuse's games,
matched only by the game's own file (its path, or the file it starts from) appearing whole as the
shortcut's program or one of its arguments, read as a POSIX shell splits words; never by name.
Each can be taken over (all at once, or one by one) or left: taken over, the shortcut becomes Fuse's
entry in the same place, starting as How Steam Starts Them says, and keeps its id (written out, so
Steam's art for it stays), its name, icon and tags. Steam must be closed, and Undo puts the list back.

**Learned From Your Shortcuts** (Settings, Library, Steam, with Direct entries) is what those same
shortcuts show of how the person starts each system's games (`SteamLearn.habits`): the program
(`Exe`), its start folder (`StartDir`) and the launch options before and after the game's file,
each exactly as they write it, quotes and variables before `%command%` included; per system, the
way most of their shortcuts do it. A Direct entry for that system then starts the same way, with
Fuse's mark before their launch options (`SteamWritten`), while their program is still there (else
as Play starts it); the switch Start Them as My Shortcuts Do turns it off. Only a shortcut whose
program is a full path and whose launch options name a game's file is learned from. Three things
are not copied: frame generation they add before `%command%` (lsfg-vk's `LSFGVK_` and earlier
`LSFG_` variables, or a wrapper named for it such as `~/lsfg`) is set aside and offered as Fuse's
own Frame Generation for that system; a start folder inside an AppImage's temporary mount
(`/tmp/.mount_`, gone once it closes) becomes the AppImage's own folder; and a shortcut whose
program is inside such a mount isn't learned from. A game's file is written the way they write
theirs: in double quotes, single quotes or with backslashes, and with `/home` as `/var/home`
(Fedora's atomic desktops link one to the other) or `/run/media` as `/media` (where udisks2 mounts
drives on most systems, and on Debian and Ubuntu) where they write it so; their shortcuts are found
by either form. Before anything is written, See Each Entry shows what Steam will start for each
entry, from where, with which launch options.

**Collections** are one per system, named maker then console ("Nintendo Game Boy Advance"). **Your
Collections** (Settings, Library, Steam) lists the person's own collections that are one of these
by another name (the system's full name, short name or a folder name for it) and lets each be kept
apart, given Fuse's games instead of Fuse making its own, or renamed Fuse's way with them in it;
theirs is never deleted and nothing is taken out of it. The choices are kept beside Steam's files
(`config/fuse-collections.json`), with the names of Fuse's own collections it has written: after
each Steam session, one Steam Cloud dropped (not in Steam's list at all) is put back, while one the
person deleted (still there, marked deleted) stays gone. Each picture is checked for the shape
Steam shows it in (Steamworks' Library Assets: the capsule 600 by 900, the hero 3840 by 1240; Store
Graphical Assets: the header 460 by 215; icons square), within 15%, and one far from it is left to
Steam. Looking over Fuse's entries finds, besides games gone, doubles and old programs, Direct
entries that no longer start their game as Play would (the emulator updated or moved, the game
moved to another drive, the way of starting changed) and games whose drive is out; System health
shows both, with Put Them Right writing the entries again where they are.

**Leaves the device.** Nothing; Steam's files are on this device, and Fast Start's save rounds are
Fuse One's own (see [docs/sync.md](docs/sync.md)).

### Frame generation (lsfg-vk, Linux)

A system's settings and a game's options have Frame Generation (Off, 2x, 3x, 4x) on Linux. With it
on, the emulator's command gets lsfg-vk's own variables, as lsfg-vk documents them
([lsfg-vk.dev](https://lsfg-vk.dev), Configuration, Environment Variables): `LSFGVK_ENV=1` and
`LSFGVK_MULTIPLIER` (`core:launch/.../FrameGeneration.kt`). It is offered once lsfg-vk is set up,
which is when its configuration file is there (`~/.config/lsfg-vk/conf.toml`, or under an absolute
`XDG_CONFIG_HOME`); lsfg-vk itself needs Lossless Scaling, bought on Steam. Play, Fast Start and
Direct entries all get it from the same resolution. Fuse never writes lsfg-vk's file.

**Leaves the device.** Nothing.

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

## Mod sites (GameBanana and Nexus Mods)

Both are found in a game's own cheats, textures and mods (GameBanana on Android too, Nexus Mods on a
computer), and both end in the same place as a file of your own: Fuse shows every file and where it goes before anything is added
(below, [Emulators' own files](#emulators-own-files)). Which game on the site a library game is, is
always your choice from the site's own list, never guessed from a similar name, and is remembered.

**GameBanana** (`core/integrations/.../gamebanana/GameBanana.kt`, `ModSites`). Its public API
(`gamebanana.com/apiv11`), read only, with no account: a search for the game, a game's mods (most
liked first) and a mod's page. A mod is offered only when its category is one Fuse adds for the
game's emulator and its file passed GameBanana's own checks and is a `.zip`; the file is checked
against GameBanana's size and checksum once it arrives.

**Nexus Mods** (`core/integrations/.../nexus/NexusMods.kt`, `NexusSite`, Settings, Accounts, Nexus
Mods). Its public API v1 (`api.nexusmods.com`), as its documentation lays it out and as its own
client (`Nexus-Mods/node-nexus-api`, `src/types.ts`) types the answers.

- **Your own key only.** You paste your personal API key from your account's API Access page. Nexus
  Mods asks that no key be built into software, so Fuse carries none. The key is kept encrypted in
  Fuse's secret store on this device only (`SecretKeys.deviceOnly`): it is never among the keys
  Fuse One shares with your household, never in an address, and redacted from every message.
- **What is asked.** `users/validate.json` (whose key it is and whether the account is Premium; it
  doesn't count towards Nexus Mods' limits), `games.json` (to choose the game, kept a week), a
  game's `trending`, `latest_added` and `latest_updated` lists (kept an hour, so browsing doesn't
  spend the account's daily requests), a mod's page and its `files.json`. Every request names Fuse
  in `Application-Name` and `Application-Version`, as Nexus Mods' own client does. Mods marked for
  adults and ones that aren't available are left out of the lists.
- **Downloads.** `download_link.json` gives an address straight away to Premium accounts only. For
  any other account Nexus Mods asks for the one-time `key` and `expires` of the website's own Mod
  Manager Download link (`nxm://<game>/mods/<mod>/files/<file>?key=…&expires=…`), so the person has
  visited the mod's page: Fuse opens that page, you copy the link and paste it, and Fuse checks it is
  for that same file before asking. A file you downloaded yourself can be added as one of your own
  instead. Only https addresses are followed, only a current `.zip` (not an old, deleted or archived
  file) of up to 4 GB is fetched, and a file that doesn't arrive whole leaves nothing behind. A
  refusal (403: Premium only; 410: the link ran out; 400: not this file or account) is said in
  plain words.

## Emulators' own files

Fuse changes an emulator's files only when you ask, never automatically, and only what it can undo
exactly.

- **Play Together** (Linux, Windows, macOS), from a game's options, only when asked, each change
  recorded apart from the person's other setting changes so Put Back undoes exactly these, from
  each emulator's own source:
  - RetroArch (v1.22.2, `retroarch.c`, `retroarch_parse_input`): Host a Game starts it with
    `--host`, Join with `--connect=<address>` (and `--port` when the host's isn't 55435, `config.def.h`
    `RARCH_DEFAULT_PORT`), and `--nick` with the Fuse One profile's name, put before its `-L`; nothing
    of RetroArch's own files changes.
  - PPSSPP (v1.20.4, `Core/Config.cpp` `[Network]`): `EnableWlan`, then `proAdhocServer` set to the
    host's address with `EnableAdhocServer` on for the one hosting (its own server on port 27312,
    `Core/HLE/sceNetAdhoc.cpp`), and `[SystemParam] NickName`. The host names its own home address,
    never `localhost`: PPSSPP's server gives each player the address it saw them at
    (`Core/HLE/proAdhocServer.cpp`).
  - Dolphin (2609a, `Core/Config/NetplaySettings.cpp`): `[NetPlay] Nickname`, and to join,
    `TraversalChoice = direct` and `Address`, which its own NetPlay window's Connect tab offers.
  - RPCS3 (v0.0.43, `rpcs3/Emu/system_config.h`): `Net: Internet enabled: Connected` and `PSN
    status: RPCN`; the RPCN account in `rpcn.yml` beside `config.yml` (`rpcs3/Emu/NP/rpcn_config.cpp`),
    with the password stretched as RPCS3's own dialog does (`rpcs3qt/rpcn_settings_dialog.cpp`,
    `derive_password`: PBKDF2 over HMAC-SHA3-256, 200,000 rounds, its own salt, upper-case hex). The
    password itself is used once and kept nowhere, by Fuse or RPCS3, and Fuse keeps no copy of
    `rpcn.yml`. Sign Out empties its password and token.
  - PCSX2 (v2.8.2, `pcsx2/Pcsx2Config.cpp` `DEV9Options::LoadSave`): `[DEV9/Eth] EthEnable = true`,
    `EthApi = Sockets`, `EthDevice = Auto`, which needs no administrator rights and answers the game's
    DHCP itself (`pcsx2/DEV9/sockets.cpp`).
  - After the Store updates one of these, what Fuse set up is looked at again, and said when it
    isn't there any more (a portable program's settings go with the version it replaced).
- **Cheats, textures and mods of your own** (Linux, Windows, macOS). Where each emulator reads a
  game's mods is a rule book in `core/launch` (`patches/Mods.kt`), from each emulator's own code:
  Dolphin (`VideoCommon/HiresTextures.cpp`, `GraphicsModSystem/Config/GraphicsModGroup.cpp`), Cemu
  (`Cafe/GraphicPack/GraphicPack2.cpp`), Eden and its family (`core/file_sys/patch_manager.cpp`),
  Ryujinx, RPCS3 and shadPS4 (`core/file_sys/fs.cpp`); cheats and patches by each emulator's own
  format (PCSX2's `.pnach`, DuckStation's patch files, PPSSPP's `.ini` cheats, RetroArch's `.cht`).
  Every file and where it goes is shown first, nothing outside the emulator's own folder for it is
  written, a file already there is kept to put back, and Remove takes out exactly what went in.
  Each mod sits in a folder of its own, which the emulator layers; when a mod about to be added
  changes a file another added mod changes too (compared without case, as the emulators' file
  systems may not tell case apart), Fuse names the other mod and the files before it goes in.
- **Dolphin's cheats.** Gecko and Action Replay codes, from a game's `.ini` or a `.txt` of `$`-named
  Gecko codes, are added to the end of the game's own settings file (`GameSettings/<game id>.ini`)
  as Dolphin's own code reads and writes them: `Core/GeckoCodeConfig.cpp` (`LoadCodes`: a `$Name
  [Creator]` line begins a code, `*` lines are its notes, then lines of two eight-digit hexadecimal
  numbers; `[Gecko_Enabled]` lists the codes turned on) and `Core/ActionReplay.cpp` (`LoadCodes`,
  `DeserializeLine`: decrypted `XXXXXXXX YYYYYYYY` or encrypted `XXXX-XXXX-XXXXX` lines). Dolphin's
  ini reader (`Common/IniFile.cpp`) adds a section met again to the one before and keeps `$`, `*`
  and code lines as they are, so the codes go in as one block and come out again exactly. The block
  turns on the game's own Enable Cheats (`[Core] EnableCheats`, which
  `ConfigLoaders/GameConfigLoader.cpp` maps to that game's Core settings) and leaves every code off,
  to be ticked in Dolphin's game properties. A code the game already has is left out, a line
  Dolphin wouldn't read is named, and a file named for another game's id is turned away. The
  settings file as it was is kept first.

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
- **Emulator settings, and Every Setting (Fuse Tune)** (Linux, Windows, macOS). Fuse's own picks
  (renderer, resolution, aspect ratio and the like, with Fuse's words for them) and Every Setting
  change the emulator's own settings files, for every game or for one game: PCSX2
  (`inis/PCSX2.ini`, `gamesettings/SERIAL_CRC.ini`), DuckStation (`settings.ini`,
  `gamesettings/SERIAL.ini`), PPSSPP (`PSP/SYSTEM/ppsspp.ini`, `PSP/SYSTEM/GAMEID_ppsspp.ini`), Dolphin (`Dolphin.ini`
  and `GFX.ini`, a game's `GameSettings/GAMEID.ini` under its own section names), RPCS3
  (`config.yml`, a game's `custom_configs/config_<title id>.yml`, in RPCS3's `config` folder on
  Windows, as `fs::get_config_dir(true)` has it), RetroArch (`retroarch.cfg`, a game's override
  `config/<core's library name>/<content>.cfg`, as `config_load_override` in `configuration.c`
  finds it) and Eden (`qt-config.ini`, a game's `custom/<title id>.ini`, in its config folder as
  `src/common/fs/path_util.cpp` and `fs_paths.h` put it: `user\config` beside the program or
  `%APPDATA%\eden\config` on Windows, `$XDG_CONFIG_HOME/eden` or `~/.config/eden` elsewhere,
  inside `~/.var/app/dev.eden_emu.eden` for the Flatpak) and Azahar (`qt-config.ini`, a game's
  `custom/<title id>.ini` as `ConfigurePerGame` names it, in its config folder as
  `src/common/file_util.cpp` puts it: `user\config` beside the program when that folder is there or
  `%APPDATA%\Azahar\config` on Windows, `$XDG_CONFIG_HOME/azahar-emu` on Linux,
  `~/.var/app/org.azahar_emu.Azahar/config/azahar-emu` for the Flatpak, and on macOS the same XDG
  folder when Azahar made one, else `~/Library/Application Support/Azahar/config`), with a game's
  title id read from its own header as for its saves. Cemu (a Wii U game's profile,
  `gameProfiles/<title id>.ini` in its config folder, by the `<title_id>` of the game folder's
  `meta/meta.xml`, as `GameProfile::Load` reads it; Cemu reads it alone, else the profile it ships
  in `gameProfiles/default/` of its data folder, so Fuse makes the game's own as a copy of that one;
  its settings for every game stay in its Options) and shadPS4 (`config.json` in its user folder,
  a game's `custom_configs/<serial>.json`, as `EmulatorSettingsImpl::Save` writes them in
  `src/core/emulator_settings.cpp`); Citron as Eden, in its own `citron` folder; Azahar's family in
  their own folders from each one's `common_paths.h` (`azaharplus-emu`, `lime3ds-emu`,
  `mandarine-emu`, and their Windows and macOS names); Xenia (`xenia-canary.config.toml` in its
  storage root, `portable.txt` beside the program making that the program's folder, else Xenia in
  Documents on Windows and in the XDG data folder elsewhere, as `xenia_main.cc` sets it; a game's
  `config/<title id>.config.toml`); and Vita3K (`config.yml` in its config folder, as
  `app_init.cpp` sets it: a `portable` folder beside the program, else `$XDG_CONFIG_HOME/Vita3K` on
  Linux, the program's own folder on Windows and its Application Support folder on a Mac; a
  game's `config/config_<title id>.xml`, which Vita3K reads an element at a time, so Fuse makes it
  with every setting it holds); Flycast (`emu.cfg` in the config folder its saves are found by; a
  game's own in its `[<product number>]` section there, each key as `<section>.<key>`, as
  `Option::load` in `core/cfg/option.h` reads it); MAME (`mame.ini` in the first folder of its ini
  path that has one, `$HOME/.mame` on Linux, Application Support's `mame` then `$HOME/.mame` on a
  Mac and the program's folder or its `ini` on Windows; a system's own `<short name>.ini` beside
  it); Supermodel (`Supermodel.ini` in its `Config` folder, as `FileSystemPath.cpp` finds it: a
  `Config` folder where it starts, the game's own folder as Fuse and ES-DE start it, else
  `~/.supermodel/Config`, else `~/.config/supermodel/Config` or `$XDG_CONFIG_HOME`'s, beside the
  program on Windows and in the game's folder on a Mac; a game's own in its `[ <ROM set> ]`
  section, read over `[ Global ]`); and melonDS (`melonDS.toml` in its config folder, as
  `pathInit` in `main.cpp` sets it: a `portable` folder beside the program, else Qt's config
  folder's `melonDS`; for every game only, the first instance's own under `[Instance0]`). A RetroArch game's core options are in
  `config/<core>/<content>.opt` (`runloop_init_core_options_path` in `runloop.c`), which RetroArch
  reads alone when it is there, so Fuse makes it as RetroArch's Quick Menu does, with every option
  as the game has it now from the folder's `.opt`, the core's own `<core>.opt`, or
  `retroarch-core-options.cfg` (`core_options_path`) with `global_core_options` on, and offers none
  when `game_specific_options` is off. Every Setting is all of an emulator's settings,
  read by Fuse Tune (`tools/tune`) from the emulator's own settings code at its last four release
  tags, never typed in by hand: PCSX2's `SettingsWrap*` calls in `pcsx2/Pcsx2Config.cpp` with types
  and defaults from `pcsx2/Config.h` and each options' constructor; DuckStation's `si.Get*Value`
  calls in `src/core/settings.cpp` with its name arrays and the constants in `settings.h`;
  PPSSPP's `ConfigSetting` tables in `Core/Config.cpp` (only those flagged `PER_GAME` for a game,
  none flagged `DONT_SAVE`); Dolphin's `Info<T>` declarations in `Core/Config/*Settings.cpp`, with
  `GameConfigLoader.cpp`'s table of a game's sections; RPCS3's `cfg::node` tree in
  `rpcs3/Emu/system_config.h`, with the words for its choices from `system_config_types.cpp`;
  RetroArch's `SETTING_*` lists in `configuration.c` (paths and folders, this computer's own,
  left out) with defaults from `config.def.h`, and each setting's name, description, range and tab
  from its menu entry in `menu/menu_setting.c` and the English words in `intl/msg_hash_us.h`;
  Eden's `Setting` and `SwitchableSetting` declarations in `src/common/settings.h` (only a
  switchable one for a game, none not saved), its categories' sections from `TranslateCategory`
  in `settings.cpp`, its enums from `settings_enums.h` (written as each member's number) and the
  names and descriptions its Qt frontend gives them in `src/qt_common/config/shared_translation.cpp`,
  read from Eden's own Forgejo (`git.eden-emu.dev`); Azahar's `SwitchableSetting` and `Setting`
  declarations in `src/common/settings.h`, the group each is kept under and which a game's file
  holds from `QtConfig`'s `Read*Values` in `src/citra_qt/configuration/config.cpp` (read outside
  `if (global)`), and the names, descriptions and choices' names its Android app's settings
  screens give them (`SettingsFragmentPresenter.kt`, with `strings.xml` and `arrays.xml`); and each
  RetroArch core's own `libretro_core_options.h` (`option_defs_us`, or `option_defs` where a core
  fills that in when it starts, and its categories `option_cats_us`) at the head of the branch
  libretro-super's build recipe names (`recipes/linux/cores-linux-x64-generic`), a key made of
  macros read through the `#define`s of the core's other headers, its sources and the headers they
  include, as the recipe's build flags define them; Cemu's `GameProfile::Load` with the types and
  defaults in `GameProfile.h` and the words `CemuConfig.h`'s formatters write each enum as; and
  shadPS4's `Setting<T>` groups in `src/core/emulator_settings.h`, each group's written members
  and `GetOverrideableFields` (those a game's file may hold), as a Linux build compiles it; Xenia's
  `DEFINE_*` settings anywhere in `src/xenia`, from a sparse checkout of its tree; and Vita3K's
  `CONFIG_INDIVIDUAL` list with `load_custom_config` for what a game's file holds and the name it
  has there; Flycast's `Option<T>` declarations in `core/cfg/option.cpp` and the classes of their
  own in `option.h` (its renderer one of `core/types.h`'s `RenderType`s), with the names, help,
  ranges and choices its `core/ui/settings_*.cpp` screens give them; MAME's option tables
  (`s_option_entries[]` in `src/emu/emuopts.cpp`, `osdobj_common.cpp` and `sdlopts.cpp`, paths and
  commands left out); Supermodel's `DefaultConfig()` in `Src/OSD/SDL/Main.cpp`, with what each does
  from its `-help` text, at its newest daily build (every one is 0.3a); and melonDS's every `Get*`
  read in its Qt frontend, by the table each is read from, with `Config.cpp`'s defaults and ranges
  and the names, help and choices of its dialogs' `.ui` files. A text setting whose key names a
  password, token, secret or API key (a sign-in: RetroArch's RetroAchievements and WebDAV ones,
  Flycast's RetroAchievements token) is left out, so Fuse never shows or writes one. Ryujinx isn't
  read yet: its source is only on git.ryujinx.app. Only
  names, kinds, ranges, choices and defaults are taken, no code. The installed version decides
  which settings show (the newest release read that is no newer than it; when Fuse can't tell the
  version, every release's, those not every release has locked with which have them). On Linux the
  version is read without starting anything where it can be: `flatpak list`, else the AppStream file
  in a Flatpak's own deployment folder; an AppImage's name or the release it updates from; the
  AppStream file a distribution package put beside its program; else, for RetroArch and PPSSPP's SDL
  build only, the program's own `--version`, which each prints before it opens anything. A choice a system's
  build hasn't got (DuckStation's Direct3D on Linux) isn't offered, and a value the emulator
  wouldn't read (a number out of its range) is refused before anything is written. Files are
  edited in place: every other line, comment and spacing stays, an ini file through `IniText`,
  RPCS3's YAML through `YamlText` and RetroArch's cfg through `CfgText`. Eden keeps each value in
  two or three keys, `key\default` beside `key` and, in a game's file, `key\use_global` first
  (`Config::WriteSettingGeneric` in `src/frontend_common/config.cpp`), and reads them back
  together (`Config::ReadSettingGeneric`): Fuse reads them the same way and writes each one Eden
  reads (`EdenConfig`), without spaces as Eden's SimpleIni does. Azahar's Qt ini keeps
  `key\default` beside `key` in the file for every game and `key\use_global=false` beside it in a
  game's (`QtConfig::WriteBasicSetting` and `WriteGlobalSetting`), a group with a space written as
  `%20`: Fuse reads and writes each as Azahar does (`AzaharConfig`). shadPS4's JSON goes through
  `JsonText`: a value changed where it is, a text one kept a string, a new member last in its
  group as nlohmann's `dump(2)` lays it out. Xenia's TOML goes through `TomlText` (the value only,
  its comment kept; melonDS's root table as the section with no name, a decimal written with its
  point, as melonDS's toml11 reads only a float as one) and Vita3K's XML through `XmlText` (an
  attribute changed where it is, new ones last, a new file as pugixml starts one). Flycast's
  switches are written `yes` and `no` and MAME's `1` and `0`, as each writes its own; a MAME ini
  goes through `MameIniText` (each option's name padded to 25 characters, as
  `core_options::output_ini` writes it); and Supermodel's values are read with their quotes and
  `;` comments off and a switch every way it reads one (`SupermodelIni`), text written in quotes
  as its own file has it. Fuse keeps what each key held before it first changed it, and
  Put Back What Fuse Changed restores exactly that (a key changed in the emulator since stays); a
  game's own file Fuse made is taken away again once nothing it set is left. RPCS3 gives a game
  with its own settings file none of the settings from its own database
  (`rpcs3/Emu/System.cpp`: "Found custom config. Ignoring database config"), so Fuse says so before
  it makes one.
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

  System software (a PS3's or a Vita's `.pup`, chosen by the person in Settings, Systems, Firmware)
  goes through the same installers: RPCS3's `--headless --installfw <file>` (`rpcs3/rpcs3.cpp`,
  v0.0.43: "Forces the emulator to install this firmware file"; `main_window::InstallPup`), and
  Vita3K's `--firmware <file>` (`vita3k/config/src/config.cpp` at b4e35ed3: "Path to the firmware
  file (.pup extension) to install", which ends the command line, and `vita3k/main.cpp` installs it
  with `install_pup` and quits). Fuse hands over only a `.pup`, never copies or changes it, and
  claims nothing when the installer fails.
- **PS4 trophies before shadPS4 sets them out.** shadPS4 writes a game's trophy list into its user
  folder when the game first opens it; until then, Fuse reads the game's own
  `sce_sys/trophy/trophy00.trp` as shadPS4 v.0.20.0 does (`src/core/file_format/trp.cpp`): its
  `TROP.ESFM` decrypted with AES-128-CBC, the key being the set's id encrypted under the person's
  own trophy key (`keys.json`'s `TrophyKeySet.ReleaseTrophyKey` in shadPS4's user folder,
  `src/common/key_manager.cpp`, or an older `config.toml`'s `[Keys] TrophyKey`). The key is only
  read, in memory, and nothing is written; a wrong key decrypts to nothing, which is never shown.
- **Disc identity.** The serial and PCSX2 CRC are read from the image itself (SYSTEM.CNF and the boot
  program, as in `pcsx2/CDVD/CDVD.cpp` and `pcsx2/Elfheader.cpp`); nothing is written.

## ROM patches

Apply a Patch, in a game's options, makes a patched copy beside the game and adds it to the library;
the game itself is never changed (`core/library/.../patch/RomPatches.kt`, pure, and the copy
written by the data layer). Nothing leaves the device.

- **IPS, UPS and BPS**, by each format's own description: UPS and BPS carry the CRC32 of the game
  they were made for and of their result, so a patch for another version is said before anything is
  made, and a game with a copier header (512 bytes before a SNES game) is tried without it.
- **xdelta** (`Vcdiff.kt`): VCDIFF as RFC 3284 lays it out, with xdelta3's own additions as its
  decoder (`xdelta3-decode.h`, `xd3_decode_input`) reads them: its application header, and the
  Adler-32 of each window's result, which a patch for another version fails. Sections xdelta3
  compressed again with its LZMA (its default when built with liblzma, `xdelta3-main.h`) are read
  as `xdelta3-second.h` and `xdelta3-lzma.h` write them: each section's decoded size, then the next
  part of one .xz stream (LZMA2, no check) per kind of section through every window, decoded with
  xz-java. DJW and FGK, xdelta3's other compressors, and a code table of the patch's own are
  refused with what to do instead. Tested against patches xdelta3 3.2.2 made from its own source,
  byte for byte.
