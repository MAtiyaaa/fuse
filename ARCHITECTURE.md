# Architecture

Fuse is a Kotlin Multiplatform project. Everything that can be shared (the domain model, library
scanning, launch resolution, provider clients, the database and the whole interface) is common code
that runs on Android and on the desktop JVM. The two apps only supply what the operating system owns:
file access, secure storage, starting other programs, system status and input devices.

Paths below are relative to the module's `src/commonMain/kotlin/io/github/matiyaaa/fuse/...` unless
they start at the repository root.

## Contents

- [Module graph](#module-graph)
- [Module responsibilities](#module-responsibilities)
- [Domain concepts](#domain-concepts)
- [Scanning pipeline](#scanning-pipeline)
- [Folder policies](#folder-policies)
- [Multi-disc games and playlists](#multi-disc-games-and-playlists)
- [Launch resolution](#launch-resolution)
- [Input routing](#input-routing)
- [Selection models](#selection-models)
- [FuseStore and FuseServices](#fusestore-and-fuseservices)
- [Sections per platform: Addons and the Store](#sections-per-platform-addons-and-the-store)
- [Drives](#drives)
- [Health, problems and safe mode](#health-problems-and-safe-mode)
- [Backups](#backups)
- [Search](#search)
- [Emulator files and patches](#emulator-files-and-patches)
- [Database and migrations](#database-and-migrations)
- [Security model](#security-model)
- [Threading](#threading)

## Module graph

```mermaid
flowchart TD
    model[core:model]
    library[core:library]
    launch[core:launch]
    integrations[core:integrations]
    data[core:data]
    transfer[core:transfer]
    romm[core:romm]
    ds[ui:designsystem]
    shell[ui:shell]
    link[ui:link]
    android[app:android]
    desktop[app:desktop]

    model --> library
    model --> integrations
    model --> data
    model --> ds
    library --> launch
    library --> shell
    launch --> shell
    integrations --> shell
    library --> transfer
    transfer --> romm
    data --> romm
    integrations --> romm
    romm --> shell
    data --> shell
    ds --> shell
    shell --> link
    shell --> android
    shell --> desktop
    link --> android
    link --> desktop
```

Read an arrow as "is used by". Dependencies only point down: `core:*` modules know nothing about
Compose, `ui:designsystem` knows nothing about the library or launching, and nothing below the apps
knows which operating system it runs on. The graph is enforced by the `build.gradle.kts` of each
module; `settings.gradle.kts` lists every module (the graph leaves out playback, Jellyfin, Fuse Sync, Fuseline and the player, covered in their own documents). The two app modules depend on `ui:shell` and
implement its `FuseServices` and `PlatformUi` contracts.

Build conventions live in `build-logic/` as precompiled script plugins:

| Plugin | Applied to | What it sets up |
|---|---|---|
| `fuse.kmp.library` | every `core:*` and `ui:*` module | Kotlin Multiplatform with an Android target (AGP 9 KMP library plugin) and a desktop JVM target named `desktop`, JVM 17, coroutines, `kotlin("test")` for `commonTest` |
| `fuse.kmp.compose` | `ui:designsystem`, `ui:shell` | Compose Multiplatform runtime, foundation, UI, animation and resources on top of the above |
| `fuse.serialization`, `fuse.sqldelight` | modules that need them | kotlinx.serialization and SQLDelight plugins |
| `fuse.android.application` | `app:android` | Application id `io.github.matiyaaa.fuse`, compile/target/min SDK from the version catalog, `versionName` and `versionCode` from `gradle.properties` |
| `fuse.desktop.application` | `app:desktop` | Kotlin JVM with Compose Desktop, JVM 17 |

Namespaces follow the module path (`io.github.matiyaaa.fuse.core.model` for `:core:model`).

## Module responsibilities

| Module | Responsibility | Key entry points |
|---|---|---|
| `core:model` | Serializable domain types shared by every layer. No logic beyond small derived properties | `Game.kt`, `Platform.kt`, `Launch.kt`, `Media.kt`, `Settings.kt` |
| `core:library` | Reads library folders and turns them into `ScannedGame`s: platform detection, folder interpretation, disc grouping, filename parsing, local media, BIOS checks. Read-only | `scan/LibraryScanner.kt`, `scan/FolderInterpreter.kt`, `PlatformCatalog.kt`, `bios/BiosChecker.kt` |
| `core:launch` | Emulator and launcher catalogs as data, installed-emulator detection helpers, and launch resolution into a `LaunchPlan`. Never starts anything | `LaunchResolver.kt`, `AdapterRegistry.kt`, `android/AndroidEmulatorCatalog.kt`, `linux/LinuxCatalog.kt`, `desktop/WindowsCatalog.kt`, `desktop/MacCatalog.kt` |
| `core:integrations` | HTTP clients for RetroAchievements, SteamGridDB, IGDB, TheGamesDB, ScreenScraper, libretro thumbnails, the Art Book Next system art pack and GitHub Releases; the scrape coordinator and title matcher; the Cartridge bridge protocol | `scrape/ScrapeCoordinator.kt`, `cartridge/CartridgeProtocol.kt`, `FuseHttp.kt` |
| `core:transfer` | One queue for every download and upload Fuse makes: up to five each way at once, kept across restarts, resumed with HTTP ranges, checked before being put in place, waiting for a missing drive by its id and for the network with back-off. Handlers per kind of job (RomM, Jellyfin, offline moves) plug in through `TransferHandler` | `Transfers.kt`, `Scheduler.kt`, `TransferManager.kt`, `RangedDownload.kt` |
| `core:romm` | Fuse RomM, Fuse's native RomM integration: the client (capabilities from RomM's OpenAPI document, device pairing, pairing codes), Local, Remote and Automatic routes, the mirror of the server's library in Fuse's database, matching that never joins games on a look-alike name, placement in the system folders Fuse has, BIOS planning that never replaces a file, and download and chunked upload transfers | `RommClient.kt`, `RommMirror.kt`, `RommRules.kt`, `RommTransfers.kt` |
| `core:data` | SQLDelight database, repositories, the library indexer that reconciles scans, app settings and scoped settings, the `SecretStore` contract | `FuseData.kt`, `repo/LibraryIndexer.kt`, `settings/AppSettings.kt` |
| `ui:designsystem` | Tokens, colours, typography, shapes, motion, theme presets, focus and selection, input routing, components, icons, sounds, hero backdrop and generated art | `theme/`, `components/`, `input/InputRouter.kt` |
| `ui:shell` | Every screen and overlay, navigation, onboarding and settings, written against the `FuseStore` and `PlatformUi` interfaces | `app/FuseApp.kt`, `store/FuseStore.kt`, `store/FuseServices.kt` |
| `ui:link` | Phone Link: a small Ktor (CIO) web server with its JSON and live-updates API over `FuseStore`, sign-in (PBKDF2 password hash, sessions, lockout), the device's screenshots and recordings (`LinkCaptures`: byte ranges, short-lived download links, zips written as they are read) and the phone web app in `src/web`, bundled into `WebAssets.kt` at build time. See [docs/PHONE_LINK.md](docs/PHONE_LINK.md) | `PhoneLinkServer.kt`, `LinkApi.kt`, `LinkAuth.kt`, `LinkCaptures.kt`, `src/web/` |
| `app:android` | Android host: implements `FuseServices` and `PlatformUi` (Home role, emulator launching, status data, Cartridge provider client, installer, companion screen, Keystore secrets) | `app/android/src/main` |
| `app:desktop` | Linux, Windows and macOS host, switching on `DesktopOs` where they differ: implements `FuseServices` and `PlatformUi` (window modes, controllers from `/dev/input` or SDL, Secret Service, DPAPI or Keychain secrets, AppImage, MSI and DMG packaging). No Apps section on any desktop; Cartridge only on Linux | `app/desktop/src/main` |

## Domain concepts

| Concept | Type in code | File |
|---|---|---|
| Game | `Game`, `GameTitles`, `ChildContent`, `Disc`, `FilenameTags`, `GameMetadata`, `PlayStats`, `ExternalLinks` | `core/model/.../model/Game.kt` |
| Platform | `Platform`, `PlatformSummary`, `EmulatorAvailability`; the catalog of 69 platforms | `core/model/.../model/Platform.kt`, `core/library/.../library/PlatformCatalog.kt`, `core/library/.../library/platform/CatalogPlatforms.kt` |
| LibrarySource | `LibrarySource`, `LibrarySourceKind` (`ROMS_ROOT`, `ROMM_LIBRARY`, `PLATFORM_FOLDER`, `SHORTCUTS`) | `core/model/.../model/Library.kt` |
| GameLocation | `GameLocation`, `LocationKind`, `FolderInterpretation` | `core/model/.../model/Game.kt` |
| FolderPolicy | `FolderPolicy`; resolved by `FolderPolicyResolver`; scoped key `ScopedSettings.FolderMode` | `core/model/.../model/Game.kt`, `core/library/.../scan/FolderPolicyResolver.kt`, `core/model/.../model/Settings.kt` |
| MediaSet | `MediaSet`, `MediaItem`, `MediaKind`, `MediaSource`, `MediaOwner`, `MediaFillMode`, `BorderStyle` | `core/model/.../model/Media.kt` |
| LaunchTarget | `LaunchTarget` (`File`, `Directory`, `Playlist`, `TitleId`, `Shortcut`, `App`) and `LaunchPlan` (`AndroidIntent`, `Command`, `OpenAppOnly`, `Unsupported`) | `core/model/.../model/Launch.kt` |
| EmulatorAdapter | `EmulatorAdapter` interface; `AndroidIntentAdapter`, `NativeAppAdapter`, `LinuxCommandAdapter`, `DesktopShortcutAdapter`; data in `AndroidEmulatorDef` and `LinuxEmulatorDef` | `core/launch/.../launch/EmulatorAdapter.kt`, `core/launch/.../launch/android/`, `core/launch/.../launch/linux/` |
| ScrapeProvider | `ScrapeProviderId` (model) and `ScrapeSource` (one implementation per provider) | `core/model/.../model/Scrape.kt`, `core/integrations/.../integrations/scrape/ScrapeSources.kt` |
| ScrapeCandidate | `ScrapeCandidate`, `ScrapeQuery`, `ArtworkOption`; scored by `TitleMatcher` | `core/model/.../model/Scrape.kt`, `core/integrations/.../integrations/match/TitleMatcher.kt` |
| AchievementState | `AchievementState`, `Achievement`, `AchievementUser`, `RecentAchievement` | `core/model/.../model/Play.kt`, mapping in `core/integrations/.../retroachievements/RaSupport.kt` |
| PlaySession | `PlaySession`, `PlaySessionSource` | `core/model/.../model/Play.kt`, `core/data/.../repo/PlaySessionRepository.kt` |
| Collection | `GameCollection`, `CollectionKind` | `core/model/.../model/Play.kt`, `core/data/.../repo/CollectionRepository.kt` |
| Widget | `WidgetKind` (19 kinds), `WidgetSpan`, `HomeWidget`, `HomeLayoutConfig`, `HomeMode` (`FLOW`, `CHANNELS`) | `core/model/.../model/Home.kt`, `ui/shell/.../home/Widgets.kt` |
| Theme | `ThemeSpec`, `ThemePalette`, `BackgroundStyle`, `CornerFamily`, `FocusStyle`, `MotionProfile`, `SoundProfile` | `core/model/.../model/Theme.kt`, `ui/designsystem/.../theme/ThemePresets.kt` |
| CapabilityProfile | `CapabilityProfile`, `DeviceTier`, `PerformanceProfile`, `RenderQuality` | `core/model/.../model/Device.kt` |
| DisplayProfile | `DisplayProfile`, `DualScreenMode`, `DisplayInfo` | `core/model/.../model/Device.kt` |
| InputProfile | `InputProfile`, `NavAction`, `PadButton`, `GlyphStyle` | `core/model/.../model/Input.kt` |
| CartridgeStatus | `CartridgeStatus`, `CartridgeDownload`, `CartridgeRoute`, `ReleaseInfo` | `core/model/.../model/Cartridge.kt` |

Supporting types: `ScannedGame`, `PlatformFolderScan`, `ScanReport` and the `FolderStateStore`
interface (`Scan.kt`); `BiosRequirement` and `BiosStatus` (`Bios.kt`); `AppEntry` (`Apps.kt`);
`ScopedKey`, `ScopeRef` and `Resolved` (`Settings.kt`).

Two rules hold across the model:

- **A game is a title, not a file.** A Switch folder with its base game, update and DLC is one
  `Game` with `content` children; a four-disc PlayStation game is one `Game` with four `discs`.
- **User data is never derived from files.** `GameTitles` keeps the on-disk, cleaned, metadata and
  custom titles side by side so every rename can be undone, and files are never renamed.

## Scanning pipeline

Everything in this section is in `core:library` and `core:data` and covered by tests.

1. **Discover platform folders** (`LibraryScanner.discover`). A `ROMS_ROOT` or `ROMM_LIBRARY` source
   is inspected for RomM Structure A (`<root>/roms/<platform>`), RomM Structure B
   (`<root>/<platform>/roms`, detected per folder) or an ES-DE style root (`<root>/<system>`). When a
   `roms/` folder exists, other top-level folders only count if they follow Structure B, because
   Structure A keeps `bios/` next to `roms/`. Folder names map to platforms through RomM slugs, RomM's
   alias table, ES-DE system names and loose full names (`PlatformCatalog.resolveFolder`). A
   `PLATFORM_FOLDER` source is one platform; a `SHORTCUTS` source yields Steam and Windows games from
   `.steam`, `.desktop`, `.gog`, `.epic`, `.amazon`, `.pcgame`, `.lnk`, `.exe` and `.bat` files.
   Unknown folders are reported, not guessed.
2. **Skip what did not change** (quick scans). A platform folder is skipped when its modification
   time and those of its direct subfolders match what `FolderStateStore` remembers
   (`FolderStateRepository`, table `folder_state`). A change two or more levels down does not change
   either time and is missed until the next full scan. Folder state is only remembered after a
   complete scan, so an unreadable folder is retried. `ScanScope` is `QUICK`, `PLATFORM` or `FULL`.
3. **Interpret each folder** (`FolderInterpreter`) according to the effective folder policy (next
   section). Walking stops at `ScanOptions.maxDepth` (4 levels) and never enters the same canonical
   directory twice, so symlink loops are safe. Excluded folders (RomM's never-game list, ES-DE and
   Batocera media folders, hidden folders), ignored files (temporary files, saves, `gamelist.xml`,
   `metadata.pegasus.txt`) and BIOS files are skipped (`ScanRules`).
4. **Group files into games** (`DiscGrouper`, `LooseContent`). An `.m3u` owns the files it lists; a
   `.cue` or `.gdi` owns its tracks; `.ccd` owns `.img`/`.sub` and `.mds` owns `.mdf`; sibling files
   that differ only by a disc marker (`(Disc 1)`, `(CD2)`, `(Disk A)`) become one multi-disc game.
   Loose updates and DLC next to a base game (`[UPD]`, `(Update)`, Switch update versions that are
   multiples of 65536, Switch title ids, `[DLC]`, `(Add-On)`) attach to the matching base game; if
   none matches they stay games of their own, so nothing disappears.
5. **Parse names** (`FilenameParser`, `TagTables`, `Serials`, `DisplayNameCleaner`). Regions,
   languages, revision, version, disc number, dump flags and PlayStation-style serials become
   `FilenameTags`. Display-name cleanup is optional (off by default) and reversible. `SearchTitles`
   makes the stricter name art and details are searched by (serials, title ids, versions and
   release groups removed in every common naming style).
6. **Find local media** (`MediaLocator`): type folders inside the platform folder or its `media/` or
   `downloaded_media/` folder, ES-DE's global `downloaded_media/<system>/<type>/` layout, and
   Batocera's `images/<name>-thumb.png` style suffixes. Square box art comes from `squares/` (or
   `square/`) type folders and `-square` suffixes.
7. **Reconcile** (`LibraryIndexer` in `core:data`). Per platform folder, in one transaction: new
   paths are inserted; known paths get only their file-derived columns rewritten; after a complete
   scan, games that were in that folder and are absent now are marked missing (never deleted);
   returning games are un-marked.
   Custom titles, favourites, hidden flags, overrides, links, metadata, media, collections and play
   time are never written by a scan.
8. **Check firmware** (`BiosChecker`). A platform's `BiosRequirement` is checked by name, glob, size
   and optional MD5. When a location cannot be read (another app's `Android/data`, firmware that
   lives inside the emulator) the answer is `UNKNOWN`, never `MISSING`.

### RomM content folders

Inside a multi-file game folder, a folder directly under the game folder whose lower-cased name is a
RomM category, or its plural with `s` or `es`, holds that kind of content (`ContentKind.ofFolder`):

| Holds playable data | `game`, `dlc`, `update`, `patch`, `hack`, `mod`, `translation`, `demo`, `prototype` |
|---|---|
| Never playable | `manual`, `walkthrough`, `cheat`, `soundtrack`, `screenshot` |

Only the first level counts. At the root of a multi-file game, loose patch files (`.ips`, `.bps`,
`.ups`, `.xdelta`, `.ppf`, `.aps`), manuals (`.pdf`, `.cbz`, `.cbr`) and cheats (`.cht`) are attached as
`PATCH`, `MANUAL` and `CHEAT` children. Fuse never installs or applies content; `ContentPlanner` in
`core:launch` tells the user, per emulator, whether it finds content on its own, takes it as a launch
argument, needs it installed from its own menu, or has no known way.

## Folder policies

`FolderPolicy` decides what a folder inside a platform folder is:

| Policy | Behaviour |
|---|---|
| `AUTO` | Fuse decides from the contents, in order: an ES-DE directory-as-file (`Game.cue/`, `Game.ps3/`); a known structure (PS3 `PS3_GAME/` or `USRDIR/EBOOT.BIN`, Vita `sce_sys/param.sfo`, PSP `PSP_GAME/`, Wii U `code/` + `content/`, Xbox 360 `default.xex`, PC games with a `.desktop`, `.exe`, `.bat`, `.com` or `.conf`); an organisational folder holding several titles; one title, possibly with RomM category folders, as a multi-file game; otherwise organisational. On folder-native platforms a folder that yields nothing is kept as one folder game |
| `FILE` | Only files are games; every folder is walked for more files |
| `FOLDER_AS_GAME` | Every top-level folder is one game (the best file inside, else the folder) |
| `FOLDER_BROWSER` | A folder is one entry; opening it shows its files and the user picks what to launch (`FolderBrowserScreen`, `LibraryOps.launchCandidates`) |

Policies inherit **Global -> Platform -> Game**. The global and platform levels are the scoped key
`ScopedSettings.FolderMode` (`folder.policy`); a game's override is `Game.folderPolicyOverride`.
`FolderPolicyResolver.of` builds the lookup the scanner uses from these values (the store passes it
in through `ScanRequest.policies`). It looks up, in order: an override on the folder's
path or its nearest ancestor with one, the platform's policy, the global policy, and finally the
platform's catalog default (`Platform.defaultFolderPolicy`). The same inheritance applies to every
`ScopedKey`, and `Resolved.from` tells the interface where a value came from ("Inherited from
PlayStation 2").

## Multi-disc games and playlists

A multi-disc game keeps its discs in `Game.discs`. At launch, `LaunchResolver.targetFor` decides what
the emulator gets:

- an existing `.m3u` is launched as is;
- otherwise, when the adapter reads playlists (`AdapterCapabilities.playlists`), the scoped setting
  `ScopedSettings.GenerateM3u` is on and a `PlaylistGenerator` is available, Fuse writes a playlist
  with one absolute disc path per line and launches that (`LaunchTarget.Playlist(generated = true)`);
- otherwise disc 1 is launched, and the user can pick another disc.

**Generated playlists are only ever written into Fuse's own cache** (`FuseServices.writeCacheFile`,
whose relative path can never leave `FuseServices.cacheDir`, for example
`playlists/<gameId>/<title>.m3u`), never into a ROM folder.
`M3uWriter` in `core:library` renders playlist text relative to wherever the caller saves it.

## Launch resolution

Launching is data, not code. Each emulator or launcher is one `AndroidEmulatorDef` or
`LinuxEmulatorDef`: package/activity candidates or executable, Flatpak and AppImage names, supported
platforms, and a list of `LaunchMode`s (file by extension, any file, directory, title id, id file),
each with an intent or command template. One generic adapter class per host
(`AndroidIntentAdapter`, `LinuxCommandAdapter`) turns any definition into an `EmulatorAdapter`. There
is no per-emulator switch statement; adding an emulator means adding a definition with its source and
a `Confidence` level (`VERIFIED_ESDE`, `VERIFIED_SOURCE`, `COMMUNITY`, `UNVERIFIED`). Unverified
entries only open the app.

The registry (`AdapterRegistry.Default`) holds 102 Android definitions (`AndroidConsoleDefs` and
`AndroidPcDefs`) plus the native Android app adapter, 35 Linux definitions plus the `.desktop`
shortcut adapter, 32 Windows definitions plus `WindowsShortcutAdapter` (`.exe`, `.lnk`, `.url`,
`.bat`), and 29 macOS definitions plus `MacOpenAdapter` (apps and scripts). Windows and macOS reuse
the Linux definitions with their own ids (`windows.ppsspp`, `macos.ppsspp`) and spell out only what
ES-DE does differently there; the same `LinuxCommandAdapter`, given the host, runs all three.
`EmulatorPriority` carries Linux's order over to each with that host's ids.

Resolution (`LaunchResolver.resolve`):

1. **Choose the emulator**: the game's override, then the platform's choice, then the first installed
   adapter in `EmulatorPriority` order that can really start the game. An adapter that only opens its
   app is used only when nothing else can start the game. A platform choice that cannot open this
   particular game falls through with a note; an explicit game override is honoured even when it
   cannot, and the plan says why.
2. **Resolve the target**: title-id adapters get a `LaunchTarget.TitleId` from an id file or a serial
   in the file name; id files (`.steam`, `.psvita`, `.ps3`, `.app`, ...) pass their content through
   `LaunchOptions.injectedText`; multi-disc games get a playlist as above; folder games follow the
   adapter's `FolderSupport` (`DIRECTORY`, `RESOLVES_FILE`, `NONE`).
3. **Fill the template**: `LaunchTokens` (`{PATH}`, `{ROMDIR}`, `{BASENAME}`, `{PKG}`, `{CORE}`,
   `{SERIAL}`, `{INJECT}`, ...) are filled in common code. The Android-only tokens `{SAF}`,
   `{PROVIDER}`, `{EXTDATA}` and `{INTDATA}` are left for the Android app, which builds the SAF tree
   document URI for the per-system folder or a FileProvider URI with a read grant.
4. **Return a plan**: `LaunchPlan.AndroidIntent` (with the full `AndroidIntentPlan`: typed extras,
   flags, ordered activity candidates, whether the activity must be checked because the package name
   is shared by unrelated apps, and an optional launch display), `LaunchPlan.Command`,
   `LaunchPlan.OpenAppOnly` with a reason, or `LaunchPlan.Unsupported` with a reason. Nothing is
   started in `core:launch`; the app's `GameLauncher` runs the plan.

Detection is separate from resolution: `AndroidEmulatorCatalog.identify` matches an installed package
(and verifies an exported activity) against the catalog, falling back to `AndroidFamilies` for forks
and renamed builds; `LinuxDetector` searches `$PATH`, Flatpak and AppImage folders;
`WindowsDetector` lists every likely parent folder once (Program Files, AppData, each drive, the
ES-DE, RetroBat, EmuDeck and LaunchBox layouts, Scoop, Chocolatey, winget, Steam libraries) and
looks for the programs ES-DE names; `MacDetector` reads app bundles (`CFBundleExecutable`) and
Homebrew folders. On Windows and macOS the user can also locate an emulator and add search folders,
kept in `emulators.json`. Adapters never change emulator configuration. Paths in shared code always
use forward slashes, Windows drives included (`C:/Games/x.iso`, with `C:/` as a root in `FsPath`);
the desktop app turns them back into backslashes only in the arguments a Windows program gets.

## Input routing

All controller, keyboard and remote input goes through `InputRouter` (`ui:designsystem`,
`input/InputRouter.kt`). Platforms feed raw presses and releases of `PadButton`s, stick positions and
trigger values; the router does the rest:

- **Mapping.** Buttons map to `NavAction`s by keycode. `InputProfile.glyphs` only says how the pad
  is labelled (hint glyphs and their positions); `swapConfirmBack` swaps A/B and X/Y for pads that
  report buttons by position. Android handhelds with Nintendo labels already send Nintendo keycodes,
  so they need no swap. User remaps (`InputProfile.remap`) take precedence. System Back arrives as
  `KEY_ESCAPE` through `press`, so capture and the button test see it; `exclusive` takes every press
  while the test runs.
- **Repeat.** Platform auto-repeat is ignored. Directions and page jumps repeat after
  `repeatDelayMs` (280 ms) every `repeatIntervalMs` (70 ms), accelerating by 10% per step after the
  fourth repeat down to half the interval. Repeats are never queued, so releasing stops movement at
  once.
- **Hold to reorder.** When the top layer asks for long presses, holding confirm for `longPressMs`
  (550 ms) sends `REORDER`; releasing earlier is an ordinary `SELECT`. If no layer handles `REORDER`
  it falls back to the options action (`CONTEXT`).
- **Stick to D-pad.** The left stick becomes D-pad presses with a deadzone (0.25) and a navigation
  threshold (0.55), 70% hysteresis on release, and the dominant axis only, so diagonals never
  double-move. Analog triggers count as pressed past half travel.
- **Layers.** Screens register handlers with a priority (`SHELL` 0, `SCREEN` 10, `OVERLAY` 20,
  `DIALOG` 30, `SYSTEM` 40); the newest layer of the highest priority sees an action first, and a
  modal layer stops unhandled actions from reaching the layers below. Each handler returns a
  `NavResult` (`MOVED`, `ACTIVATED`, `CONSUMED`, `BLOCKED`, `IGNORED`) which drives sounds and haptics:
  `BLOCKED` plays a soft bump and never a movement sound.

`InputLayer` is the composable that registers a layer while it is on screen, and
`InputRouter.handleKeyEvent` adapts Compose key events. `lastSource` lets hints follow the input
device without ever hiding the selection.

## Selection models

Selection is explicit state owned by each list or grid, never platform focus, so it survives
recomposition, layout changes and navigation (`ui:designsystem`, `focus/`):

| Model | Used for |
|---|---|
| `LinearSelection` | Rows, columns, menus and settings lists |
| `GridSelection` | Fixed-column grids (cover and icon grids) |
| `ShelfSelection` | Home shelves: each row remembers its own column, like a console dashboard |
| `SpatialSelection` with `packCells` | Channels home: tiles of different spans; moving picks the nearest overlapping cell in that direction, so the same press always lands on the same tile |

At an edge, a model returns `IGNORED` so the parent layer decides (Up on the first row moves to the
section tabs). `FollowSelection` keeps the selected item at a steady anchor while lists scroll, and
jumps without animating through hundreds of rows. The `Navigator` in `ui:shell` keeps per-route state
(selection and scroll anchors, at most 64 routes) so Back returns exactly where you were.

## FuseStore and FuseServices

The interface and the platform meet through three interfaces in `ui:shell`:

- **`FuseStore`** (`store/FuseStore.kt`) is everything the interface reads and does: preferences,
  library, sources and scans, emulators, media, collections, achievements, apps, Cartridge, scoped
  settings, credentials, updates, storage, themes, System health (`HealthOps`) and backups
  (`BackupOps`). Reads are `StateFlow`s or `Flow`s; writes are suspend functions
  or fire-and-forget calls that run on background dispatchers. Screens only talk to this interface.
- **`FuseServices`** (`store/FuseServices.kt`) is what the shared store needs from the operating
  system: the database (`FuseData`), a `FuseFileSystem` (read-only except the Storage screen's delete), the `SecretStore`, the shared
  `HttpClient`, Fuse's cache directory, and the `EmulatorDetector`, `GameLauncher`, `CartridgeBridge`,
  `ReleaseInstaller`, `AppsProvider` and `DeviceLocations` implementations, the `VolumeMonitor` that
  reports mounted drives, ranged binary reads (`FuseFileSystem.readBytes`) for disc images, kept files
  outside the cache (`keepFile`, for restored pictures), and `EmulatorFiles` for the few emulator
  settings Fuse changes on request (desktop only).
- **`PlatformUi`** (`platform/PlatformUi.kt`) is what the interface can ask of the system directly:
  status, displays, performance metrics, sounds, haptics, the Home role, storage access, quick
  controls, video previews, and the system's save and open dialogs (`saveFile`, `openFile`). A missing capability is reported in `PlatformFeatures` and its screens
  are hidden rather than shown broken.

`DefaultFuseStore` composes `core:library`, `core:launch`, `core:integrations` and `core:data` over a
`FuseServices`, so library, launching, scraping, achievements and Cartridge logic are shared by both
apps. `createFuseStore` in `store/StoreFactory.kt` is its entry point. It is tested end to end in
`ui/shell/src/desktopTest`: scanning a temporary library, launching through a fake launcher, play
sessions, playlists written only to the cache, persisted settings, and UI flows driven by controller
presses.

## Sections per platform: Addons and the Store

Where Fuse can install apps (Android, where `FuseServices.packages` is a `PackageBridge`),
`FuseStore.appStore` is the Store and the Cartridge section is **Addons**, holding Cartridge and the
Store as two tabs. Everywhere else `appStore` is `AppStoreOps.None` and the section is Cartridge as it
always was. One class decides this, `app/Sections.kt` (`Sections`, read as `AppState.sections`):
the section's label and icon, whether it has a tab, and whether Settings offers it. The Hud, the tab
list, the quick menu, Settings and the theme preview all ask it, so there are no platform checks
elsewhere. The section keeps its saved identity (`Destination.CARTRIDGE`), so tab order and hidden
tabs carry over.

The Store is in layers, each tested on its own:

- `core:integrations` `obtainium/`: the pack's format, its fetching, and resolving an app's newest
  release and file by the pack's rules (see [INTEGRATIONS.md](INTEGRATIONS.md#the-store-obtainium-emulation-pack)).
- `ui:shell` `store/AppStoreOps.kt`: the Store's state (`StoreState`: catalogue, installed apps,
  releases, jobs) and operations; `store/impl/AppStoreImpl.kt` keeps the catalogue and releases in
  `kv_cache`, what Fuse installed in `AppSettings.store`, and runs every install, update and
  uninstall as a job in the store's scope (one per app, two downloads at once, one Android
  confirmation at a time), so jobs carry on while the user is elsewhere; `ApkDownloader` downloads
  over HTTPS only.
- `PackageBridge` (`store/PackageBridge.kt`): what the system provides. Android's
  `AndroidPackageBridge` reads packages, downloads into `cache/store`, and installs and uninstalls
  through `PackageInstaller`, with `InstallResultReceiver` routing results to the waiting call.
- `ui:shell` `addons/`: Addons' tabs, the Store's shelves, an app's page (`Route.StoreApp`) and the
  first choice of edition.

## Drives

`core:library/storage/Volumes.kt` holds the rules; each app reports its drives through `VolumeMonitor`
(Linux mountinfo with `/dev/disk/by-uuid`, by-label and sysfs; Windows volume serials; macOS
`diskutil`; Android `StorageManager` volumes and media broadcasts). Every `StorageVolume` has a stable
id (`uuid:`, `vsn:`, `android:`), or a weak `mount:` id when the system gives none.

- A library folder remembers its drive as a `VolumeRef` (id, label, kind, its path inside the drive).
  `Volumes.evaluate` gives each folder a `SourceState`: online, offline, moved, another drive in its
  place, folder missing, or no access. Only an online folder is scanned, so games on a drive that is
  out are never marked missing; a scan's results are checked against the drives again before they
  are applied, for a drive pulled mid-scan.
- A drive back under another path is followed: `LibrarySourceRepository.relink` moves every stored
  path of that folder (games, discs, content, local media, folder state) in one transaction, and
  refuses when the new paths are already taken.
- Weak ids never make a readable folder offline, and "another drive" needs both drives removable.

## Health, problems and safe mode

- **`Problem`** (`store/UiModels.kt`) is how anything that went wrong is told: a title, what it
  means, its kind and severity, a reassurance ("nothing was changed"), actions and technical
  details. Launch failures (`LaunchProblems.kt`), System health findings and start failures all use
  it, and `ProblemSheet.kt` draws it.
- **System health** (`store/impl/Health.kt`) follows the store's state for the cheap checks
  (folders and drives, emulators, firmware, keys, missing games, updates) and reads small text files
  after scans for the rest (every disc a playlist names, every track a cue or gdi lists). Unknown is
  never reported as missing.
- **The diagnostics report** (`DiagnosticsReport.kt`) is built only when asked and shown in full
  before it is saved or copied. `redact` replaces the home folder with `~`, user folder names with
  `<user>`, and removes query strings, key and password parameters and bearer tokens.
- **Safe mode** (`app/SafeMode.kt`): `StartupGuard` counts starts that never settle; after three in
  a row the interface starts with Fuse's own theme, reduced motion and no automatic work until the
  user leaves safe mode. Saved settings are never changed by it.

## Backups

`core:data/backup` builds and applies `.fusebackup` files: a zip with `manifest.json`,
`content.json` and the user's own pictures under `media/`. A backup holds the settings document,
scoped settings, each game's own changes, collections, user-chosen media and play sessions, never
games, firmware, secrets or scraped art. Games are named by `GameKey`: their path, their path inside
the library folder, and their file name, so a library on another card still matches; two candidates
are never guessed between. A restore runs in one transaction and merges: values the backup sets win,
values it leaves unset never erase, sessions are never counted twice, and a copy of how settings were
is kept so they can be put back. Rows recording what Fuse changed in emulators' files (`owned.*`) are
left out, since on another device the same setting may be the user's own.

## Search

`core:data/search` parses the query (`SearchSyntax`: words plus `key:value` filters, quotes for
spaces, a filter still being typed), ranks names (`SearchRank`: whole, start, word starts, anywhere,
initials, then one or two typos by edit distance) and runs both over an in-memory index of the
library (`SearchRepository`, kept current while search is open). Filters about things outside a game
(its system, collections and drive) are answered by the store (`SearchStore.kt`), which also turns
filters into chips and offers values for the filter being typed. Settings are found through
`SettingsIndex`, which lists sections and the rows people look for with other words for them, and
the folded group a row sits in, so opening it unfolds that group first.

## Settings

Settings has twelve sections under five headings (Personalize, Games, This device, Connections,
General), listed in `settingsSections` (`ui:shell/settings/SettingsScreen.kt`). Each section's rows
come from functions in `settings/Sections.kt`; a section made of several (Display, Accounts,
Storage and backups, About) brings them in with `under`, which keeps each function the single owner
of its rows and gives their ids a prefix so none repeat. Rarely changed settings fold into groups
(`AppState.group`) whose header always says how they stand ("Default", "3 changed", "1 key
rejected"), and tuning groups end with a way back to the defaults. A section can show a status on
its row in the list (findings, a waiting update, a rejected key). Sections merged in 0.2.0 keep
their old ids in `settingsAliases`, so every older link still lands in the right place.

## Emulator files and patches

Fuse changes an emulator's own files only when the user asks, and only what it can undo exactly.

- **Disc identity** (`core:library/disc`): `PlayStationDisc` reads ISO 9660 images (2048-byte and raw
  2352-byte sectors) for SYSTEM.CNF and the boot program, giving the serial and PCSX2's CRC by
  PCSX2's own rules; `ParamSfo` reads PARAM.SFO for PS3 title ids.
- **PCSX2 patches** (`core:launch/patches`): `Pnach` lists patches as PCSX2 does, `IniText` edits
  PCSX2's per-game settings without disturbing anything else in them, and `Pcsx2PatchRules` decides
  who turned each patch on. Fuse turns patches on with `Enable = name` in
  `gamesettings/SERIAL_CRC.ini` and records each in `OwnedChangesRepository`; it only ever removes a
  line it recorded, and forgets a record as soon as the user removes that line in PCSX2. The desktop
  `EmulatorFiles` writes only inside a PCSX2 data folder it found, atomically, keeping the file as it
  was in Fuse's own data the first time it changes it.
- **Installed content**: `core/library/content` reads packages, archives, licences and CIAs
  (`PsPackages`, `ZipReader`, `Inflate`, `Licences`, `Cia`), and what an emulator holds
  (`InstalledContentReader`). From both, `ContentPlanner` makes a `ContentPlan`: the items in install
  order, each `INSTALLED`, `READY`, `NEEDS_LICENCE`, `SUPERSEDED` or `UNSUPPORTED`, and the game's
  `ContentState`s.

  `DefaultContentOps` (ui/shell) builds each step's command with `EmulatorAdapter.packageInstall`
  (RPCS3 `--headless --installpkg`, Vita3K `--pkg --zrif` or an archive, Azahar `-i`). It runs it
  through `EmulatorFiles.runInstaller` (desktop) and verifies it by reading the emulator's storage
  again. `InstallOnlyFiles` makes an installed package game start by its title id (or, for a 3DS
  `.cia`, from its installed title).

## Database and migrations

`core:data` owns one SQLDelight database, `FuseDatabase`, with its schema in
`core/data/src/commonMain/sqldelight/io/github/matiyaaa/fuse/data/db/*.sq`.

- **Tables** (schema version 3; `migrations/1.sqm` added a game's chosen system, `platform_override`
  next to `platform_scanned`, the app type in `app_override.kind`, and `folder_path` in
  `game_summary`; `migrations/2.sqm` added `library_source.volume_json`, the drive a folder is on): `library_source`, `game`, `game_content`, `game_disc`, `game_genre`,
  `folder_state`, `media`, `play_session`, `game_collection`, `collection_game`, `setting`,
  `app_override`, `kv_cache`, `title_cleanup_history`, and the view `game_summary`.
- **Migrations.** The schema snapshot for each released version is kept in
  `core/data/src/commonMain/sqldelight/databases/<version>.db` and `verifyMigrations` is on, so the
  build fails if a migration does not produce the current schema. A schema change adds a
  `<from>.sqm` migration file next to the `.sq` files and a new snapshot, and needs a test in
  `core/data/src/desktopTest` (see `SchemaTest`).
- **Opening.** On Android, `AndroidDatabase` uses `SQLiteOpenHelper` (a downgrade fails instead of
  wiping data), enables WAL and turns foreign keys on after any migration. On the desktop,
  `DesktopDatabase` tracks the version in `PRAGMA user_version`, creates or migrates inside a
  transaction with foreign keys off, refuses a database written by a newer Fuse, and opens runtime
  connections with foreign keys on, WAL and a 5 second busy timeout.
- **A copy before every upgrade.** Before a database written by an older Fuse is migrated, a
  consistent copy is kept next to it (`fuse.db.before-v<version>.bak`): `VACUUM INTO` on the desktop,
  a copy of the closed file and its write-ahead log on Android. A schema problem is never solved by
  wiping the database.
- **Settings.** Global settings are one JSON document (`AppSettings`, stored at `(GLOBAL, '', 'app')`
  in `setting`) with a `version` field; unknown fields are ignored and unknown enum values fall back
  to defaults, so older and newer versions can read each other's settings. Scoped settings are rows
  in `setting` keyed by scope, scope id and key.
- **Caches.** `kv_cache` stores provider responses with `fetched_at` and `expires_at`; expired
  entries stay readable for offline display until purged.

## Security model

- **Secrets live only in the platform's secure store.** `SecretStore` is implemented with Android
  Keystore backed encryption (AES-256-GCM) on Android, the Secret Service (`secret-tool`) on Linux
  and the login Keychain (`security`, values through stdin) on macOS, falling back to an AES-GCM
  encrypted file readable only by the user when no keyring answers. On Windows that file's key is
  sealed with DPAPI for the signed-in user. The keys are listed in `SecretKeys`: RetroAchievements username and web API key,
  SteamGridDB key, IGDB client id and secret, TheGamesDB key, ScreenScraper user and password.
  Secrets never go into the database, `AppSettings`, logs or exported intents.
- **Secrets cannot leak through strings.** Clients hold keys in `Secret`, whose `toString()` prints
  `***`. Every error message passes through `redact()`, which removes `y=`, `apikey=`,
  `client_secret=`, ScreenScraper credentials, `Authorization` headers, bearer tokens and the caller's
  own key literals. No HTTP logging plugin is installed, because some providers take keys in the URL.
  ScreenScraper media URLs are stored without credentials and re-signed just before download.
- **Fuse RomM's credential is a secret too.** The client token (or, for a server without tokens,
  the sign-in) is stored under `romm.credential` in `SecretStore` and nowhere else: not in the
  database, `AppSettings`, logs, screenshots, diagnostics, crash output or exported settings. RomM
  errors pass through `redact()` like the other clients'. Fuse asks RomM for the narrowest token
  scope that does what was chosen (reading, or reading, uploading and scanning).
- **Cartridge never shares credentials.** `CartridgeStatus` carries no server address, token or
  password; Cartridge's status provider is read-only and protected by its `READ_STATUS`
  permission. Picture files it hands over for RomM's art are Cartridge's own, opened read-only.
- **Launches carry only what the emulator needs**: a path, a SAF URI, a FileProvider URI with a read
  grant for that one launch, or an id.
- **The library is only changed on request.** `FuseFileSystem`'s one write is `delete`, used only by
  Settings, Storage after the user confirms, and only for paths inside the game's own library folder
  (links are removed, never followed; saves next to a game are left). Otherwise Fuse writes only to
  its own directories (`FuseServices.cacheDir`) or to places the user picked. Phone Link has no route
  that deletes.
- **Updates need approval.** Nothing is downloaded or installed until the user confirms; the
  installer verifies the `sha256:` digest GitHub publishes for the asset (`ReleaseInstaller`) and
  discards the download on a mismatch.
- **No telemetry.** There is no analytics, crash reporting or usage tracking code.
  `PrivacySettings.telemetry` exists only so the Privacy screen can say so, and is always false.
- **Licence keys stay with the emulator.** A Vita package's zRIF (found beside the game, made from
  its `.rif`, or pasted) is passed only in Vita3K's arguments, held in memory for that run of Fuse,
  removed from installer output Fuse shows, and never kept, logged or included in a report or backup.
- **Emulator files are changed only on request**, inside the emulator's own data folder, with the
  original kept first; see [Emulator files and patches](#emulator-files-and-patches).

## Threading

- **Coroutines everywhere.** Core APIs are `suspend` functions or `Flow`s. Nothing blocks the main
  thread: repositories switch to `ioDispatcher` (`Dispatchers.IO` on both platforms) and
  `FuseFileSystem` implementations switch to an IO dispatcher internally.
- **Cancellable scans.** The scanner checks for cancellation and yields at every directory listing;
  `scanAsFlow` stops when its collector is cancelled.
- **Pacing, not blocking.** `RateLimiter` suspends callers per host (maximum concurrency plus a minimum
  interval between request starts); each provider client has its own defaults.
- **UI work stays light.** The `InputRouter` runs on the UI scope and its repeats are coroutines that
  are cancelled on release. The hero backdrop debounces fast selection changes (110 ms) and only
  crossfades once the new art has decoded; animated theme backgrounds are throttled to 30 frames per
  second; the clock updates once a minute.
- **Transactions.** A scan's reconciliation of one platform folder is a single database transaction,
  so the library is never half updated.
