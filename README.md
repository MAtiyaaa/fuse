<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/banner-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/brand/banner-light.svg">
  <img src="docs/assets/brand/banner-dark.svg" width="100%" alt="Fuse. A controller-first home for your games. Android, Linux, Windows, macOS, free software.">
</picture>

<h3>Point it at your folders. Pick up a controller. Play.</h3>

Fuse gathers the games in your folders, dresses them in art and starts each one in the emulator you
already use, all from a console-style interface made for a gamepad. On Android it can even be your
Home screen.

<a href="https://github.com/MAtiyaaa/fuse/releases/latest"><img src="https://img.shields.io/github/v/release/MAtiyaaa/fuse?display_name=release&style=flat-square&color=FF6A3D&labelColor=15171C&label=release" alt="The latest release"></a>
<a href="https://matiyaaa.github.io/fuse/"><img src="https://img.shields.io/badge/website-matiyaaa.github.io%2Ffuse-2B303B?style=flat-square&labelColor=15171C" alt="Website: matiyaaa.github.io/fuse"></a>
<img src="https://img.shields.io/badge/runs%20on-Android%209%2B%20%C2%B7%20Linux%20%C2%B7%20Windows%20%C2%B7%20macOS-2B303B?style=flat-square&labelColor=15171C" alt="Runs on Android 9 or newer, Linux, Windows and macOS">
<a href="LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0--or--later-2B303B?style=flat-square&labelColor=15171C" alt="Licence: GPL-3.0-or-later"></a>
<a href="#privacy"><img src="https://img.shields.io/badge/telemetry-none-2B303B?style=flat-square&labelColor=15171C" alt="Telemetry: none"></a>

<br>

<a href="https://github.com/MAtiyaaa/fuse/releases/latest"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/button-android-dark.svg"><img src="docs/assets/brand/button-android-light.svg" height="48" alt="Download for Android"></picture></a>
&nbsp;
<a href="https://github.com/MAtiyaaa/fuse/releases/latest"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/button-linux-dark.svg"><img src="docs/assets/brand/button-linux-light.svg" height="48" alt="Download for Linux"></picture></a>
<br>
<a href="https://github.com/MAtiyaaa/fuse/releases/latest"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/button-windows-dark.svg"><img src="docs/assets/brand/button-windows-light.svg" height="48" alt="Download for Windows"></picture></a>
&nbsp;
<a href="https://github.com/MAtiyaaa/fuse/releases/latest"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/button-macos-dark.svg"><img src="docs/assets/brand/button-macos-light.svg" height="48" alt="Download for macOS"></picture></a>

<sub>Android 9 or newer &nbsp;·&nbsp; 64-bit x86 Linux AppImage &nbsp;·&nbsp; Windows 10 and 11 &nbsp;·&nbsp; macOS on Apple silicon and Intel &nbsp;·&nbsp; <a href="#install">checksums and install notes</a></sub><br>
<sub>0.1.4 is the Gallery Update: download your screenshots and recordings from your phone, a bottom screen that stays while you play, and systems that stay where you drop them. <a href="#where-fuse-stands">Here is where it stands.</a></sub><br>
<sub>Or download from <a href="https://matiyaaa.github.io/fuse/">Fuse's website</a>, which always offers the latest version for your system.</sub>

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

<a href="docs/assets/screenshots/home.webp"><img src="docs/assets/screenshots/home.webp" width="100%" alt="Fuse's Home screen in the Fuse theme. The Legend of Zelda: Breath of the Wild is in focus, told large with its year, play time, update and DLC. Below, a Continue playing row with Final Fantasy VII, Super Mario 64 and Metroid Prime over their box art, and a row of systems with their logos and artwork."></a>

<sub><b>Home.</b> Continue where you left off, with your systems one row below.</sub>

</div>

<table>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/library-covers.webp"><img src="docs/assets/screenshots/library-covers.webp" width="100%" alt="The Library as a wall of tall covers of well-known games, Advance Wars in focus, with tabs for every game, favourites and recently played."></a>
      <br><b>Your whole library</b>
      <br><sub>Covers, square box art, capsules or a compact list, filtered by system, favourites or what you played last.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/library-icons.webp"><img src="docs/assets/screenshots/library-icons.webp" width="100%" alt="The Library as a grid of square box art, Crazy Taxi in focus with its art filling the room, next to Chrono Trigger, Crash Bandicoot and Donkey Kong Country."></a>
      <br><b>Art that fills itself in</b>
      <br><sub>Box art, covers, logos and backgrounds for every game, found for you, and art of Fuse's own for any it can't find.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/game.webp"><img src="docs/assets/screenshots/game.webp" width="100%" alt="The page of The Legend of Zelda: Breath of the Wild: its year, developer, genres and players, a Play button that says it starts in Ryujinx, its play time and what it's about."></a>
      <br><b>A page for every game</b>
      <br><sub>Its details, the emulator Fuse will start, your play time, and its discs, updates and DLC kept together.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/systems.webp"><img src="docs/assets/screenshots/systems.webp" width="100%" alt="The Systems screen: every system with its own logo and artwork, Nintendo Switch in focus with its game count and its emulator, Ryujinx."></a>
      <br><b>Systems, with art of their own</b>
      <br><sub>Logos, artwork and colours for every system, and the emulator behind each one, per system or per game.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/home-channels.webp"><img src="docs/assets/screenshots/home-channels.webp" width="100%" alt="Home as a board of tiles: Continue playing, Favourites, Systems with their logos, this week's play time as a bar chart, total play time and New in your library."></a>
      <br><b>Or a board of tiles you arrange</b>
      <br><sub>Channels turns Home into widgets you place and resize yourself, from 19 kinds.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/onboarding.webp"><img src="docs/assets/screenshots/onboarding.webp" width="100%" alt="The first step of setup: Welcome to Fuse, a Begin button, and the Fuse mark."></a>
      <br><b>A guided start</b>
      <br><sub>Setup walks you through folders, emulators and, on Android, the optional Home screen.</sub>
    </td>
  </tr>
</table>

<p align="center"><sub>Every screenshot is a real render of Fuse's interface (the desktop build at 1920 x 1080), driven with
controller input over a library of well-known games whose art Fuse found by itself: box art from the
<a href="https://github.com/libretro-thumbnails/libretro-thumbnails">libretro thumbnails</a>, system art from <a href="https://github.com/anthonycaccese/art-book-next-es-de">Art Book Next</a>.
Games with no art there (the Switch games here) show Fuse's own. The games, their names and their art belong to their owners.
Fuse comes with no games and is not affiliated with them.</sub></p>

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
      <sub>On two-screen devices (Android 10 and later) the companion shows the focused game or system, or the game you are playing and its session time, and stays beside games and apps you open.</sub>
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/phone-link.svg" width="44" height="44" alt=""><br>
      <b>Phone Link</b><br>
      <sub>Manage your library from a phone on the same Wi-Fi: see what's playing and downloading, fix names, details and art, start art fills, and download your screenshots and recordings. Signed in with a password you set on the device; a phone can't delete anything or see keys.</sub>
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
      <b>Ten themes, and yours</b><br>
      <sub>Fuse, Glass, Starlight, Crossbar, Orbital, Wave, Blades, Channels, CRT and Daylight, each with its own background, corners, focus, motion and sound. Add themes others made from a link or a file, or <a href="docs/THEMES.md">write your own</a>.</sub>
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
- **Android games** in their own system: any installed app can be a game, an app or an emulator, and
  games get box art, details and play time like the rest.
- **A game in the wrong system** moves to the right one from its options, and stays there.
- **Add a game from anywhere**: an installed app, an APK file, or a game file outside your library
  folders, picked with Fuse's own file picker.
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
  more), **35 on Linux**, found on `$PATH`, as Flatpaks or as AppImages (RetroArch, PCSX2, RPCS3,
  Dolphin, Cemu, Ryujinx, xemu, Xenia, shadPS4, MAME, DOSBox Staging and more), **32 on Windows**,
  found in portable folders, Program Files, AppData, Scoop, Chocolatey, winget and Steam libraries,
  and **29 on macOS**, found as apps or Homebrew programs. Anything Fuse misses on Windows or macOS
  can be located from Settings.
- **Pick an emulator** per system or per game. Forks and renamed builds are recognised by family, and
  shared package names are trusted only after Fuse checks that the expected activity exists.
- **Honest fallbacks.** When an app has no documented way to start a specific game, Fuse opens the app
  and says why.
- **Steam and Windows games** start through GameNative, GameHub Lite, Winlator Cmod, WinNative or
  Bannerlator on Android, through Steam or `.desktop` shortcuts on Linux, and as they are on Windows
  (`.exe` games, `.lnk` shortcuts and `.bat` scripts).
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
- **Found however the file is named**: No-Intro, Redump, GoodTools, TOSEC and scene names, serials
  and title ids anywhere in the name, list numbers and versions; exact names always win.
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
- **Library** layouts from square box art to capsules to covers to a compact list; **Systems**, **Apps**
  (on Android), **Search**, game pages, a media manager, a folder browser, the **quick menu** and a
  guided setup.
- **Ten themes** and community themes from a link or a file ([docs/THEMES.md](docs/THEMES.md)), square
  box art or tall posters, four motion levels including Reduced, High contrast focus, optional glass
  panels and CRT effect, and interface sounds synthesised on the fly.
- A **performance overlay** that only shows metrics the device can really measure.
- **Screenshots and recordings** of Fuse on Android: from the quick menu after a 3 second countdown,
  or at once with L3 + R3 (hold to record), saved to Pictures/Fuse and Movies/Fuse with Fuse's music.

</details>

<details>
<summary><b>Android, Linux, Windows and macOS</b></summary>
<br>

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/android.svg" width="40" height="40" alt=""><br>
      <b>Android</b> <sub>9 or newer</sub>
      <ul>
        <li>Optional Home screen mode and an app drawer, with Fuse kept in recent apps</li>
        <li>A companion screen on a second display (Android 10 and later)</li>
        <li>In-app updates, downloaded only after you confirm and checked against their SHA-256 digest</li>
        <li>Keys and passwords kept in the Android Keystore</li>
        <li>Interface sounds and haptics</li>
        <li>Screenshots and screen recordings of Fuse</li>
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
  <tr>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/windows.svg" width="40" height="40" alt=""><br>
      <b>Windows</b> <sub>10 and 11, 64-bit</sub>
      <ul>
        <li>An installer for your account (no administrator needed), or a portable zip that keeps everything next to it</li>
        <li>Controllers through SDL, like most emulators</li>
        <li>Keys protected by Windows for your account (DPAPI)</li>
        <li><code>.exe</code> games, <code>.lnk</code> shortcuts and <code>.bat</code> scripts next to your emulators</li>
        <li>Emulators you keep anywhere: show Fuse where with Locate</li>
      </ul>
    </td>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/macos.svg" width="40" height="40" alt=""><br>
      <b>macOS</b> <sub>Apple silicon (11 or newer) and Intel (10.15 or newer)</sub>
      <ul>
        <li>A disk image for each kind of Mac</li>
        <li>Controllers through SDL, like most emulators</li>
        <li>Keys kept in the Keychain</li>
        <li>Emulators as apps or Homebrew programs, found in Applications and the folders inside it</li>
        <li>An optional login item</li>
      </ul>
    </td>
  </tr>
</table>

On every computer Fuse manages games only: there is no Apps section, and Cartridge, an Android and
Linux app, isn't offered on Windows and macOS.

</details>

## Where Fuse stands

> [!IMPORTANT]
> **Fuse 0.1.4 "The Gallery Update" is still early.** It lets you download your screenshots and
> recordings from your phone, keeps the bottom screen beside your games, and fixes systems that
> moved back after a drag (see [the release notes](docs/releases/0.1.4.md)); 0.1.3 brought
> screenshots and recordings, 0.1.2 moving things by touch, Fuse's own look, community themes and a website, 0.1.1
> redesigned the bottom screen of dual-screen handhelds, and 0.1.0 brought Fuse to Windows and macOS. The
> shared core (library scanning, launch resolution, integrations and the database) and the interface
> are in place and tested where it matters. The Android app builds and passes its unit tests and lint, and has been tried on one
> handheld so far. The Linux app builds, passes its tests, packages as an AppImage and starts in a
> virtual display. The Windows and macOS builds pass their tests and a self-test of the packaged app
> on each system in CI, but haven't been tried by hand on a PC or Mac yet. Expect rough edges and
> changes between versions.

Not there yet: checks on more handhelds and on Windows and Mac computers, signed Windows and macOS
builds, video previews and screen capture on the desktop, a storage mode without All files access, translations (the
interface is English only), RetroAchievements hashing for 3DS, Saturn, Dreamcast and compressed disc
images, and ScreenScraper credentials. [ROADMAP.md](ROADMAP.md) ticks a box only when the
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
| `Fuse-<version>-windows-x64.msi` | Windows 10 and 11, 64-bit (installer) |
| `Fuse-<version>-windows-x64.zip` | Windows 10 and 11, 64-bit (portable) |
| `Fuse-<version>-macos-arm64.dmg` | Macs with Apple silicon, macOS 11 or newer |
| `Fuse-<version>-macos-x64.dmg` | Intel Macs, macOS 10.15 or newer |
| `SHA256SUMS.txt` | Checksums for all of them |

The APK is published first; the computer builds join the release a little later.

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
  <tr>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/line/windows.svg" width="20" height="20" alt="">&nbsp;<b>Windows</b>
      <p>Run the <code>.msi</code>: it installs Fuse for your account, with no administrator prompt, and a later version installs over it. Or unzip the portable <code>.zip</code> anywhere (a USB drive works) and run <code>Fuse.exe</code>; it keeps its library and settings in the <code>FuseData</code> folder beside it.</p>
      <p>Fuse isn't signed by Microsoft yet, so SmartScreen may warn the first time: choose <b>More info</b>, then <b>Run anyway</b>.</p>
    </td>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/line/macos.svg" width="20" height="20" alt="">&nbsp;<b>macOS</b>
      <p>Open the disk image for your Mac (<code>arm64</code> for Apple silicon, <code>x64</code> for Intel) and drag Fuse to Applications.</p>
      <p>Fuse isn't notarized by Apple yet. The first time, right-click Fuse and choose <b>Open</b>, or allow it in System Settings, Privacy &amp; Security. From Terminal, this does the same:</p>
      <pre><code>xattr -dr com.apple.quarantine /Applications/Fuse.app</code></pre>
    </td>
  </tr>
</table>

Fuse does not include emulators, BIOS files or games. Install the emulators you want; Fuse finds them
automatically.

## RomM through Cartridge

Fuse does not talk to RomM servers. [Cartridge](https://github.com/abdu2304/cartridge) by
[abdu2304](https://github.com/abdu2304), a RomM companion app, signs in to your RomM server, keeps
the credentials, and downloads games into folders on your device. Fuse scans those folders like any other and reads Cartridge's local status to show
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
   GitHub releases after you confirm). RomM's details, each game's download progress and uploading
   games to RomM need Cartridge 0.9.11 "The Bridge Expansion", with bridge protocols 2 and 3 (in
   review as [MAtiyaaa/cartridge#30](https://github.com/MAtiyaaa/cartridge/pull/30)).
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
| [docs/THEMES.md](docs/THEMES.md) | Writing, sharing and adding themes |
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
- **[Cartridge](https://github.com/abdu2304/cartridge) by [abdu2304](https://github.com/abdu2304)**
  (MIT), the RomM companion Fuse pairs with, and where Fuse learned to match emulator forks by name.
  Fuse's bridge (live downloads, deep links and uploads) is being contributed to Cartridge; until
  it is merged there, Fuse installs Cartridge from the [MAtiyaaa/cartridge](https://github.com/MAtiyaaa/cartridge)
  fork, and will switch to abdu2304's releases once it is.
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
