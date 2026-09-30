# Research notes: RomM, RetroAchievements, scrapers

Collected 2026-09-30 from official docs and source. Snapshots: RomM master `6226e50` (latest tag 5.3.1), RomM docs `e64f9b1`,
RA api-docs `9d0aeb7`, RAWeb `275e1fb`, rcheevos `f87c0de` (12.5.0), RetroArch `fbff529`, SteamGridDB OpenAPI 2.10.0,
TheGamesDB spec.yaml 2.0.0. Items marked UNVERIFIED could not be confirmed first-hand.

## RomM

### Layout (breaking change in 5.3.0)

RomM 5.3.0 (2026-09-21) replaced Structure A/B auto-detection with a structure template (`filesystem.structure` in
`config.yml`). Structure A is the default; Structure B is opt-in and RomM refuses to start on an undeclared
`{platform}/roms` layout.

| | Structure A (default) | Structure B (opt-in since 5.3.0) |
|---|---|---|
| ROMs | `library/roms/{platform}/{game}` | `library/{platform}/roms/{game}` |
| BIOS | `library/bios/{platform}/` | `library/{platform}/bios/` |

Template rules (`config_manager.py`, `parse_structure_template`):
- `{platform}` marks the platform folder; every section before it is a literal folder name.
- `{game}` is terminal. At the `{game}` level a file is one game and a folder is one multi-file game.
- Any other braced section (`{region}`, `{category}`) is a purely organisational wildcard level.
- Per-platform overrides keyed by folder name, a template or a list (discovery is the union).
- Hidden (dot) folders are never scanned.

Never-game folders (`DEFAULT_EXCLUDED_MULTI_FILE_DIRS`): `@eaDir, assets, __MACOSX, #recycle, $RECYCLE.BIN, .Trash-*,
.stfolder, .Spotlight-V100, .fseventsd, .DocumentRevisions-V100, System Volume Information`, and the ES-DE/Batocera media
folders `images, covers, backcovers, 3dboxes, bezels, fanart, manuals, marquees, miximages, miximages_v2, physicalmedia,
screenshots, thumbnails, titlescreens, videos`.

Ignored files: extensions `db, tmp, bak, lock, log, cache, crdownload, assembling`; names `.DS_Store, .localized,
.Trashes, .stfolder, @SynoResource, *:Zone.Identifier, gamelist.xml, metadata.pegasus.txt`.

### Platform folder names

Resolution order (`platform_aliases.py::resolve_platform_slug`, lowercased): `system.platforms`/`system.versions`
binding, then the folder name as a RomM slug, then the Batocera/RetroBat/ES-DE alias table, else a custom platform.

| System | RomM slug | Aliases |
|---|---|---|
| PlayStation | `psx` | `ps` |
| PS2 / PS3 / PS4 / PS5 | `ps2` / `ps3` / `ps4` / `ps5` | `ps3-psn` -> `ps3` |
| PSP / PS Vita | `psp` / `psvita` | |
| Switch / Switch 2 | `switch` / `switch-2` | |
| N64 | `n64` | |
| NDS / DSi | `nds` / `nintendo-dsi` | |
| 3DS / New 3DS | `3ds` / `new-nintendo-3ds` | `n3ds` |
| GB / GBC / GBA | `gb` / `gbc` / `gba` | `sgb` -> `gbc` |
| NES / Famicom / FDS | `nes` / `famicom` / `fds` | |
| SNES / Super Famicom | `snes` / `sfam` | `sfc` -> `sfam` |
| GameCube | `ngc` | `gamecube`, `gc` |
| Wii / Wii U | `wii` / `wiiu` | `wiiware` -> `wii` |
| Dreamcast | `dc` | `dreamcast` |
| Saturn | `saturn` | |
| Mega Drive / Genesis | `genesis` | `megadrive`, `megadrivejp` |
| Sega CD / 32X | `segacd` / `sega32` | `megacd` / `sega32x` |
| Master System / Game Gear | `sms` / `gamegear` | `mastersystem`, `mark3` |
| Arcade | `arcade` | `mame`, `fbneo`, `fba`, `naomi`, `atomiswave` |
| Neo Geo AES / CD | `neogeoaes` / `neo-geo-cd` | `neogeo` / `neogeocd` |
| NGP / NGPC | `neo-geo-pocket` / `neo-geo-pocket-color` | `ngp` / `ngpc` |
| PC Engine / CD | `tg16` / `turbografx-cd` | `pcengine` / `pcenginecd` |
| Atari 2600 / 7800 / Lynx / Jaguar | `atari2600` / `atari7800` / `lynx` / `jaguar` | `atarilynx`, `atarijaguar` |
| WonderSwan / Color | `wonderswan` / `wonderswan-color` | `wswan` / `wswanc` |
| Virtual Boy, Pokemon mini | `virtualboy`, `pokemon-mini` | `pokemini` |
| Xbox / 360 | `xbox` / `xbox360` | |
| Windows / DOS | `win` / `dos` | `pc`, `windows` -> `win` |

### Multi-file games (`backend/models/rom.py`)

```python
class RomFileCategory(enum.StrEnum):
    GAME="game"; DLC="dlc"; HACK="hack"; MANUAL="manual"; WALKTHROUGH="walkthrough"
    PATCH="patch"; UPDATE="update"; MOD="mod"; DEMO="demo"; TRANSLATION="translation"
    PROTOTYPE="prototype"; CHEAT="cheat"; SOUNDTRACK="soundtrack"; SCREENSHOT="screenshot"
```

- Matching (`category_matches`): lowercased name equals `x`, `xs` or `xes` (`DLC`, `dlcs`, `Updates`, `patches`).
- Only the first folder directly under the game folder counts.
- `manual, walkthrough, soundtrack, cheat, screenshot` never hold a ROM binary.
- `manuals`/`screenshots` directly under a platform folder are excluded media dirs; inside a game folder they are categories.

### Filename tags (`FSRomsHandler.parse_tags`)

`\(([^)]+)\)|\[([^]]+)\]`, each tag split on `,`. Order: region code, language code, translation, region name,
language name, version (`^(version|ver|v)`), `Reg-`, `Rev-`, else other.
Region codes: A AUS, AS Asia, B Brazil, C Canada, CH China, E Europe, F France, FN Finland, G Germany, GR Greece,
H Holland, HK Hong Kong, I Italy, J Japan, K Korea, NL, NO, PD Public Domain, R Russia, S Spain, SW Sweden, T Taiwan,
U USA, UK, UNK, UNL, W World. Language codes: Ar Da De El En Es Fi Fr It Ja Ko Nl No Pl Pt Ru Sr Sv Zh.
Provider id tags: `(igdb-N)`, `(ra-N)`, `(ssfr-N)`, `(steam-N)` and others.

### API auth (reference only; Fuse never talks to RomM directly)

Client API tokens `Authorization: Bearer rmm_<64 hex>` with scopes. Cartridge owns RomM credentials.

## RetroAchievements Web API

- `https://retroachievements.org/API/API_<Name>.php`, web API key in query param `y`, target user in `u`
  (username or ULID; usernames can change, store the ULID).
- "Rate limiting is enabled", no published numbers. Cache static data.
- Endpoints: `API_GetUserProfile` (u), `API_GetUserSummary` (u, g, a), `API_GetUserRecentAchievements` (u, m minutes),
  `API_GetGameInfoAndUserProgress` (g, u, a=1), `API_GetUserCompletionProgress` (u, c<=500, o),
  `API_GetUserAwards` (u), `API_GetUserRecentlyPlayedGames` (u, c<=50, o), `API_GetGameHashes` (i),
  `API_GetGameList` (i console, f=1, h=1), `API_GetConsoleIDs` (a=1, g=1).
- Images: prefix relative paths with `https://media.retroachievements.org` (`/Badge/250336.png`, `_lock.png` variant).
- rcheevos is MIT. Hash per console (whole-file MD5, header-stripped for NES/SNES/PCE/7800/Lynx, byte-order normalised
  N64, custom for NDS, 3DS and disc systems, filename for arcade). Console ids: 1 MD, 2 N64, 3 SNES, 4 GB, 5 GBA, 6 GBC,
  7 NES, 8 PCE, 9 SegaCD, 10 32X, 11 SMS, 12 PSX, 16 GC, 18 NDS, 19 Wii, 21 PS2, 27 Arcade, 39 Saturn, 40 DC, 41 PSP,
  62 3DS, 76 PCE-CD, 78 DSi, 81 FDS, 82 PS3.

## SteamGridDB API v2

- `https://www.steamgriddb.com/api/v2`, `Authorization: Bearer <key>`. No deprecated v2 endpoints; v1 is gone.
- `/search/autocomplete/{term}`, `/games/id/{id}`, `/games/{platform}/{platformId}`,
  `/grids|heroes|logos|icons/game/{gameId}`.
- Params: `styles`, `dimensions` (grids `460x215, 920x430, 600x900, 342x482, 660x930, 512x512, 1024x1024`; heroes
  `1920x620, 3840x1240, 1600x650`; icons square sizes), `mimes`, `types` (static/animated), `nsfw`, `humor`, `epilepsy`
  (false default), `limit` (<=50), `page`.
- Response `{success, page, total, limit, data:[{id, score, style, url, thumb, tags, author, width, height}]}`.

## IGDB v4

Twitch client-credentials; the client secret must never ship in a public app. Fuse asks each user for their own Client ID
and Secret. 4 requests/second, 8 concurrent. Images `https://images.igdb.com/igdb/image/upload/t_{size}/{image_id}.jpg`
(`cover_big`, `screenshot_huge`, `1080p`, `_2x`).

## TheGamesDB

`https://api.thegamesdb.net`, `apikey` query param. Public keys are limited per IP (forum cites 1500/month, UNVERIFIED).
`/v1.1/Games/ByGameName`, `/v1/Games/Images`, `/v1/Games/ByGameHash`. Images `https://cdn.thegamesdb.net/images/{size}/...`.
Fuse asks the user for their own key.

## ScreenScraper

Needs developer credentials (`devid`, `devpassword`, `softname`) granted to free software on request, plus optional user
credentials. Fuse does not ship developer credentials; official builds may inject them from CI secrets later (like RomM).
Until then the provider is marked "needs developer credentials".

## Libretro thumbnails

`https://thumbnails.libretro.com/{System}/{Named_Boxarts|Named_Snaps|Named_Titles|Named_Logos}/{Name}.png`, URL-encoded.
Replace `& * / : ` " < > ? \ |` with `_`. No licence file: fetch at runtime and cache on device, never bundle.
