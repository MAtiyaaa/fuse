# Fuse 0.0.1 - The First Update

## New

The first public release of Fuse, a controller-first home for your games on Android and Linux. It is
early: expect rough edges, and please report what you find.

- **Your library, understood.** Fuse scans ES-DE style `ROMs/<system>` folders, RomM Structure A and B
  libraries, single platform folders and folders of Steam or PC shortcuts, and recognises 69
  platforms. Multi-disc sets, `.m3u`, `.cue` and `.gdi` files, PS3, PS Vita, PSP, Wii U, Xbox 360 and
  PC game folders, RomM content folders (DLC, updates, patches, hacks, translations and more) and
  loose Switch updates each become one game.
- **Folder behaviour you choose**: Auto, File, Folder as game or Folder browser, globally, per system
  or per game. Quick rescans only look at folders that changed.
- **Read-only.** Fuse never moves, renames or deletes your files. Games that disappear are marked
  missing, not removed, and multi-disc playlists are written to Fuse's own storage.
- **Launching.** More than 100 Android emulator and launcher definitions and 35 Linux emulators, each
  with its source. Pick an emulator per system or per game. When an app has no documented way to start
  a specific game, Fuse opens it and tells you why. Fuse explains, per emulator, how DLC and updates
  are used.
- **Steam and Windows games** through GameNative, GameHub Lite, Winlator Cmod, WinNative and
  Bannerlator on Android, and through Steam and `.desktop` shortcuts on Linux.
- **Art and details.** Artwork already in ES-DE and Batocera layouts is used first; SteamGridDB, IGDB,
  TheGamesDB and libretro thumbnails can fill the rest with your own keys (ScreenScraper waits for
  developer credentials). Uncertain matches are shown to you before anything is saved, art you pick
  yourself is never replaced, and games without art get generated placeholder art.
- **RetroAchievements**: your profile, recent unlocks and per-game progress, cached so the service is
  not asked more than needed.
- **RomM through Cartridge**: pair Fuse with Cartridge 0.9.10 or newer to see downloads, pick up new
  games when you come back, and open a game's RomM entry.
- **Interface.** Home as a flowing dashboard or a board of tiles you arrange, with 19 kinds of
  widgets; library layouts from icons to covers to lists; Systems, Apps, Search, game pages, a quick
  menu and a guided setup. Nine themes, four motion levels including Reduced, High contrast focus, and
  interface sounds synthesised on the fly.
- **Controllers first**: key repeat that speeds up while held, hold to reorder, stick navigation with
  a deadzone, Nintendo button layout, and hint glyphs for Xbox, Nintendo, PlayStation or keyboard.
- **Android**: optional Home screen mode, an app drawer, a second-screen companion, and in-app updates
  checked against their SHA-256 digest before installing <!-- verify -->.
- **Linux**: a single AppImage, window modes, gamepad input, and keys kept in the Secret Service
  <!-- verify -->.
- **Private**: no telemetry, analytics or crash reporting.

Downloads: `Fuse-0.0.1-android.apk` (Android 9 or newer), `Fuse-0.0.1-x86_64.AppImage` (64-bit x86
Linux) and `SHA256SUMS.txt`.

## Changed

First release, nothing changed yet.

## Fixed

First release, nothing fixed yet.
