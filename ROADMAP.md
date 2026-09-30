# Roadmap

A box is ticked only when the code for it is in this repository. Items marked in progress are being
written for 0.0.1 and carry a `verify` note for the maintainer to tick after merging.

## Phase 0: Foundations

- [x] Kotlin Multiplatform build with convention plugins in `build-logic/` (Android and desktop JVM targets, one version catalog)
- [x] Shared domain model (`core:model`)
- [x] Research on frontends, Android, emulators, RomM, RetroAchievements and scrapers (`docs/research/`, [RESEARCH.md](RESEARCH.md))

## Phase 1: The vertical slice (0.0.1 "The First Update")

The goal of the first release: point Fuse at your games, see them with art, and start them in the
right emulator with a controller, on Android and Linux.

### Library (`core:library`, tested)

- [x] Platform detection for 69 platforms from RomM slugs, RomM aliases, ES-DE system names and full names
- [x] Library roots in ES-DE style, RomM Structure A and RomM Structure B; single platform folders; Steam and PC shortcut folders
- [x] Folder policies Auto, File, Folder as game and Folder browser, inherited Global -> Platform -> Game
- [x] Known folder structures: directory-as-file, PS3, PS Vita, PSP, Wii U, Xbox 360, PC games
- [x] RomM multi-file games with content folders (dlc, update, patch, hack, mod, translation, demo, prototype, manual, and the rest)
- [x] Loose updates and DLC next to base games, including Switch title ids and update versions
- [x] Multi-disc grouping; `.m3u`, `.cue` and `.gdi` parsing; playlist rendering for Fuse's cache
- [x] Filename tags, serials and optional, reversible display-name cleanup
- [x] Local artwork from ES-DE and Batocera media layouts
- [x] Incremental quick scans from remembered folder state; platform and full rescans
- [x] BIOS checks that report Unknown, never Missing, when a location cannot be read

### Launching (`core:launch`, tested)

- [x] Android emulator and launcher catalog as data, with sources and confidence levels
- [x] Linux catalog: `$PATH`, Flatpak and AppImage detection; Steam and `.desktop` shortcuts
- [x] Fork and renamed-build detection by family, with mandatory activity checks for shared package names
- [x] Launch resolution: game, platform and priority choice; title ids; id files; generated playlists; folder support
- [x] "Open app only" with a reason where no per-game launch is documented
- [x] DLC and update guidance per emulator (Fuse never installs content itself)

### Integrations (`core:integrations`, tested)

- [x] RetroAchievements client with cache lifetimes, and rcheevos hashing for cartridge systems
- [x] SteamGridDB, IGDB, TheGamesDB, ScreenScraper and libretro thumbnail clients
- [x] Scrape coordinator, title matcher with visible confidence, and a fill planner that never replaces custom art
- [x] GitHub Releases client with asset selection and SHA-256 digests
- [x] Cartridge bridge protocol: deep links, Android status provider contract, Linux status file
- [x] Per-host request pacing and credential redaction in every error message

### Data (`core:data`, tested)

- [x] SQLDelight schema version 1 with migration verification
- [x] Repositories for games, sources, media, play sessions, collections, apps, caches and title cleanup
- [x] Library indexer that marks missing games instead of deleting them and never overwrites user data
- [x] App settings as a forward and backward compatible document; scoped settings with visible inheritance

### Interface (`ui:designsystem`, `ui:shell`)

- [x] Design system: tokens, squircle tiles, the focus spark, motion profiles, nine themes, generated art, hero backdrop, procedural sounds, Lucide icons, original glyphs
- [x] Input router with repeat, hold to reorder, stick to D-pad and layered handlers; explicit selection models
- [x] Screens: Home (Flow and Channels with 19 widget kinds), Library, Systems, Apps, Cartridge, game page, Search, media manager, folder browser, quick menu, Settings (16 sections), onboarding
- [ ] `DefaultFuseStore`, the store over the core modules (in progress) <!-- verify -->
- [ ] Second-screen companion content (the entry point exists and is empty) <!-- verify -->

### Android app (in progress)

- [ ] Home role with an opt-in alias, root back handling, never looping the role request <!-- verify -->
- [ ] Emulator detection and launching: SAF tree URIs, FileProvider grants, activity checks, launch display <!-- verify -->
- [ ] Status data: battery, Wi-Fi, Bluetooth, displays, performance metrics that can really be measured <!-- verify -->
- [ ] Cartridge status provider client and deep links <!-- verify -->
- [ ] Update and Cartridge installer through `PackageInstaller` with digest verification <!-- verify -->
- [ ] Companion screen on a second display <!-- verify -->
- [ ] Secrets in Android Keystore (AES-256-GCM) <!-- verify -->
- [ ] Interface sounds and video previews <!-- verify -->

### Linux app (in progress)

- [ ] Window modes <!-- verify -->
- [ ] Joystick and gamepad input <!-- verify -->
- [ ] Secrets in the Secret Service <!-- verify -->
- [ ] AppImage packaging (`scripts/build-appimage.sh`) <!-- verify -->

### Project

- [x] Documentation, licence and third-party notices
- [x] CI and release workflows (`.github/workflows/`)
- [ ] First release published by the release workflow <!-- verify -->

## Phase 2: Polish and depth

- [ ] SAF-only storage mode that works without All files access (emulators that need FileProvider URIs would then be limited)
- [ ] Video previews on the desktop
- [ ] R8 minification and resource shrinking for release APKs
- [ ] Screenshot tests for the design system and main screens
- [ ] Tests for `ui:designsystem` input and selection logic; Android host tests (not enabled in the build yet)
- [ ] Localization (all interface text is English today)
- [ ] In-app text size setting
- [ ] TV overscan-safe margins
- [ ] A button remapping screen (the input router already supports remaps)
- [ ] RetroAchievements hashing for disc systems, Nintendo DS/DSi and 3DS
- [ ] ScreenScraper developer credentials injected into official builds from CI secrets
- [ ] A pre-release update channel (the setting exists; update checks only look at stable releases)
- [ ] On-device checks of the launches marked community or unverified (Kenji-NX, ARMSX1, ARMSX2, Strato, Citron, Sudachi, Winlator Frost)
- [ ] Per-emulator configuration editing: opt-in, shown before anything is written, never automatic

## Phase 3: More places to play

- [ ] SteamOS and Game Mode integration

## Not planned

- Cloud sync of the library, settings or saves
- Telemetry, analytics or ads
- Shipping BIOS files, games or console makers' artwork
- Changing emulator configuration automatically
- Emulation inside Fuse: Fuse launches the emulators you install
