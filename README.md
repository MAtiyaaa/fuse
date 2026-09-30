# Fuse

A controller-first home for your games. Fuse gathers the games in your folders, dresses them in art,
and starts each one in the emulator or launcher you already use, all from a console-style interface
you can drive with a gamepad.

Fuse runs on Android, where it can also be your Home screen, and on Linux as a first-class desktop
app. It is free software under the GNU GPL, version 3 or later.

**Status: 0.0.1 "The First Update".** This is the first public version and it is early. The shared
core (library scanning, launch resolution, integrations, database) and the interface are in place and
tested where it matters; expect rough edges, missing polish and changes between versions. See
[ROADMAP.md](ROADMAP.md) for what is done and what is not.

## Highlights

- **Your folders, understood.** ES-DE style `ROMs/<system>` folders, RomM Structure A and B
  libraries, single platform folders and folders of Steam or PC shortcuts. 69 platforms are
  recognised by RomM slug, ES-DE name or full name. Multi-disc sets, `.m3u`, `.cue` and `.gdi` files,
  PS3 and Wii U folders, RomM `dlc` and `update` folders, and loose Switch updates are grouped into
  one game each.
- **Read-only, always.** Fuse never moves, renames or deletes your files. Missing games are marked,
  not removed. Playlists for multi-disc games are written to Fuse's own cache.
- **Folder behaviour you control.** Auto, File, Folder as game or Folder browser, set globally, per
  system or per game.
- **Launches that are documented, not guessed.** Emulators and launchers are described as data, each
  with its source (ES-DE's MIT configuration, the emulator's own code or its vendor's guide) and a
  confidence level. When an app has no documented way to start a specific game, Fuse opens it and
  says why. Steam and Windows games start through GameNative, GameHub Lite, Winlator Cmod, WinNative
  or Bannerlator on Android, and through Steam or `.desktop` shortcuts on Linux.
- **Art from where it already is.** Media in ES-DE and Batocera layouts is used first; SteamGridDB,
  IGDB, TheGamesDB, ScreenScraper and libretro thumbnails can fill the rest. Art you choose yourself
  is never replaced. Games without art get generated placeholder art instead of a blank tile.
- **RetroAchievements**, displayed on Home and on each game's page, with careful caching.
- **RomM through Cartridge.** Pair Fuse with the Cartridge app to see downloads, pick up new games
  when you come back, and jump from a game to its RomM entry.
- **A calm, cinematic interface.** A dark room lit by the focused game's art, squircle tiles, a focus
  "spark" that never relies on colour alone, nine themes, four motion levels including Reduced,
  procedural interface sounds, and Home as a flowing dashboard or a board of tiles you arrange.
- **Built for controllers.** Custom key repeat that accelerates while held, hold to reorder, stick
  navigation with deadzones, Nintendo button layout, and hint glyphs for Xbox, Nintendo, PlayStation
  or keyboard.
- **Private by design.** No telemetry, no analytics, no crash reporting.

## Install

Download the latest files from the [Releases page](https://github.com/MAtiyaaa/fuse/releases):

| File | For |
|---|---|
| `Fuse-<version>-android.apk` | Android 9 or newer |
| `Fuse-<version>-x86_64.AppImage` | 64-bit x86 Linux |
| `SHA256SUMS.txt` | Checksums for both |

Check what you downloaded:

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
```

**Android.** Open the APK and allow your browser or file manager to install apps when asked. Fuse
works as a normal app; making it your Home screen is optional and offered during setup. To read
your folders and hand games to emulators that need file paths or FileProvider links, Fuse asks for
All files access, the same access ES-DE uses. Fuse only reads: it never moves, renames or deletes
your files.

**Linux.** Make the AppImage executable and run it:

```sh
chmod +x Fuse-*-x86_64.AppImage
./Fuse-*-x86_64.AppImage
```

If it does not start, your distribution may need the FUSE 2 library (`libfuse2`, or `libfuse2t64` on
newer Ubuntu and Debian). See the
[AppImage FUSE guide](https://docs.appimage.org/user-guide/troubleshooting/fuse.html).

Fuse does not include emulators, BIOS files or games. Install the emulators you want; Fuse finds them
automatically.

## Building

You need JDK 17 or newer and, for Android, the Android SDK with API level 37 installed.

```sh
./gradlew :app:android:assembleDebug   # Android debug APK
./gradlew :app:desktop:run             # run the desktop app
./gradlew allTests                     # every test
```

[BUILDING.md](BUILDING.md) covers tests per module, the AppImage and release signing.

## Cartridge and RomM

Fuse does not talk to RomM servers. [Cartridge](https://github.com/MAtiyaaa/cartridge), a RomM
companion app, signs in to your RomM server, keeps the credentials, and downloads games into
folders on your device. Fuse scans those folders like any other and reads Cartridge's local status to
show what it is doing.

To pair them:

1. Install Cartridge 0.9.10 or newer (Settings, Cartridge in Fuse can install it from Cartridge's
   GitHub releases after you confirm). The bridge is in review as
   [MAtiyaaa/cartridge#29](https://github.com/MAtiyaaa/cartridge/pull/29).
2. Sign in to RomM inside Cartridge and download some games.
3. In Fuse, add the folder Cartridge saves to as a library, if setup did not suggest it already.

On Android, Fuse reads Cartridge's status through a read-only content provider protected by a
Cartridge permission; on Linux it reads `~/.local/state/cartridge/status.json`
(`$XDG_STATE_HOME/cartridge/status.json`). Fuse opens Cartridge on a specific page, platform or game
with `cartridge://` links. No server address, token or password ever passes between the two apps.
The full protocol is in [INTEGRATIONS.md](INTEGRATIONS.md#cartridge-bridge-protocol).

## Privacy

Fuse contains no telemetry, analytics, advertising or crash reporting, and your library works fully
offline. Fuse only contacts a service after you set it up: RetroAchievements, SteamGridDB, IGDB,
TheGamesDB, ScreenScraper and libretro thumbnails receive only what they need to answer (for example
a game's title, or your own API key). The one service used without setup is GitHub, to check for new
versions of Fuse; you can turn automatic checks off in Settings, Updates, and nothing is ever
downloaded or installed without your confirmation. API keys and passwords are kept in the system's
secure storage, never in logs or in the database. [INTEGRATIONS.md](INTEGRATIONS.md) lists exactly
what each service receives and when.

## Documentation

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, domain model, scanning, launching, input, storage, security |
| [INTEGRATIONS.md](INTEGRATIONS.md) | Every online service and the Cartridge bridge, and what leaves the device |
| [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) | Tokens, type, focus, motion, themes, sound, accessibility |
| [RESEARCH.md](RESEARCH.md) | What we learned about frontends, Android, emulators, RomM and scrapers |
| [ROADMAP.md](ROADMAP.md) | What is done and what comes next |
| [BUILDING.md](BUILDING.md) | Building, testing, packaging and signing |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Licences of everything Fuse uses |

## Licence

Copyright (C) 2026 the Fuse contributors.

Fuse is free software: you can redistribute it and/or modify it under the terms of the GNU General
Public License as published by the Free Software Foundation, either version 3 of the License, or (at
your option) any later version (`GPL-3.0-or-later`). See [LICENSE](LICENSE). Fuse is distributed in
the hope that it will be useful, but without any warranty.

Third-party components keep their own licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Credits

- [ES-DE](https://es-de.org/), whose MIT-licensed emulator configuration is the best public record of
  how emulators accept games.
- [RomM](https://github.com/rommapp/romm), whose folder conventions and platform names Fuse follows.
- [Cartridge](https://github.com/MAtiyaaa/cartridge) by abdu2304, the RomM companion Fuse pairs with.
- [rcheevos](https://github.com/RetroAchievements/rcheevos) and
  [RetroAchievements](https://retroachievements.org/).
- [Lucide](https://lucide.dev/) icons, and the [Sora](https://github.com/sora-xor/sora-font) and
  [Manrope](https://github.com/googlefonts/manrope) typefaces.
- Kotlin, Compose Multiplatform, SQLDelight, Ktor, Coil and kotlinx libraries.
- The emulator and launcher developers whose work Fuse starts.

iiSU was studied as a reference for quality only; no code or assets were taken from it, nor from
Sony, Microsoft or Nintendo. Console and game names are trademarks of their owners and are used only
to identify systems. Fuse ships no console artwork, sounds, BIOS files or games.
