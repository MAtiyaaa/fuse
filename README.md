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
<sub>0.4.2.6 is the <b>Native Update</b>: one Save Sync for each device (Fuse One, RomM or Syncthing), Fuse One saves that move even from games started outside Fuse, and a setup that knows when Steam isn't there.</sub><br>
<sub>Or download from <a href="https://matiyaaa.github.io/fuse/">Fuse's website</a>, which always offers the latest version for your system.</sub>

<br>

<b><a href="#a-dark-room-lit-by-the-game-youre-on">Tour</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#made-by-fuse">Made by Fuse</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#everything-in-its-place">Features</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#install">Install</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
<b><a href="#romm-fuses-native-romm-integration">RomM</a></b> &nbsp;&nbsp;·&nbsp;&nbsp;
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

<a href="docs/assets/screenshots/library.webp"><img src="docs/assets/screenshots/library.webp" width="100%" alt="Fuse's Library on an AYN Thor: 2010 FIFA World Cup South Africa is in focus, its logo large over a stadium full of fans that fills the whole screen. Below, rows of square box art: 2014 FIFA World Cup Brazil, Animal Crossing, Assassin's Creed, Balatro, Cooking Mama and Crazy Taxi."></a>

<sub><b>Library.</b> The game you're on fills the room.</sub>

</div>

<table>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/home-channels.webp"><img src="docs/assets/screenshots/home-channels.webp" width="100%" alt="Home as a board of tiles: 14 systems and 49 games, a wide Continue playing tile with Geometry Dash and Cooking Mama, a Systems tile with Steam, PlayStation, PS2 and PS3, New in your library, and Recent achievements."></a>
      <br><b>Home, a board you arrange</b>
      <br><sub>Continue playing, your systems, what's new and your achievements, as tiles you place and resize yourself, or as a flowing Home if you like.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/systems.webp"><img src="docs/assets/screenshots/systems.webp" width="100%" alt="The Systems screen: Steam in focus with 10 games that start in GameNative, then PlayStation, PS2, PS3, PSP, Nintendo 64, DS, 3DS, Game Boy, Game Boy Advance, Switch, Dreamcast, Xbox 360 and Android, each with its own logo and artwork."></a>
      <br><b>Systems, with art of their own</b>
      <br><sub>Logos, artwork and colours for every system, with its game count and the emulator behind it.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/system-page.webp"><img src="docs/assets/screenshots/system-page.webp" width="100%" alt="The Nintendo Switch page: Animal Crossing: New Horizons in focus, its island art filling the screen, next to Mario Kart 8 Deluxe, Red Dead Redemption, Story of Seasons and Super Mario Odyssey."></a>
      <br><b>A page for every system</b>
      <br><sub>Its games over the art of the one you're on, sorted and laid out the way you like for that system.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/apps.webp"><img src="docs/assets/screenshots/apps.webp" width="100%" alt="Apps, Emulators: 15 found on the device, from ARMSX1, ARMSX2, ARMSX3, AzaharPlus, Cemu, Dolphin, DuckStation and Eden to Flycast, GameNative, melonDS, PPSSPP, RetroArch, Vita3K and XenDroid."></a>
      <br><b>Your emulators, found for you</b>
      <br><sub>On Android, every emulator on the device in one place, next to your pinned apps and an app drawer.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/quick-menu.webp"><img src="docs/assets/screenshots/quick-menu.webp" width="100%" alt="The quick menu over Home: the time and date, battery at 80 percent with 4 hours left, and tiles for Wi-Fi, Bluetooth, Screenshot, Display, Controller, Performance, Low Power, Find games and Cartridge."></a>
      <br><b>Everything at a press</b>
      <br><sub>The quick menu: time, battery left, Wi-Fi, screenshots, what is playing and more, from any screen, arranged the way you like.</sub>
    </td>
    <td width="50%" valign="top">
      <a href="docs/assets/screenshots/keyboard.webp"><img src="docs/assets/screenshots/keyboard.webp" width="100%" alt="Search with Fuse's controller keyboard: a field for games, systems and apps above a full keyboard, with hints for type, delete, space, cursor and results on the controller's buttons."></a>
      <br><b>Type with a controller</b>
      <br><sub>A keyboard made for buttons and sticks, with a cursor, shortcuts on every face button, and paste.</sub>
    </td>
  </tr>
</table>

<p align="center"><sub>Screenshots taken with Fuse on an AYN Thor (1920 x 1080), with a real library and the art Fuse filled
in for it; system art from <a href="https://github.com/anthonycaccese/art-book-next-es-de">Art Book Next</a>. The games, their names and their art belong to their owners.
Fuse comes with no games and is not affiliated with them.</sub></p>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/divider-dark.svg">
  <img src="docs/assets/brand/divider-light.svg" width="100%" alt="">
</picture>

<div align="center">

## Made by Fuse

The parts you feel most are Fuse's own: written for Fuse, in this repository, under its licence.
They behave the same on every device, answer to one design, and get better with every release.

</div>

<table>
  <tr>
    <td colspan="2" valign="top">
      <img src="docs/assets/icons/sync.svg" width="56" height="56" alt=""><br>
      <h3>Fuse One <sub>by Fuse</sub></h3>
      Your household in one place: Fuse Profiles, Fuse Save Sync and the Fuse Library. Your saves,
      play time, library, settings and keys, the same on every device you play on, from a computer of
      your own at home (or the Fuse One Host, on its own in Docker). Without a host, each person's
      saves are still kept apart on the device. Stop on the PC, carry on on the handheld; nothing goes
      to anyone else's server, and nothing is ever silently lost.
      <ul>
        <li><b>Saves where you play.</b> The newest save is put in place before a game starts and kept
        after it closes, matched by the game, not the path, in over 60 emulators: RetroArch,
        DuckStation, PCSX2, PPSSPP, Dolphin, melonDS, DraStic, Mupen64Plus, RPCS3, Vita3K, shadPS4,
        Flycast, Azahar for 3DS, Eden and Ryujinx for Switch, Cemu for Wii U, Xenia and more. Save
        Folders shows where each one keeps them, and takes a folder you chose.</li>
        <li><b>Play time that adds up.</b> 30 minutes on one device and 20 offline on another is 50,
        never 30 and never 80. Everything works offline and catches up later.</li>
        <li><b>Synced everywhere, by itself.</b> A save made on one device reaches every other
        device with the game in the background, without launching it, and devices that were off
        catch up when they return. Each game says "Synced everywhere" or "4 of 5 devices current".</li>
        <li><b>Asks, never guesses.</b> When two devices both played, you choose which save to use;
        the other is kept. Every save keeps its versions, from every device, to go back to.</li>
        <li><b>A profile for each person</b>, with one of Fuse's own avatars and a PIN if they like:
        their own saves (even on a device everyone shares), library, favourites, collections, Home
        and theme, switched in place, no restart. Profiles work without a host too, and go with you
        when the device joins one. A game can be one save for everyone too.</li>
        <li><b>One click to host</b> on Linux, Windows or macOS, kept running when Fuse is closed and
        after a restart; devices find it on the network and ask to join (anyone already in says yes
        after checking a number), or type a code. The host plays as its own Admin. Signed requests,
        revocable devices, and an outside address for away from home.</li>
        <li><b>The Hub shows everything.</b> In Fuse and in a browser on the host: every game's saves
        and their versions, which device made each one, how big it is, where the host keeps it, and
        total play time by profile and by device.</li>
        <li><b>Only what you made.</b> Saves, states, memory cards, play time and settings; your games
        themselves are never copied or synced.</li>
        <li><b>Already run Syncthing?</b> Fuse can share your emulators' save folders through it
        instead, bring in the newest save before a game and ask when two devices both played.</li>
      </ul>
      <a href="docs/sync.md"><b>How Fuse One works</b></a> &nbsp;·&nbsp; <a href="tools/host/README.md"><b>The Fuse One Host</b></a>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/player.svg" width="56" height="56" alt=""><br>
      <h3>Fuse Player <sub>by Fuse</sub></h3>
      Fuse's own video and music player, for your films, shows and music from Jellyfin, with its own
      controls, subtitles and remote. Not a web page, not another app: part of Fuse.
      <ul>
        <li><b>Two engines, one player.</b> Media3 on Android; FFmpeg on Linux, Windows and macOS,
        decoding on the GPU where it can (VAAPI, D3D11VA, VideoToolbox).</li>
        <li><b>Starts fast, keeps playing.</b> A short first buffer, a deep one behind it, retries
        on a flaky link, and when Wi-Fi can't keep up, it lowers the quality by itself and carries on
        from the same second.</li>
        <li><b>Subtitles drawn by Fuse</b> on every platform: SRT, WebVTT, ASS with its styles, PGS
        and DVD pictures, with size, position and delay you set.</li>
        <li><b>Made for a controller</b>, with seeking that speeds up while held, and for touch:
        double tap to skip, drag the timeline, tap outside a sheet to close it.</li>
        <li><b>Two screens:</b> the film on one, a full remote on the other, swapped live while it plays.</li>
      </ul>
      <a href="docs/player.md"><b>How Fuse Player works</b></a>
    </td>
    <td width="50%" valign="top">
      <img src="docs/assets/icons/fuseline.svg" width="56" height="56" alt=""><br>
      <h3>Fuseline <sub>by Fuse</sub></h3>
      Fuse's own animation engine, named after the line in Fuse's logo that carries the spark. Every
      movement in Fuse runs on it, from a tile's lift to the setup's opening. Fuse 0.3.7 brought
      <b>Fuseline 3</b>, where motion never breaks, 0.3.7.5 <b>Fuseline 3.1</b>, which took work off
      every frame around it, and 0.4.0 brings <b>Fuseline 4</b>, which no longer runs motion just
      because time passes.
      <ul>
        <li><b>Fuseline 4: worked out when it is read.</b> Every motion is solved in closed form, so
        a value is worked out from its formula for the frame being shown, exactly where stepping it
        every frame would have left it, without the frames.</li>
        <li><b>Fuseline 4: left alone until it could be seen to change.</b> A value proves the first
        moment it could move far enough to be seen and isn't touched before then; a value nobody is
        looking at rests until it arrives, and comes back exactly where it would have been.</li>
        <li><b>Fuseline 4: shared motion, solved once.</b> Every value on the same spring shares one
        solution a frame, and a colour's four numbers solve their curve once.</li>
        <li><b>Fuseline 3.1: values without coroutines.</b> A tile's lift, a button's colour and a
        panel's size follow their targets with no coroutine of their own, so a page of tiles
        composes faster and in a third of the memory.</li>
        <li><b>Fuseline 3.1: no invisible frames, and decoration that waits for you.</b> A frame that
        would move something less than anyone could see isn't drawn again, and the theme's room
        holds still while you press buttons, then carries on as before.</li>
        <li><b>Fuseline 3: motion never breaks.</b> Whatever interrupts something moving, it carries
        on from where it is, at the speed it has.</li>
        <li><b>One frame for every move.</b> A hundred values moving cost one frame wait, not a
        hundred: a screen full of motion takes about a tenth of Compose's time and memory.</li>
        <li><b>Exact curves and springs.</b> Béziers solved with Newton steps, springs in closed
        form, retargeted mid-flight without a jolt.</li>
        <li><b>Fuseline 2 follows in place.</b> A spring following a finger or a scroll takes each
        new target without a new move: about 16 times faster than before when every value is
        retargeted every frame, measured.</li>
        <li><b>Colours blend through Oklab</b>, so a fade stays even instead of dipping grey.</li>
        <li><b>Your motion choice everywhere</b>: Enhanced, Standard, Minimal, Reduced and Low Power apply to every
        animation at once.</li>
        <li><b>Guarded by tests:</b> nothing in Fuse may import Compose's animation, and Fuseline 3,
        2 and 1 are kept in the tests, as they shipped, to measure every new Fuseline against.</li>
      </ul>
      <a href="docs/fuseline.md"><b>How Fuseline works</b></a>
    </td>
  </tr>
</table>

<div align="center">

| Each frame | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose |
|---|--:|--:|--:|--:|--:|--:|
| 1,000 springs nobody is looking at | **1.33 µs** | 173.36 µs | 183.38 µs | 214.38 µs | 217.47 µs | 2057.55 µs |
| 1,000 tweens nobody is looking at | **5.85 µs** | 89.68 µs | 175.89 µs | 211.89 µs | 206.46 µs | 2054.35 µs |
| 1,000 two-number springs, read every frame | **135.94 µs** | 194.70 µs | 199.13 µs | 251.51 µs | 256.86 µs | 2022.47 µs |
| 1,000 springs given a new target every frame, drawn | **444.43 µs** | 826.75 µs | 844.07 µs | 688.97 µs | 5960.13 µs | 16388.75 µs |
| 100 values following a finger, drawn | **25.14 µs** | 51.79 µs | 52.70 µs | n/a | n/a | 200.14 µs |
| Tabs changed every 3 frames, drawn | **4.07 µs** | 4.87 µs | 5.08 µs | 7.14 µs | 21.25 µs | 50.80 µs |
| A busy screen for 10 seconds | **85.80 µs** | 95.02 µs | 98.92 µs | 105.90 µs | 146.57 µs | 714.77 µs |
| A page of 120 tiles with 3 animated values each, composed | 2240 µs | 2237 µs | 3482 µs | n/a | n/a | 3952 µs |
| The selection moving across 120 tiles | 1718 µs | 1672 µs | 1844 µs | n/a | n/a | 1912 µs |
| A theme's room, redrawn per second while you press buttons | 3 | 3 | 44 | n/a | n/a | 130 |

<sub>Measured on one computer (OpenJDK on Linux), every engine warmed up and given the same work; Fuseline 3.1, 3, 2 and 1 are kept in Fuse's tests exactly as they shipped. Bold: clearly first. Of 111 cases Fuseline 4 is clearly first on 52, 57 are within run-to-run noise, and another engine is clearly first on 2 (<a href="docs/fuseline.md#speed">method and every case</a>).</sub>

</div>

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
      <sub>Every system folder ES-DE names, 157 systems by RomM slug, multi-disc sets, and folder behaviour you choose per system or per game.</sub>
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
      <sub>Fuse never moves or renames your files, and deletes a game's files only when you ask in Storage and confirm. A card or drive that is out is offline, never deleted games, and a drive back under another path is followed.</sub>
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
      <b>RomM, built in or through Cartridge</b><br>
      <sub>RomM in Fuse browses your server, downloads games and BIOS into the folders you have and uploads yours, with an offline copy of the library. Or pair with the Cartridge app, still fully supported.</sub>
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
      <sub>Every drive by name, connected or not, as a bar by system, every game by the space it takes, and deleting the games you're done with, after a confirmation that names what goes.</sub>
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
      <b>Twenty themes, and yours</b><br>
      <sub>Fuse, Glass, Starlight, Crossbar, Orbital, Wave, Blades, Fused, CRT, Daylight and ten more, each with its own background, corners, focus, motion and sound. Make one in the Theme Studio, add one from a link or a file, or <a href="docs/THEMES.md">write your own</a>.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/accessibility.svg" width="44" height="44" alt=""><br>
      <b>Focus you can always see</b><br>
      <sub>The focus spark never relies on colour alone. Four motion levels, including Reduced, High contrast focus, three text sizes, and margins for TVs that cut off the picture's edges.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/privacy.svg" width="44" height="44" alt=""><br>
      <b>Private by design</b><br>
      <sub>No telemetry, analytics, ads or crash reporting. Online services are used only after you set them up.</sub>
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/health.svg" width="44" height="44" alt=""><br>
      <b>System health</b><br>
      <sub>What needs attention in your setup, most serious first, each with the fix: folders, drives, emulators, firmware, playlists and keys. A diagnostics report for bug reports, with anything personal taken out.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/backup.svg" width="44" height="44" alt=""><br>
      <b>Backup and restore</b><br>
      <sub>One file with your settings, Home, what you changed on games, collections, chosen art and play time. Restoring merges and never deletes, and finds your games on another card. Never games, firmware or keys.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/search.svg" width="44" height="44" alt=""><br>
      <b>Search that understands</b><br>
      <sub>Filters like <code>platform:snes</code>, <code>year:1990s</code> or <code>played:week</code> as chips, values offered as you type so a controller never has to spell them, typos forgiven, and every setting findable.</sub>
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/jellyfin.svg" width="44" height="44" alt=""><br>
      <b>Films, shows and music</b><br>
      <sub>Your Jellyfin server in Addons: continue watching, next up, seasons, music and search, played by Fuse Player by Fuse, at home or away. Setup explains Jellyfin and signs you in.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/quick-menu.svg" width="44" height="44" alt=""><br>
      <b>A quick menu that's yours</b><br>
      <sub>Move, resize, add and take out its tiles with a controller, touch or the keyboard. Widgets for what's playing (skip a song with a flick), brightness and volume, and the second screen.</sub>
    </td>
    <td width="33%" valign="top">
      <img src="docs/assets/icons/touch.svg" width="44" height="44" alt=""><br>
      <b>Touch, everywhere</b><br>
      <sub>Every screen answers a finger as well as a pad. On Linux, Windows and macOS touch screens, swipe in from either edge to go back, as on a phone.</sub>
    </td>
  </tr>
</table>

Everything Fuse does, part by part, is in [docs/features.md](docs/features.md).

## Where Fuse stands

> [!IMPORTANT]
> **Fuse 0.4.2.6 "The Native Update"**: each device chooses one Save Sync (Fuse One, RomM or Syncthing,
> or off here while your other devices keep syncing), Fuse One sends saves from games started outside
> Fuse, and setup puts your games first and knows when Steam isn't there. 0.4.2.5 kept games from RomM
> in step with RomM's saves, made a game wait for your newer save instead of starting with an older one, Find Where It Saves shows Fuse
> where any game's emulator keeps its saves, each game has one Save Sync choice (everywhere, receive
> only, off here, off everywhere), and a game's options are four short tabs. 0.4.2.4 made Settings say whether Fuse draws on the graphics card on Linux, took the rings
> and squares out of the Fuse theme's room and lightened the startup animations. 0.4.2.3 brought 3DS saves between
> Android and Linux, blank saves that never replace real ones, a controller that stays Fuse's in
> Game Mode and Jellyfin films at their proper loudness. 0.4.2.2 kept saves each person's on a shared device so a swap
> can't half happen, every emulator's saves are found where it really keeps them, Dolphin's settings
> change from Fuse, firmware checks say verified or not, Home never goes blank, Linux draws more
> evenly, and the Decky plugin can't take Steam's menu down. 0.4.2.1 brought Steam restarts in Game
> Mode, the Store with every update in one place and touch screens on Linux and Windows. 0.4.2 itself
> made Fuse feel at home on Linux, SteamOS and Android. Steam and
> Decky are part of setup, controllers are set up by being asked for each button, Fuse One brings
> profiles, saves, the Fuse Library and now your keys to every device (or keeps each person's saves
> apart on one device with no host at all), the Fuse One Host runs on its own in Docker, and Fuse
> Intelligence shows how far each emulator has really been checked. See
> [the release notes](docs/releases/0.4.2.6.md); every earlier release is in [CHANGELOG.md](CHANGELOG.md).

<details>
<summary><b>Every release so far</b></summary>
<br>

| Version | Name | What it brought |
|---|---|---|
| [0.4.2.5](docs/releases/0.4.2.5.md) | The Native Update | RomM save sync (never over a real save), a game waits for your newer save (keep waiting, play with this device's, or not now), Find Where It Saves test runs, Save Sync for each game, a game's options in tabs, RomM covers on computers, no trackpad paging on Home |
| [0.4.2.4](docs/releases/0.4.2.4.md) | The Native Update | Linux: a Graphics row that says graphics card or processor and why, Steam's runtime libraries set aside, the Fuse theme's room blurred in full precision without rings or squares, dithered background gradients, startup and setup animations that no longer draw the interface under them |
| [0.4.2.3](docs/releases/0.4.2.3.md) | The Native Update | 3DS saves placed before the first play on a device that can't read the game's id, blank saves never sent as the newest, the controller kept in Game Mode after Steam's keyboard, Add and Done by controller, surround folded down at its proper level |
| [0.4.2.2](docs/releases/0.4.2.2.md) | The Native Update | Each person's saves kept apart on a shared device and swapped as one transaction, saves no one owned claimed from Sync, Switch account and device saves, Eden's folder through Android's picker, every emulator's saves where it keeps them, Dolphin settings, firmware verified or not, the emulator contract table, smoother Linux, a safer Decky plugin |
| [0.4.2.1](docs/releases/0.4.2.1.md) | The Native Update | Steam restarts in Game Mode, the Store with every update in one place, checks before installing, Xenia and Vita3K achievements, EmuCoreX packs and cheats, All counting every game, touch screens on Linux and Windows |
| [0.4.2](docs/releases/0.4.2.md) | The Native Update | Steam and Decky in setup, guided and multiple controllers, Fuse One (profiles, saves, the Fuse Library and keys) with or without a host, the Fuse One Host in Docker, Fuse Intelligence, Steam achievements per account with rarity, apps your computers stream on the Library shelf, haptics on controllers, Fuse's own files in Storage, series that wear their games' art, Moonlight on the Systems page, Fuseline 4.1 |
| [0.4.1](docs/releases/0.4.1.md) | The Connected Update | Fuse Library, causal profile settings, strong household identity, save import and display/desktop foundations |
| [0.4.0](docs/releases/0.4.0.md) | The Speed Release | Fuseline 4: values worked out from their motion's formula when read, left alone until they could visibly change, resting while nobody looks; springs solved once for every value on them; a Motion Inspector in Developer options; heat and battery saver ease decoration on Android; fading colours that redraw instead of recomposing |
| [0.3.8.2](docs/releases/0.3.8.2.md) | The Reach Update | Games folders read by their files whatever they are called; a background Fuse Sync host started again on the newest Fuse (the Fuse Library on the host); one or a few systems at the left of the Systems page; a Library list in the developer options |
| [0.3.8.1](docs/releases/0.3.8.1.md) | The Reach Update | Switch libraries read the way people keep them: updates and DLC told apart with or without title ids, joined to their game wherever they sit (own folder, one big folder, updates and dlc folders, or folders chosen in settings), copies counted once, .nsz and .xcz, RomM uploads that bring updates and DLC along |
| [0.3.8](docs/releases/0.3.8.md) | The Reach Update | Every game in the household within reach: other devices' games in the RomM tab and a Fuse Library in the Sync tab, Available On for every game, Send to Another Device (queued while it is away), uploads to RomM asked from any device, the best source chosen by itself, every device's downloads, all remembered offline; Library touch scrolling that stays where you leave it |
| [0.3.7.5](docs/releases/0.3.7.5.md) | The Motion Update | Fuseline 3.1 (values without coroutines, no invisible frames, decoration that waits for you, paced decoration), measured against Fuseline 3, 2, 1 and Compose; an optimised Android build compiled ahead of time; 0.3.7.4's slowdown on Android undone; selection moves that redraw two items |
| [0.3.7.4](docs/releases/0.3.7.4.md) | The Motion Update | Each device keeps its own Systems page; systems made too large through Fuse Sync are put back; arranging on a short screen; far less drawn every frame; the slowest frame shown on the device |
| [0.3.7.3](docs/releases/0.3.7.3.md) | The Motion Update | Systems as small as a game's box art, with a picture of each console you can change; a System size setting; arranging that moves systems along in order; a Systems look for each screen of a two-screen device; RomM uploads that finish; the second screen following Downloads |
| [0.3.7.2](docs/releases/0.3.7.2.md) | The Motion Update | Fuse Sync before Who's playing in setup, so your household's profiles are there to choose; a new, solid controller picture with Fuse's mark in the middle |
| [0.3.7.1](docs/releases/0.3.7.1.md) | The Motion Update | System cards on a handheld, such as the AYN Thor, back to the size they were before 0.3.6.5; TVs keep theirs |
| [0.3.7](docs/releases/0.3.7.md) | The Motion Update | Fuseline 3: motion that never jumps or stops dead when interrupted, tabs that slide and reverse mid-change, layout motion and shared elements, every input moving things the same way, motion levels for accessibility, a motion inspector, and an engine faster than Fuseline 2 and Compose on every benchmark |
| [0.3.6.6](docs/releases/0.3.6.6.md) | The Unity Update | Uploads to RomM, and every other transfer, no longer freeze Fuse on a computer; scraping, downloads and uploads carry on under the standby screen |
| [0.3.6.5](docs/releases/0.3.6.5.md) | The Unity Update | Fuse sized for a 4K TV by itself, with an Interface size setting; the Systems page arranged like Home, with sizes and pages; logos that fit every card and a PS5 logo; Downloads that open their game; RomM art kept on download; RomM found on the network; emulators that start in Game Mode; Store uninstalls with a password prompt, Install All and Uninstall All; one delete per press and a full stop key on the on-screen keyboard; Steam art on any Fuse entry; sharper backgrounds; subtitles outlined in black again; no to Steam kept |
| [0.3.6.4](docs/releases/0.3.6.4.md) | The Unity Update | RomM games' box art shown as soon as it is found, without reopening Fuse; system panels from your games' screenshots, cut like Art Book Next's, by themselves for the PS5 and as a style or your pick for any system; RomM on the second screen; Fuse's art in Steam; Linux updates in place |
| [0.3.6.3](docs/releases/0.3.6.3.md) | The Unity Update | RomM and Jellyfin that come with Fuse Sync, sealed for each device and asked once when you update; each person's own Jellyfin account; whose saves move on each device; Fuse Sync first in setup; the grand welcome the first time anyone plays here; motion that follows the device |
| [0.3.6.2](docs/releases/0.3.6.2.md) | The Unity Update | RomM games with your own art and details, kept and editable; the library's games not on RomM; box art instead of RomM's posters; system art for RomM-only systems; a nicer RomM tab |
| [0.3.6.1](docs/releases/0.3.6.1.md) | The Unity Update | Fuse RomM libraries with large PS4 and PS5 games read quickly, resumed and kept; Test Connection; PS4 and PS5 folders matched to their RomM zips |
| [0.3.6](docs/releases/0.3.6.md) | The Unity Update | Fuse RomM, one Downloads page, Jellyfin downloads for offline, streaming through Moonlight, saves that reach every device by themselves, each handheld emulator's saves from its own code, Restore Fuse Default Art, Syncthing-Fork in the Store, Fuseline 2, screenshots and recordings on computers, Fusi's friend Bo |
| [0.3.5](docs/releases/0.3.5.md) | The Swift Update | Fuse Sync from outside home without waiting on home, saves sent several files at a time, quick profile switches, tabs that change in the next frame, a Sync tab with games first and a timeline of saves, folder saves with their whole structure, a Hub that opens at once, a new update viewer on the website |
| [0.3.4](docs/releases/0.3.4.md) | The Household Update | Profiles without a host, saves from every system reaching it, Fuse Sync and Syncthing that never lose a save or touch what you set up, hints that follow your button mapping, Nintendo pads that confirm with A everywhere, PICO-8 and Flash on the computer, small screens that fit |
| [0.3.3](docs/releases/0.3.3.md) | The Together Update | Saves sent while you play, who is playing what, one game across devices, joining without a code, a host account and shared outside address, Erase Fuse, a mouse that works first click, redesigned Sync and Syncthing tabs |
| [0.3.2](docs/releases/0.3.2.md) | The Corner Update | Menus that travel between screens, a hello when switching profile, each person's own saves, saves in 3DS, Switch, Wii U and Xbox 360 emulators, Jellyfin widgets on every page, round corners |
| [0.3.1](docs/releases/0.3.1.md) | The Glide Update | Home widgets that turn like a catalogue, Jellyfin that answers quickly, Syncthing beside Fuse Sync, the Thor's two screens with the controller |
| [0.3.0](docs/releases/0.3.0.md) | The Sync & Clean Update | Fuse Sync by Fuse, profiles, a quick menu you arrange, 157 systems, Jellyfin in setup, swipe-back on touch screens, the second screen's pages redesigned |
| [0.2.9](docs/releases/0.2.9.md) | The Organized Update | Films on the screen you choose with a live swap, Addons in your order and in Settings, Jellyfin at home |
| [0.2.8](docs/releases/0.2.8.md) | The Media & Connectivity Update | Jellyfin and Fuse Player by Fuse, a phone as keyboard and controller, Storage redesigned |
| [0.2.7](docs/releases/0.2.7.md) | The Swap & Clean Update | Fuse's menus on a dual-screen handheld's touch screen, like a 3DS, and Fuseline by Fuse |
| [0.2.6](docs/releases/0.2.6.md) | The Detail Update | A Store on computers, games moved between drives, Steam achievements and RPCS3 trophies, setup rebuilt |
| [0.2.5](docs/releases/0.2.5.md) and before | The Ignition Update and earlier | The startup mark, PS3, Vita and 3DS installs, the Theme Studio, the Store and Addons, SD cards and drives, System health, backups, PCSX2 patches, the widget board, Windows and macOS |

The full history is in [CHANGELOG.md](CHANGELOG.md).

</details>

The shared core (library scanning, launch resolution, integrations and the database) and the
interface are in place and tested where it matters, and every screen is rendered and checked at 23
screen sizes, from a 3.5 inch 4:3 handheld to a 32:9 monitor and a 4K screen at 100%. The Android
app builds and passes its unit tests and lint, and runs on handhelds every day. The Linux app
builds, passes its tests, packages as an AppImage and starts in a virtual display. The Windows and
macOS builds pass their tests and a self-test of the packaged app on each system in CI. Fuse runs on
Linux, Windows, macOS and Android handhelds alike.

Not there yet: checks on more handhelds and on Windows and Mac computers, signed Windows and macOS
builds, video previews and sound in recordings on the desktop, a storage mode without All files access, translations (the
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
      <p>On KDE Plasma with Wayland and a scaled screen (a 4K TV at 200%, say), set System Settings, Display and Monitor, Legacy Applications (X11) to <b>Apply scaling themselves</b>. Fuse is then drawn sharp and sizes itself for the screen; otherwise the system stretches it and it looks soft.</p>
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

## RomM: Fuse's native RomM integration

Fuse talks to your [RomM](https://github.com/rommapp/romm) server itself, on Android and on every
computer. Turn it on in setup ("Do you use RomM?") or in Settings, Addons, RomM. Its games are in the
Library's All view with every other game.

```mermaid
flowchart LR
    romm[("Your RomM server")] <-->|"browse, downloads, uploads, BIOS"| fuse["Fuse"]
    fuse -->|"places games in"| folders[/"Your system folders"/]
    fuse -->|"offline copy"| db[("Fuse's database")]
```

- **Pairing without a password where it can.** On a RomM server that offers it, Fuse asks to be
  approved as a device, or takes a pairing code made in RomM, and keeps a read-only or
  upload-capable token you can revoke in RomM. A server without client tokens takes a sign-in.
  Either way it lives only in Fuse's secure secret storage, never in the database, logs,
  screenshots, diagnostics or exported settings.
- **Home and away.** A local address, an outside one, or both: Fuse uses whichever answers and
  never waits on one that doesn't.
- **What the server can do, from the server.** Fuse reads RomM's own API description, so features
  a server lacks are simply not offered.
- **An offline copy.** The server's library is kept in Fuse's database, brought up to date a page
  at a time, so RomM's games, systems and collections, and Search work without the server.
- **Games in the folders you have.** Downloads go through Downloads into the system folders Fuse
  already uses, resumed where they stopped and checked before they are put in place. A RomM game
  is matched to one in your library only by its RomM link, a hash, the console's own title id, the
  exact file name, or the same name with the same region and revision when it is the only one on
  each side; a name that only looks alike never joins two games.
- **Uploads** in pieces, resumed where they stopped, with a token that allows them.
- **BIOS from RomM** on health pages and each system's BIOS menu. A file you already have is never
  replaced by one of the same name; firmware an emulator installs itself goes to a Firmware folder
  with how to install it.

RomM in Fuse and Cartridge are both supported. Turning one on turns the other off in Fuse, keeping
both set up, so you can switch back at any time.

## RomM through Cartridge

<a href="docs/assets/screenshots/cartridge.webp"><img src="docs/assets/screenshots/cartridge.webp" width="100%" alt="Fuse's Cartridge page, connected to RomM: Open Cartridge, Browse, Search, Upload, Downloads and Consoles, then Browse by system with Steam, PlayStation, PS2, PS3, PSP, Nintendo 64 and DS, and Recently downloaded games."></a>

Cartridge remains fully supported for people who prefer it to RomM in Fuse. With Cartridge, Fuse
itself does not talk to RomM: [Cartridge](https://github.com/abdu2304/cartridge) by
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

1. Install Cartridge 0.9.10 or newer (Settings, Accounts, Cartridge in Fuse can install it from Cartridge's
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
fully offline. Fuse only contacts a service after you set it up: your own Fuse One host, your own Syncthing, your own Jellyfin server,
RetroAchievements, SteamGridDB, IGDB, TheGamesDB, ScreenScraper and libretro thumbnails receive only what they need to answer (for example a
game's title, or your own API key). The one service used without setup is GitHub, to check for new
versions of Fuse; you can turn automatic checks off in Settings, About, and nothing is ever
downloaded or installed without your confirmation. rpcs3.net is asked, with only a game's title id,
when you choose How It Runs in RPCS3. Backups and diagnostics reports are files you save yourself;
Fuse never sends them anywhere. API keys and passwords are kept in the system's
secure storage, never in logs or in the database. [INTEGRATIONS.md](INTEGRATIONS.md) lists exactly
what each service receives and when.

## Documentation

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, domain model, scanning, launching, input, storage, security |
| [INTEGRATIONS.md](INTEGRATIONS.md) | Every online service and the Cartridge bridge, and what leaves the device |
| [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) | Tokens, type, focus, motion, themes, sound, accessibility |
| [docs/features.md](docs/features.md) | Everything Fuse does, part by part |
| [docs/sync.md](docs/sync.md) | Fuse One: profiles, save sync, the Fuse Library, hosting, what syncs, conflicts, security, and Syncthing instead |
| [tools/host/README.md](tools/host/README.md) | The Fuse One Host: installing, updating, backing up and removing it |
| [docs/fuseline-sdk.md](docs/fuseline-sdk.md) | Using Fuseline in your own Compose Multiplatform app |
| [docs/player.md](docs/player.md) | Fuse Player by Fuse: engines, subtitles, controls, two screens |
| [docs/fuseline.md](docs/fuseline.md) | Fuseline by Fuse: curves, springs, transitions and how fast it is |
| [docs/jellyfin.md](docs/jellyfin.md) | Jellyfin in Fuse: connecting, home and away, what is sent |
| [docs/PHONE_LINK.md](docs/PHONE_LINK.md) | Phone Link: your phone as a window on the library, a keyboard and a controller |
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

- **Fuse One**, **Fuse Player by Fuse** and **Fuseline by Fuse**, Fuse's own household system,
  media player and animation engine, written for Fuse and part of this repository, under its licence.
- [ES-DE](https://es-de.org/), whose MIT-licensed emulator configuration is the best public record of
  how emulators accept games.
- [RomM](https://github.com/rommapp/romm), whose folder conventions, platform names and alias table Fuse follows.
- **[Cartridge](https://github.com/abdu2304/cartridge) by [abdu2304](https://github.com/abdu2304)**
  (MIT), the RomM companion Fuse pairs with, and where Fuse learned to match emulator forks by name
  and to know an AppImage by what it updates from.
  Fuse's bridge (live downloads, deep links and uploads) is being contributed to Cartridge; until
  it is merged there, Fuse installs Cartridge from the [MAtiyaaa/cartridge](https://github.com/MAtiyaaa/cartridge)
  fork, and will switch to abdu2304's releases once it is.
- [Jellyfin](https://jellyfin.org/), the free media server Fuse plays your films, shows and music
  from, and [FFmpeg](https://ffmpeg.org/) (through [JavaCPP](https://github.com/bytedeco/javacpp-presets))
  and [Media3](https://developer.android.com/media/media3), which Fuse Player by Fuse decodes with.
- [rcheevos](https://github.com/RetroAchievements/rcheevos) and
  [RetroAchievements](https://retroachievements.org/).
- **Music by boipurple.** Fuse's menu music is boipurple's albums *jam channel* and *creature
  interchange*: puddleworld plays under the menus and alright apothecary during setup, and every
  song can be picked in Settings, Sound, shuffled, or skipped from the quick menu. The songs remain the artist's own and are not covered by Fuse's
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
