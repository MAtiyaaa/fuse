<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/banner-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/brand/banner-light.svg">
  <img src="docs/assets/brand/banner-dark.svg" width="100%" alt="Fuse. A controller-first home for your games. Android, Linux, free software.">
</picture>

<h3>Point it at your folders. Pick up a controller. Play.</h3>

Fuse gathers the games in your folders, dresses them in art and starts each one in the emulator you
already use, all from a console-style interface made for a gamepad. On Android it can even be your
Home screen.

<a href="https://github.com/MAtiyaaa/fuse/releases"><img src="https://img.shields.io/badge/release-0.0.5%20The%20Box%20Art%20Update-FF6A3D?style=flat-square&labelColor=15171C" alt="Release 0.0.5, The Box Art Update"></a>
<img src="https://img.shields.io/badge/runs%20on-Android%209%2B%20%C2%B7%20Linux-2B303B?style=flat-square&labelColor=15171C" alt="Runs on Android 9 or newer and Linux">
<a href="LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0--or--later-2B303B?style=flat-square&labelColor=15171C" alt="Licence: GPL-3.0-or-later"></a>
<a href="#privacy"><img src="https://img.shields.io/badge/telemetry-none-2B303B?style=flat-square&labelColor=15171C" alt="Telemetry: none"></a>

<br>

<a href="https://github.com/MAtiyaaa/fuse/releases/latest"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/button-android-dark.svg"><img src="docs/assets/brand/button-android-light.svg" height="48" alt="Download for Android"></picture></a>
&nbsp;
<a href="https://github.com/MAtiyaaa/fuse/releases/latest"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/button-linux-dark.svg"><img src="docs/assets/brand/button-linux-light.svg" height="48" alt="Download for Linux"></picture></a>

<sub>Android 9 or newer &nbsp;·&nbsp; 64-bit x86 Linux AppImage &nbsp;·&nbsp; <a href="#install">checksums and install notes</a></sub><br>
<sub>0.0.5 is an early release, shaped by hands-on tests on a dual-screen Android handheld. <a href="#where-fuse-stands">Here is where it stands.</a></sub>

<br>

<b><a href="#a-dark-room-lit-by-the-game-youre-on">Tour</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#everything-in-its-place">Features</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#install">Install</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#romm-through-cartridge">Cartridge</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#build-it-yourself">Build</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#documentation">Docs</a></b>

</div>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/divider-dark.svg">
  <img src="docs/assets/brand/divider-light.svg" width="100%" alt="">
</picture>

<div align="center">

## A dark room, lit by the game you're on

The game you are on sets the mood. Its art fills the room, its colour tints the glow, and the tile
under your thumb lifts toward you with a single sweep of light.

<a href="docs/assets/screenshots/home.png"><img src="docs/assets/screenshots/home.png" width="100%" alt="Fuse's Home screen in the Fuse theme. A dark room tinted amber by the focused game, Velvet Orbit, shown large at the top left with its play time and two discs. Below, a Continue playing row of wide tiles and a row of system tiles."></a>

<sub><b>Home.</b> Continue where you left off, with your systems one row below.</sub>

</div>

<table>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/library-covers.png"><img src="docs/assets/screenshots/library-covers.png" width="100%" alt="The Library as a grid of tall cover tiles, filtered by chips for favourites, recently played and each system."></a>
      <br><b>Your whole library</b>
      <br><sub>Covers, square box art, capsules or a compact list, filtered by system, favourites or what you played last.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/library-icons.png"><img src="docs/assets/screenshots/library-icons.png" width="100%" alt="The Library as a grid of square icon tiles. Games without art show generated placeholder art with their initials and platform colour."></a>
      <br><b>Every tile looks intentional</b>
      <br><sub>Games without art get generated placeholder art from their initials and their platform's colour.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/game.png"><img src="docs/assets/screenshots/game.png" width="100%" alt="A game page: a large Play button that says the game starts in Flycast, an emulator button, favourite and collection buttons, play time, and buttons for Disc 1 and Disc 2."></a>
      <br><b>A page for every game</b>
      <br><sub>Fuse tells you which emulator it will start, keeps your play time and groups the discs of a set.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/systems.png"><img src="docs/assets/screenshots/systems.png" width="100%" alt="The Systems screen in a cool blue light. The focused system shows its game count, its default emulator Dolphin and that two emulators are available."></a>
      <br><b>Systems, and the emulators behind them</b>
      <br><sub>Choose the emulator per system or per game, from the ones installed on your device.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/home-channels.png"><img src="docs/assets/screenshots/home-channels.png" width="100%" alt="Home as a board of tiles: the time and date, Continue playing, Favourites, a Systems count, this week's play time as a bar chart, total play time and New in your library."></a>
      <br><b>Or a board of tiles you arrange</b>
      <br><sub>Channels turns Home into widgets you place and resize yourself, from 19 kinds.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/quick-menu.png"><img src="docs/assets/screenshots/quick-menu.png" width="100%" alt="The quick menu over Home: the time, battery, tiles for Wi-Fi, Bluetooth, Display, Controller, Performance, Low Power, Cartridge, Search and Sound, and brightness and volume sliders."></a>
      <br><b>One button from everything</b>
      <br><sub>Start opens the quick menu: status, brightness, volume, performance and shortcuts.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/settings.png"><img src="docs/assets/screenshots/settings.png" width="100%" alt="Settings, Appearance section: theme, motion, background art, title logos, background dimming, glass panels, CRT effect, high contrast focus and button symbols."></a>
      <br><b>Make it yours</b>
      <br><sub>Nine themes, four motion levels, glass, CRT and high contrast focus, across 16 sections of settings.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/onboarding.png"><img src="docs/assets/screenshots/onboarding.png" width="100%" alt="The first step of setup: Welcome to Fuse, a Begin button, and the Fuse mark with its glowing spark."></a>
      <br><b>A guided start</b>
      <br><sub>Setup walks you through folders, emulators and, on Android, the optional Home screen.</sub>
    </td>
  </tr>
</table>

<p align="center"><sub>Every screenshot is a real render of Fuse's interface (the desktop build at 1920 x 1080), driven with
controller input over a sample library of invented games. The art is Fuse's own generated placeholder art.</sub></p>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/divider-dark.svg">
  <img src="docs/assets/brand/divider-light.svg" width="100%" alt="">
</picture>

<div align="center">

## Everything in its place

Made for the couch, the handheld and the desk. Fuse does the careful work so you only have to press
Play.

</div>

<table>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/controller.svg" width="44" height="44" alt=""><br>
      <b>Made for a controller</b><br>
      <sub>Key repeat that speeds up while held, hold to reorder, stick navigation with a deadzone, and button hints for Xbox, Nintendo, PlayStation or keyboard.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/home.svg" width="44" height="44" alt=""><br>
      <b>Your Home screen, if you like</b><br>
      <sub>On Android, Fuse can be the Home app, with an app drawer next to your games. It is optional and offered during setup.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/folders.svg" width="44" height="44" alt=""><br>
      <b>Your folders, understood</b><br>
      <sub>ES-DE and RomM layouts, 69 platforms, multi-disc sets, and folder behaviour you choose per system or per game.</sub>
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/launch.svg" width="44" height="44" alt=""><br>
      <b>Launches, documented</b><br>
      <sub>More than 100 emulator and launcher definitions on Android and 35 on Linux, each with its source. No documented way in? Fuse opens the app and tells you why.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/read-only.svg" width="44" height="44" alt=""><br>
      <b>Your files, left alone</b><br>
      <sub>Fuse never moves or renames your files, and deletes a game's files only when you ask in Storage and confirm. Games that go missing are marked, not removed.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/artwork.svg" width="44" height="44" alt=""><br>
      <b>Art from where it already is</b><br>
      <sub>ES-DE and Batocera media first, then SteamGridDB, IGDB, TheGamesDB and libretro thumbnails. Art you choose is never replaced.</sub>
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/achievements.svg" width="44" height="44" alt=""><br>
      <b>RetroAchievements</b><br>
      <sub>Your profile, recent unlocks and per-game progress on Home and on each game's page, cached so the service is asked no more than needed.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/cartridge.svg" width="44" height="44" alt=""><br>
      <b>RomM, through Cartridge</b><br>
      <sub>Pair with the Cartridge app to follow each download, pick up new games when you come back, bring in RomM's details and art, and jump to a game's RomM entry.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/second-screen.svg" width="44" height="44" alt=""><br>
      <b>A second screen that helps</b><br>
      <sub>On two-screen devices (Android 10 and later) the companion shows the focused game or system, or the game you are playing and its session time.</sub>
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/phone-link.svg" width="44" height="44" alt=""><br>
      <b>Phone Link</b><br>
      <sub>Manage your library from a phone on the same Wi-Fi: see what's playing and downloading, fix names, details and art, and start art fills. Signed in with a password you set on the device; a phone can't delete anything or see keys.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/storage.svg" width="44" height="44" alt=""><br>
      <b>Storage at a glance</b><br>
      <sub>Each drive as a bar by system, every game by the space it takes, and deleting the games you're done with, after a confirmation that names what goes.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/collections.svg" width="44" height="44" alt=""><br>
      <b>Collections and series</b><br>
      <sub>Your own collections, plus the series Fuse finds on its own from game details and shared titles. Hide one, or keep it as yours.</sub>
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/themes.svg" width="44" height="44" alt=""><br>
      <b>Nine themes</b><br>
      <sub>Fuse, Glass, Crossbar, Orbital, Wave, Blades, Channels, CRT and Daylight. Each sets its own background, corners, focus, motion and sound.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/accessibility.svg" width="44" height="44" alt=""><br>
      <b>Focus you can always see</b><br>
      <sub>The focus spark never relies on colour alone. Four motion levels, including Reduced, and High contrast focus.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/privacy.svg" width="44" height="44" alt=""><br>
      <b>Private by design</b><br>
      <sub>No telemetry, analytics, ads or crash reporting. Online services are used only after you set them up.</sub>
    </td>
  </tr>
</table>

### In detail

<details>
<summary><b>Library and folders</b></summary>
<br>

- **Library roots** in ES-DE style `ROMs/<system>` folders, RomM Structure A and B libraries, single
  platform folders and folders of Steam or PC shortcuts.
- **69 platforms** recognised by RomM slug, RomM alias, ES-DE name or full name.
- **One game, however it is stored.** Multi-disc sets, `.m3u`, `.cue` and `.gdi` files, PS3, PS Vita,
  PSP, Wii U, Xbox 360 and PC game folders, RomM content folders (DLC, updates, patches, hacks,
  translations and more) and loose Switch updates are each grouped into one game.
- **Folder behaviour you control**: Auto, File, Folder as game or Folder browser, set globally, per
  system or per game.
- **Quick rescans** only read the folders that changed.
- **Your files, left alone.** Fuse never moves or renames your files, and deletes a game's files only
  when you ask in Settings, Storage and confirm (never from Phone Link). Missing games are marked,
  not removed, and playlists for multi-disc games are written to Fuse's own cache.
- **BIOS checks** report Unknown, never Missing, when a location cannot be read.

</details>

<details>
<summary><b>Launching</b></summary>
<br>

- **Documented, not guessed.** Emulators and launchers are described as data, each with its source
  (ES-DE's MIT-licensed configuration, the emulator's own code or its vendor's guide) and a
  confidence level.
- **More than 100 definitions on Android** (RetroArch, Dolphin, PPSSPP, DuckStation, NetherSX2,
  ARMSX2, aPS3e, melonDS, Azahar, Eden, Vita3K, Flycast, Lemuroid, MAME4droid, ScummVM and many
  more) and **35 on Linux**, found on `$PATH`, as Flatpaks or as AppImages (RetroArch, PCSX2, RPCS3,
  Dolphin, Cemu, Ryujinx, xemu, Xenia, shadPS4, MAME, DOSBox Staging and more).
- **Pick an emulator** per system or per game. Forks and renamed builds are recognised by family, and
  shared package names are trusted only after Fuse checks that the expected activity exists.
- **Honest fallbacks.** When an app has no documented way to start a specific game, Fuse opens the app
  and says why.
- **Steam and Windows games** start through GameNative, GameHub Lite, Winlator Cmod, WinNative or
  Bannerlator on Android, and through Steam or `.desktop` shortcuts on Linux.
- **DLC and updates**: Fuse explains, per emulator, how they are used. It never installs content
  itself.

</details>

<details>
<summary><b>Art and details</b></summary>
<br>

- Media already in **ES-DE and Batocera** layouts is used first.
- **Square box art** for every game, from SteamGridDB, your own folders or a file; games without it
  show their cover whole instead of cropped.
- **SteamGridDB, IGDB, TheGamesDB and libretro thumbnails** fill the rest, with your own keys where a
  service needs one, by themselves after a scan if you like. When a source runs out of requests,
  the others take over. The ScreenScraper client is built in and waits for developer credentials, so in
  0.0.1 it makes no requests.
- Uncertain matches are **shown to you** before anything is saved.
- **Art you choose yourself is never replaced**, except when you ask to reset it.
- Games without art get **generated placeholder art** instead of a blank tile.

</details>

<details>
<summary><b>Controllers and interface</b></summary>
<br>

- Custom **key repeat** that accelerates while held, **hold to reorder**, **stick navigation** with
  deadzones, **Detect my buttons** (Xbox, Nintendo or PlayStation layout from two presses), and hint
  glyphs for Xbox, Nintendo, PlayStation or keyboard.
- A **button mapping** screen with capture and a live button test that no button can leave by accident.
- **Home** as a flowing dashboard (Flow) or a board of tiles you arrange (Channels), with 19 kinds of
  widgets.
- **Library** layouts from square box art to capsules to covers to a compact list; **Systems**, **Apps**,
  **Search**, game pages, a media manager, a folder browser, the **quick menu** and a guided setup.
- **Nine themes**, four motion levels including Reduced, High contrast focus, optional glass panels
  and CRT effect, and interface sounds synthesised on the fly.
- A **performance overlay** that only shows metrics the device can really measure.

</details>

<details>
<summary><b>Android and Linux</b></summary>
<br>

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/android.svg" width="40" height="40" alt=""><br>
      <b>Android</b> <sub>9 or newer</sub>
      <ul>
        <li>Optional Home screen mode and an app drawer</li>
        <li>A companion screen on a second display (Android 10 and later)</li>
        <li>In-app updates, downloaded only after you confirm and checked against their SHA-256 digest</li>
        <li>Keys and passwords kept in the Android Keystore</li>
        <li>Interface sounds and haptics</li>
      </ul>
    </td>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/linux.svg" width="40" height="40" alt=""><br>
      <b>Linux</b> <sub>64-bit x86</sub>
      <ul>
        <li>A single AppImage, and an optional login entry</li>
        <li>Full screen, borderless or window, from Settings, F11 or the command line</li>
        <li>Gamepad input from <code>/dev/input/js*</code>, with no extra drivers</li>
        <li>Keys kept in the Secret Service, with an encrypted-file fallback</li>
        <li>Steam and <code>.desktop</code> shortcuts next to your emulators</li>
      </ul>
    </td>
  </tr>
</table>

</details>

## Where Fuse stands

> [!IMPORTANT]
> **Fuse 0.0.5 "The Box Art Update" is still early.** It answers the fourth round of tests on a
> dual-screen Android handheld with square box art, menu music, a choice of screen for games and
> apps, uploads to RomM through Cartridge and art that finds itself (see
> [the release notes](docs/releases/0.0.5.md)). The shared core (library scanning, launch resolution,
> integrations and the database) and the interface are in place and tested where it matters. The
> Android app builds and passes its unit tests and lint, and has been tried on one handheld so far.
> The Linux app builds, passes its tests, packages as an AppImage and starts in a virtual display.
> Expect rough edges and changes between versions.

Not there yet: checks on more handhelds, video previews on the desktop, a storage mode without All
files access, translations (the interface is English only), RetroAchievements hashing for disc
systems, DS and 3DS, and ScreenScraper credentials. [ROADMAP.md](ROADMAP.md) ticks a box only when the
code is in this repository.

**Tried it on your device?** A short report ("this emulator launches on my handheld") helps more than
anything right now. [Open an issue](https://github.com/MAtiyaaa/fuse/issues) or see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Install

Download the latest files from the [Releases page](https://github.com/MAtiyaaa/fuse/releases/latest):

| File | For |
|---|---|
| `Fuse-<version>-android.apk` | Android 9 or newer |
| `Fuse-<version>-x86_64.AppImage` | 64-bit x86 Linux |
| `SHA256SUMS.txt` | Checksums for both |

Check what you downloaded:

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
```

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/line/android.svg" width="20" height="20" alt="">&nbsp;<b>Android</b>
      <p>Open the APK and allow your browser or file manager to install apps when asked. Fuse works as a normal app; making it your Home screen is optional and offered during setup.</p>
      <p>To read your folders and hand games to emulators that need file paths or FileProvider links, Fuse asks for All files access, the same access ES-DE uses. Fuse only reads: it never moves, renames or deletes your files.</p>
    </td>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/line/linux.svg" width="20" height="20" alt="">&nbsp;<b>Linux</b>
      <p>Make the AppImage executable and run it:</p>
      <pre><code>chmod +x Fuse-*-x86_64.AppImage
./Fuse-*-x86_64.AppImage</code></pre>
      <p>If it does not start, your distribution may need the FUSE 2 library (<code>libfuse2</code>, or <code>libfuse2t64</code> on newer Ubuntu and Debian). See the <a href="https://docs.appimage.org/user-guide/troubleshooting/fuse.html">AppImage FUSE guide</a>.</p>
    </td>
  </tr>
</table>

Fuse does not include emulators, BIOS files or games. Install the emulators you want; Fuse finds them
automatically.

## RomM through Cartridge

Fuse does not talk to RomM servers. [Cartridge](https://github.com/MAtiyaaa/cartridge), a RomM
companion app, signs in to your RomM server, keeps the credentials, and downloads games into folders
on your device. Fuse scans those folders like any other and reads Cartridge's local status to show
what it is doing.

```mermaid
flowchart LR
    romm[("Your RomM server")] <-->|"sign-in and downloads"| cartridge["Cartridge"]
    cartridge -->|"saves games to"| folders[/"Folders on your device"/]
    folders -->|"scanned, read-only"| fuse["Fuse"]
    cartridge -.->|"local status"| fuse
    fuse -.->|"cartridge:// links"| cartridge
```

To pair them:

1. Install Cartridge 0.9.10 or newer (Settings, Cartridge in Fuse can install it from Cartridge's
   GitHub releases after you confirm). RomM's details and each game's download progress need the
   next Cartridge, with bridge protocol 2 (in review as
   [MAtiyaaa/cartridge#30](https://github.com/MAtiyaaa/cartridge/pull/30)), and uploading games to
   RomM needs protocol 3 ([MAtiyaaa/cartridge#31](https://github.com/MAtiyaaa/cartridge/pull/31)).
2. Sign in to RomM inside Cartridge and download some games.
3. In Fuse, add the folder Cartridge saves to as a library, if setup did not suggest it already.

On Android, Fuse reads Cartridge's status through a read-only content provider protected by a
Cartridge permission; on Linux it reads `~/.local/state/cartridge/status.json`
(`$XDG_STATE_HOME/cartridge/status.json`). Fuse opens Cartridge on a specific page, platform or game
with `cartridge://` links. It can also hand Cartridge a game to upload to your RomM server ("Upload
to RomM" in a game's options): Cartridge shows the files and uploads only after you confirm there.
**No server address, token or password ever passes between the two apps.**
The full protocol is in [INTEGRATIONS.md](INTEGRATIONS.md#cartridge-bridge-protocol).

## Build it yourself

You need JDK 17 or newer and, for Android, the Android SDK with API level 37 installed.

```sh
./gradlew :app:android:assembleDebug   # Android debug APK
./gradlew :app:desktop:run             # run the desktop app
./gradlew allTests                     # every test
```

[BUILDING.md](BUILDING.md) covers tests per module, the AppImage and release signing.

## Privacy

Fuse contains **no telemetry, analytics, advertising or crash reporting**, and your library works
fully offline. Fuse only contacts a service after you set it up: RetroAchievements, SteamGridDB, IGDB,
TheGamesDB, ScreenScraper and libretro thumbnails receive only what they need to answer (for example a
game's title, or your own API key). The one service used without setup is GitHub, to check for new
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
- [Cartridge](https://github.com/abdu2304/cartridge) by abdu2304, the RomM companion Fuse pairs with
  (Fuse installs it from the [MAtiyaaa/cartridge](https://github.com/MAtiyaaa/cartridge) releases).
- [rcheevos](https://github.com/RetroAchievements/rcheevos) and
  [RetroAchievements](https://retroachievements.org/).
- **Music by boipurple.** Fuse's menu music is the album *jam channel* by boipurple: puddleworld
  plays under the menus and alright apothecary during setup, and every song on the album can be
  picked in Settings, Sound. The songs remain the artist's own and are not covered by Fuse's
  licence.
- [Lucide](https://lucide.dev/) icons (ISC), and the [Sora](https://github.com/sora-xor/sora-font) and
  [Manrope](https://github.com/googlefonts/manrope) typefaces (SIL Open Font License).
- Kotlin, Compose Multiplatform, SQLDelight, Ktor, Coil and kotlinx libraries.
- The emulator and launcher developers whose work Fuse starts.

<sub>iiSU was studied as a reference for quality only; no code or assets were taken from it, nor from
Sony, Microsoft or Nintendo. Console and game names are trademarks of their owners and are used only
to identify systems. Fuse ships no console artwork, console sounds, BIOS files or games.</sub>

<br>

<div align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/mark-dark.svg">
  <img src="docs/assets/brand/mark-light.svg" width="56" height="56" alt="The Fuse mark">
</picture>
<br>
<sub>Fuse is free software, made in spare time for everyone who plays with a controller.</sub>
</div>
