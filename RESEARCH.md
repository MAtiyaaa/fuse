# Research

This is the summary of what the Fuse team learned before and while building Fuse 0.0.1: how other
frontends work, what Android and Linux allow a launcher to do, how emulators accept games from another
app, how RomM lays out a library, and what the metadata and achievement services offer.

The detailed notes, with every source and confidence label, live in `docs/research/`:

| Note | Covers |
|---|---|
| [docs/research/iisu-and-frontends.md](docs/research/iisu-and-frontends.md) | iiSU and the 2026 Android frontend baseline |
| [docs/research/android-platform.md](docs/research/android-platform.md) | Android APIs for a Home app and launcher (targetSdk 36) |
| [docs/research/emulators.md](docs/research/emulators.md) | Verified launch intents for Android emulators and PC launchers |
| [docs/research/romm-ra-scrapers.md](docs/research/romm-ra-scrapers.md) | RomM folder structures, RetroAchievements Web API, scraper APIs |

All notes were collected on 2026-09-30. Where this summary and a note disagree, the note is the
more detailed source; where the code and a note disagree, the code comment says which source it
followed.

## Contents

1. [Frontends studied](#1-frontends-studied)
2. [Android platform constraints](#2-android-platform-constraints)
3. [Linux](#3-linux)
4. [Emulator launch facts](#4-emulator-launch-facts)
5. [RomM folder structures and content categories](#5-romm-folder-structures-and-content-categories)
6. [RetroAchievements](#6-retroachievements)
7. [Scrapers and their terms and limits](#7-scrapers-and-their-terms-and-limits)
8. [Dependency versions](#8-dependency-versions)
9. [Open questions](#9-open-questions)

## 1. Frontends studied

### iiSU (reference for quality only)

iiSU is a closed-source Android frontend (package `com.iisulauncher`, latest Alpha 0.0.7.4 on
2026-07-31). Its FAQ says the Android app "is not currently planned to be open source". It is praised
for presentation and motion ("the prettiest out-of-box presentation on Android", Held Games,
2026-07; "one of the most visually pleasing emulator frontends on Android", Retro Dodo, 2026-06) and
for its dual-screen mode (Steam Deck HQ). Reviewers also note that it is overwhelming at first and
unstable as an alpha, including settings resets while it is the Home app.

**Fuse studied iiSU only as a quality bar. No code, assets, wording or layouts were taken from it,
and none were taken from Sony, Microsoft or Nintendo either.** The note records what iiSU looks like
(light default, dot grid, glass pills, gradient focus ring, domino cascades, washed-out hero art) and
the decisions Fuse took to be its own thing:

1. Dark-first, cinematic canvas: the focused game's art lights the room instead of being a washed-out
   wallpaper. No dot grid, no gradient ring.
2. Focus grammar: squircle tiles that lift, with a crisp light edge and a bar underneath that reads
   without colour. Spring physics without bounce.
3. Motion: parallax depth and crossfades that never pass through black or white. No cascades.
4. Status: one edge-hugging HUD line with tabular numerals, no pills or keycaps.
5. Hints: one quiet line of original button glyphs at the bottom right.
6. Sound: short procedural tones, silent when focus cannot move.

Sources: [iisu.network](https://iisu.network/),
[iiSU wiki: adding Steam games](https://iisu.network/wiki/adding-steam-games-to-iisu).

### Other frontends

| Frontend | Licence and status | What Fuse took as facts |
|---|---|---|
| [ES-DE](https://es-de.org/) | Desktop MIT; the Android APK is paid and partly closed source | Its Android and Linux `es_find_rules.xml` / `es_systems.xml` are MIT and are the best public record of emulator packages, activities and intent extras (section 4). Directory-as-file layouts, `%INJECT%` id files, Steam and Android app shortcuts |
| [Daijisho](https://github.com/TapiocaFox/Daijishou) | Closed app; the public platform JSON repository has no licence | Used only to cross-check launch intents ("X-DJ" in the notes). Nothing copied |
| [Pegasus](https://github.com/pegasus-frontend/pegasus-frontend) | GPL-3.0 | `metadata.pegasus.txt` is ignored by the scanner, like RomM does |
| Beacon, Cocoon, Console Launcher | Closed | Winlator Cmod and Bannerlator publish `am start` instructions for Beacon; Fuse uses the same intent |
| Mimir | GPL-3.0 | Listed for completeness |

The 2026 baseline every serious frontend has, and which Fuse targets: Home-app mode and an app drawer;
controller-first with touch; ABXY swap and prompt styles; folder-name platform detection; an emulator
database; several scrapers; icon, box, hero, logo, screenshot and video slots; RetroAchievements;
playtime; "jump back in"; several layouts; widgets; themes; sound; dual-screen support; Steam and PC
games through GameNative, GameHub and Winlator shortcuts; onboarding; an updater.

## 2. Android platform constraints

Full detail and API levels: [docs/research/android-platform.md](docs/research/android-platform.md).

**Home role.** `RoleManager.ROLE_HOME` (API 29) needs an enabled MAIN+HOME activity. After one
denial the system dialog offers "Don't ask again", so a launcher must never loop the request; the
fallbacks are `Settings.ACTION_HOME_SETTINGS` and `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`. The
recommended shape is a separate `<activity-alias>` for HOME that is disabled until the user opts in,
toggled with `setComponentEnabledSetting(..., DONT_KILL_APP)`. With targetSdk 36 predictive back no
longer calls `onBackPressed`, so a Home screen must register its own root back callback.
[RoleManager](https://developer.android.com/reference/android/app/role/RoleManager)

**Package visibility.** Being the Home app grants no package visibility. A launcher declares
`<queries>` for MAIN/LAUNCHER intents plus explicit `<package>` entries; `QUERY_ALL_PACKAGES` is not
needed and Google Play restricts it. Manifest receivers for package changes do not fire, so re-check
on cold start.
[Package visibility](https://developer.android.com/training/package-visibility),
[`<queries>`](https://developer.android.com/guide/topics/manifest/queries-element),
[LauncherApps](https://developer.android.com/reference/android/content/pm/LauncherApps)

**Scoped storage.** The Storage Access Framework cannot grant the internal storage root, SD card
roots, `Download/` or `Android/data|obb` (Android 11+). Listing must use one `DocumentsContract` query
per directory; `DocumentFile.listFiles()` is too slow for large libraries. "All files access"
(`MANAGE_EXTERNAL_STORAGE`) is what ES-DE uses, because handing an emulator a FileProvider URI
requires owning file access ("you can't provide access to files you don't own"). It is acceptable for
GitHub and F-Droid distribution. Other apps' `Android/data` folders stay unreadable, which is why the
BIOS checker reports "Unknown" rather than "Missing" there.
[Document provider](https://developer.android.com/guide/topics/providers/document-provider),
[All files access](https://developer.android.com/training/data-storage/manage-all-files),
[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)

**Launching other apps.** `file://` URIs throw `FileUriExposedException`, but raw path strings in
extras are fine. Android 16 "Safer Intents": when launching by explicit component, set an action and
data that match the target's filter. `ActivityOptions.makeCustomAnimation` is ignored across tasks;
`makeScaleUpAnimation` and `makeClipRevealAnimation` are honoured.
[Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-16),
[ActivityOptions](https://developer.android.com/reference/android/app/ActivityOptions)

**Second displays.** `ActivityOptions.setLaunchDisplayId` is API 26;
`ActivityManager.isActivityStartAllowedOnDisplay` is API 29. `Presentation` is not deprecated, but
for dual-screen handhelds (mostly Android 13 to 15) a separate activity on the other display is more
robust. When a display is removed its activities move to the primary display. Compose inside a
`Presentation` needs lifecycle and saved-state owners before `show()`.
[Presentation](https://developer.android.com/reference/android/app/Presentation)

**Status and controls.** Battery comes from the sticky `ACTION_BATTERY_CHANGED`; Wi-Fi from
`ConnectivityManager` callbacks; Bluetooth state needs no runtime permission at targetSdk 36. Apps
cannot toggle Wi-Fi or Bluetooth; they open the system panels. Brightness can only be set for the
app's own window without `WRITE_SETTINGS`.

**Controllers.** `BUTTON_A` is always the south button by position. Treat hat axes and D-pad keys as
the same input. [KeyEvent](https://developer.android.com/reference/android/view/KeyEvent)

**What cannot be read.** There is no public API for another app's frame rate. `/proc/stat` is denied
since Android 8 and `HardwarePropertiesManager` is effectively unavailable. What is available: memory
info, `isLowRamDevice`, thermal status (API 29) and thermal headroom (API 30, at most once per second).
Fuse therefore never shows a metric it cannot measure (`PerformanceMetric` in `core:model`).
[PowerManager](https://developer.android.com/reference/android/os/PowerManager)

**Installing APKs.** `REQUEST_INSTALL_PACKAGES` plus a `PackageInstaller` session; handle
`STATUS_PENDING_USER_ACTION` by starting the returned intent. Android 15+ refuses APKs with
targetSdk below 24. Android developer verification started rolling out regionally on 2026-09-30 and
becomes global on certified devices in 2027.
[PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller),
[Developer verification](https://developer.android.com/developer-verification)

**Secrets.** `androidx.security:security-crypto` is deprecated (1.1.0, 2025-07-30); use Android
Keystore AES-256-GCM directly. [Keystore](https://developer.android.com/privacy-and-security/keystore)

## 3. Linux

The Linux adapter catalog (`core/launch/.../linux/LinuxCatalog.kt`) follows ES-DE's Linux
`es_find_rules.xml` and `es_systems.xml`: each emulator is searched on `$PATH`, then as a Flatpak, then
as an AppImage or portable build in a few home folders (`~/Applications`, `~/.local/bin`,
`~/AppImages`, `~/Downloads`, `~/.local/share/applications`, `~/bin`). No EmuDeck layout is assumed.
Steam games start with `steam -applaunch <appid>` or `steam://rungameid/<appid>`; freedesktop `.desktop`
shortcuts (Steam, Heroic, Lutris and emulator shortcuts) run through `gio launch` when GLib's `gio` is
installed, otherwise from their `Exec=` line with field codes stripped, as ES-DE does. Credentials belong
in the Secret Service ([specification](https://specifications.freedesktop.org/secret-service/)).
Cartridge publishes its status for Fuse in `$XDG_STATE_HOME/cartridge/status.json` (see
[INTEGRATIONS.md](INTEGRATIONS.md)).

## 4. Emulator launch facts

Full per-emulator tables with sources: [docs/research/emulators.md](docs/research/emulators.md).

### The ES-DE model

ES-DE's MIT-licensed Android configuration
([es_find_rules.xml](https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_find_rules.xml),
[es_systems.xml](https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_systems.xml),
[INSTALL.md](https://gitlab.com/es-de/emulationstation-de/-/blob/master/INSTALL.md),
[ANDROID.md](https://gitlab.com/es-de/emulationstation-de/-/blob/master/ANDROID.md))
describes each emulator as ordered `package/activity` pairs (the first installed one wins) and each
launch as an action, category, MIME type, data and typed extras (String, String[], int, boolean) plus
`CLEAR_TASK`, `CLEAR_TOP` and `NO_HISTORY` flags. The research used ES-DE master at `e0ae6ba77d`
(2026-09-16; latest tag v3.4.1). The adapter catalog records the commit it was checked against in
`Sources.ESDE_COMMIT` in `core/launch/.../EmulatorAdapter.kt`.

A ROM reaches an emulator in exactly one of three ways, and each emulator accepts only a specific one:

| Mode | What the emulator receives | Requirement |
|---|---|---|
| Path (`%ROM%`) | `/storage/emulated/0/ROMs/psx/Game.chd` | The emulator has All files access or legacy storage (RetroArch, ePSXe, Citra MMJ, Winlator, aPS3e directory mode) |
| SAF tree document (`%ROMSAF%`) | `content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fpsx/document/primary%3AROMs%2Fpsx%2FGame.chd` | The emulator already holds a persisted grant for that **per-system** folder, which is why users grant `ROMs/<system>` inside each emulator. Shape confirmed from an ES-DE log in [Azahar issue #1484](https://github.com/azahar-emu/azahar/issues/1484) |
| FileProvider (`%ROMPROVIDER%`) | A `content://` URI with a temporary read grant, in the intent data only | The frontend must own file access (All files access). Single files only, so not for `.cue`/`.bin` sets |

Other conventions Fuse follows:

- **Directories interpreted as files.** A folder named with a platform extension (`Game.cue/`,
  `Game.m3u/`, `Game.ps3/`) is one entry. If it contains a same-named file that file is launched,
  otherwise the folder itself (PS3 disc structure).
- **Id files.** A tiny file whose content, not its path, starts the game (ES-DE `%INJECT%`, at most
  4096 bytes): `.steam`, `.epic`, `.gog`, `.amazon`, `.pcgame` hold a store id for GameNative and
  GameHub Lite; `.psvita` a Vita title id; `.ps3` a PS3 serial; `.app` a `package[/activity]` for
  native Android apps.
- **Spoofed package names.** `com.ludashi.benchmark`, `com.tencent.ig`, `com.antutu.ABenchMark` and
  `com.miHoYo.Yuanshen` are each used by several unrelated apps (Winlator forks, GameHub Lite, Eden and
  Citron builds). Always resolve package **and** activity, and check the activity is exported.
- **Typed extras matter.** GameNative `app_id` is an int; Vita3K `AppStartParameters` is a String[];
  DuckStation `resumeState` and GameHub `autoStartGame` are booleans; SAF URIs passed as extras are
  Strings.
- **Actions the app actually checks.** Flycast requires `VIEW`; GameNative requires
  `app.gamenative.LAUNCH_GAME` (or `VIEW` with `gamenative://run`); Eden treats
  `dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG` specially. Elsewhere the action is cosmetic for
  an explicit component, but Fuse mirrors ES-DE's values.

### Representative emulators

| Emulator | Component | ROM hand-off | Confidence |
|---|---|---|---|
| RetroArch | `com.retroarch.aarch64` (also `.ra32`, `com.retroarch`) -> `com.retroarch.browser.retroactivity.RetroActivityFuture` | Extras `ROM` (path), `LIBRETRO` (core path), `CONFIGFILE` | ES-DE + source |
| DuckStation | `com.github.stenzek.duckstation` -> `.EmulationActivity` | `bootPath` = SAF, `resumeState` = false, CLEAR_TASK + CLEAR_TOP | ES-DE + Daijisho (closed source) |
| NetherSX2 | `xyz.aethersx2.android` -> `.EmulationActivity` | `bootPath` = SAF | ES-DE + Daijisho |
| PPSSPP | `org.ppsspp.ppsspp` / `org.ppsspp.ppssppgold` -> `org.ppsspp.ppsspp.PpssppActivity` | `VIEW`, data = SAF | ES-DE + source |
| Dolphin | `org.dolphinemu.dolphinemu` -> `.ui.main.TvMainActivity` | `AutoStartFile` = SAF (the source also takes ClipData and `AutoStartFiles` for multi-disc) | ES-DE + source |
| melonDS | `me.magnum.melonds` -> `.ui.emulator.EmulatorActivity` | `uri` = SAF | ES-DE + source |
| Azahar | `org.azahar_emu.azahar` -> `org.citra.citra_emu.activities.EmulationActivity` | data = SAF | ES-DE + source |
| Eden | `dev.eden.eden_emulator` -> `org.yuzu.yuzu_emu.activities.EmulationActivity` | data = FileProvider; title-id launch through `LAUNCH_WITH_CUSTOM_CONFIG` + `title_id` | ES-DE + source |
| Vita3K | `org.vita3k.emulator` -> `.Emulator` | `AppStartParameters` = `["-r", "<TITLEID>"]` for installed titles | ES-DE + source |
| aPS3e | `aenu.aps3e` -> `aenu.aps3e.EmulatorActivity` | `game_dir` (serial or directory) or `iso_uri` | ES-DE + source |
| Flycast | `com.flycast.emulator` -> `com.flycast.emulator.MainActivity` | `VIEW` (required), data = SAF | ES-DE + source |
| NES.emu, GBA.emu and other EX+ Alpha emulators | `com.explusalpha.*` -> `com.imagine.BaseActivity` | data = FileProvider | ES-DE + source |

**No documented per-game launch.** Mainline Winlator and RPCS3/RPCSX for Android do not export their
game activities; Lemuroid only starts games by its internal database id; Panda3DS opens its own
interface. Fuse opens these apps and says why instead of pretending
(`LaunchPlan.OpenAppOnly`).

**Switch and other installed-content systems.** ES-DE has no way to pass Switch updates or DLC; they
are installed inside the emulator. Fuse reports this per emulator (`ContentSupport` in `core:model`)
rather than trying to install anything.

### PC and Steam launchers on Android

| Launcher | Per-game launch | How |
|---|---|---|
| Winlator Cmod (and PRoot, Glibc, spoofed forks) | Yes | Exported `XServerDisplayActivity`, `-e shortcut_path <absolute path to .desktop>`, CLEAR_TASK + CLEAR_TOP. The `.desktop` comes from Winlator's "Export for Frontend" |
| WinNative | Yes | `com.winlator.cmod.runtime.display.XServerDisplayActivity`, `shortcut_path` |
| Bannerlator | Yes | `com.winlator.star.XServerDisplayActivity`, `shortcut_path` (vendor frontend guide) |
| GameNative | Yes | Action `app.gamenative.LAUNCH_GAME`, int `app_id`, `game_source` = `STEAM`/`EPIC`/`GOG`/`AMAZON`/`CUSTOM_GAME`; id files from GameNative's "Export for frontend" |
| GameHub Lite | Yes | Action `gamehub.lite.LAUNCH_GAME` on the patched `GameDetailActivity`, `steamAppId` / `localGameId`, `autoStartGame` = true |
| Winlator (mainline) | No, open the app | `XServerDisplayActivity` is `exported="false"` |
| GameHub (original) | No, open the app | No documented external launch; the Lite patch has to add the export |
| Winlator Frost | Unverified, open the app | No repository or documentation found |

Sources: [GameNative](https://github.com/utkarshdalal/GameNative),
[GameHub Lite](https://github.com/Producdevity/gamehub-lite),
[Winlator Cmod](https://github.com/coffincolors/winlator),
[Winlator](https://github.com/brunodev85/winlator),
[Bannerlator](https://github.com/The412Banner/Bannerlator).

## 5. RomM folder structures and content categories

Sources: [RomM](https://github.com/rommapp/romm) (AGPL-3.0; master `6226e50`, latest tag 5.3.1),
[RomM docs](https://docs.romm.app/). Fuse uses RomM's naming conventions as facts only and never talks
to a RomM server itself; Cartridge does.

| | Structure A (default) | Structure B |
|---|---|---|
| ROMs | `library/roms/{platform}/{game}` | `library/{platform}/roms/{game}` |
| BIOS | `library/bios/{platform}/` | `library/{platform}/bios/` |

RomM 5.3.0 (2026-09-21) replaced auto-detection with a structure template in `config.yml`; Structure B
is opt-in there. Fuse detects both layouts per folder, and plain ES-DE style `ROMs/<system>` roots as
well.

- **Platforms.** Folder names resolve through RomM slugs (`psx`, `ps2`, `ngc`, `genesis`, `sfam`,
  `tg16`, `win`, ...) and RomM's Batocera/RetroBat/ES-DE alias table (`ps`, `gamecube`, `megadrive`,
  `sfc`, `pcengine`, `windows`, ...). Fuse's platform ids are RomM slugs.
- **Never-game folders.** `@eaDir`, `assets`, `__MACOSX`, `#recycle`, `$RECYCLE.BIN`, `.Trash-*`,
  `.stfolder`, `System Volume Information` and similar, plus the ES-DE/Batocera media folders
  (`images`, `covers`, `videos`, `manuals`, `screenshots`, ...). Hidden folders are never scanned.
- **Ignored files.** Extensions `db tmp bak lock log cache crdownload assembling`; names such as
  `.DS_Store`, `gamelist.xml`, `metadata.pegasus.txt`.
- **Multi-file games and categories.** At the `{game}` level a file is one game and a folder is one
  multi-file game. A folder directly under the game folder whose lower-cased name is a category, or
  its plural with `s` or `es`, holds that category: `game`, `dlc`, `hack`, `manual`, `walkthrough`,
  `patch`, `update`, `mod`, `demo`, `translation`, `prototype`, `cheat`, `soundtrack`, `screenshot`.
  `manual`, `walkthrough`, `soundtrack`, `cheat` and `screenshot` never hold a playable file.
- **Filename tags.** `(...)` and `[...]` groups, comma separated: region codes and names, language
  codes, `Rev`/`v` versions, and provider id tags such as `(igdb-N)` or `(ra-N)`.
- **API.** Client tokens are `Authorization: Bearer rmm_<64 hex>` with scopes. Reference only: Cartridge
  owns the RomM credentials and Fuse never receives them.

## 6. RetroAchievements

Sources: [API docs](https://api-docs.retroachievements.org/) (api-docs `9d0aeb7`),
[rcheevos](https://github.com/RetroAchievements/rcheevos) (MIT, 12.5.0).

- **API.** `https://retroachievements.org/API/API_<Name>.php`, the user's web API key in the `y`
  parameter and the target user in `u` (username or ULID; usernames can change, so store the ULID).
- **Endpoints Fuse uses.** `GetUserProfile`, `GetUserSummary`, `GetUserRecentAchievements`,
  `GetGameInfoAndUserProgress`, `GetUserCompletionProgress` (pages of up to 500),
  `GetUserAwards`, `GetUserRecentlyPlayedGames` (up to 50), `GetGameHashes`, `GetGameList`,
  `GetConsoleIDs`.
- **Rate limits.** "Rate limiting is enabled" with no published numbers, so static data must be
  cached. Fuse's client sends one request at a time, at least 400 ms apart, and the data layer caches
  every response with the TTLs in `RaCachePolicy` (listed in [INTEGRATIONS.md](INTEGRATIONS.md)).
- **Images.** Relative paths are prefixed with `https://media.retroachievements.org`; locked badges
  use the `_lock.png` variant.
- **Hashing.** rcheevos hashes per console: whole-file MD5, header-stripped for NES, SNES, PC Engine,
  Atari 7800 and Lynx, byte-order normalised for N64, the file name for arcade, and custom schemes for
  disc systems, NDS and 3DS. Fuse implements the cartridge-system cases (`RaHasher`) and reports the
  others as unsupported instead of guessing.
- **Fuse only displays achievements.** Emulators unlock them.

## 7. Scrapers and their terms and limits

| Provider | Access | Limits | Terms |
|---|---|---|---|
| [SteamGridDB API v2](https://www.steamgriddb.com/api/v2) | User's API key (`Authorization: Bearer`) from [preferences](https://www.steamgriddb.com/profile/preferences/api) | No published number; Fuse paces 100 ms apart, 4 at a time | [Terms](https://www.steamgriddb.com/terms) |
| [IGDB v4](https://api-docs.igdb.com/) | Twitch client-credentials token from the user's own Client ID and Secret ([Twitch console](https://dev.twitch.tv/console)). A client secret must never ship in a public app | 4 requests per second, 8 concurrent | [Twitch Developer Services Agreement](https://legal.twitch.com/legal/developer-agreement/) |
| [TheGamesDB](https://api.thegamesdb.net/) | User's API key in the `apikey` parameter | Public keys have a small monthly allowance per IP (a forum post cites 1500 per month; unverified); every response reports the remaining allowance | See the API site |
| [ScreenScraper](https://www.screenscraper.fr/webapi2.php) | Developer credentials (`devid`, `devpassword`, `softname`), granted to free software on request, plus an optional user account | Per-user thread and per-minute limits returned in every answer; documented HTTP codes for closed API, quotas and blocked versions | See [screenscraper.fr](https://www.screenscraper.fr/) |
| [libretro thumbnails](https://thumbnails.libretro.com/) | No key. `https://thumbnails.libretro.com/{System}/Named_Boxarts/{Name}.png` (also `Named_Snaps`, `Named_Titles`, `Named_Logos`), with characters that are unsafe in file names replaced by `_` | None published; Fuse paces 100 ms apart | The [repository](https://github.com/libretro-thumbnails/libretro-thumbnails) has no licence file, so images are fetched at runtime and cached on the device, never bundled |

Fuse does not ship ScreenScraper developer credentials. Until official builds inject them from CI
secrets, the provider shows "needs ScreenScraper developer credentials" and makes no request.

## 8. Dependency versions

`gradle/libs.versions.toml` is the single source of versions. It records that every version was
checked against Maven Central, Google Maven and the Gradle Plugin Portal on 2026-09-30. When bumping
a version, check its release notes and update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

| Component | Version |
|---|---|
| Kotlin (Multiplatform, Compose compiler, serialization plugin) | 2.4.20 |
| Android Gradle Plugin | 9.4.1 |
| Compose Multiplatform | 1.12.1 |
| Compose Material 3 (Multiplatform) | 1.9.0 |
| JetBrains Lifecycle | 2.11.0 |
| SQLDelight | 2.4.0 |
| Coil | 3.6.3 |
| Ktor | 3.6.0 |
| kotlinx.coroutines | 1.11.0 |
| kotlinx.serialization | 1.11.0 |
| kotlinx-datetime | 0.8.0 |
| AndroidX Activity | 1.13.0 |
| AndroidX Core | 1.19.1 |
| Media3 | 1.11.1 |
| Gradle (wrapper) | 9.8.0 |
| Android compileSdk / targetSdk / minSdk | 37 / 36 / 28 |

## 9. Open questions

These could not be confirmed from a primary source and are treated as such in the code
(`Confidence.COMMUNITY` or `Confidence.UNVERIFIED`, which only opens the app):

- Kenji-NX and ARMSX2 sources were unreachable; ARMSX1's published activity differs from its public
  repository.
- Closed-source emulators rest on ES-DE plus Daijisho only: DuckStation, NetherSX2, DraStic, Redream,
  Pizza Boy, My Boy!, ePSXe, FPse.
- Not in ES-DE: Citron, Sudachi, yuzu, Suyu, Borked3DS, Winlator Frost and the original GameHub.
  Strato's manifest is verified but its launch behaviour is not.
- How Switch emulators apply updates and DLC on an external launch was not verified from source.
- iiSU internals (closed source).
- TheGamesDB's public-key allowance.
