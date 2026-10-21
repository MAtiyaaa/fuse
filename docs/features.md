# Fuse in detail

Everything Fuse does, part by part. The [README](../README.md) has the tour; this is the reference.

<details>
<summary><b>Library and folders</b></summary>
<br>

- **Library roots** in ES-DE style `ROMs/<system>` folders, RomM Structure A and B libraries, single
  platform folders and folders of Steam or PC shortcuts.
- **157 systems** recognised by RomM slug, RomM alias, ES-DE name or full name: every game system
  folder ES-DE knows, from the Apple II and the VIC-20 to the PC-98, the Model 3 and WASM-4, each
  reading the file types ES-DE accepts for it.
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
  when you ask in Settings, Storage and backups and confirm (never from Phone Link). Missing games are marked,
  not removed, and playlists for multi-disc games are written to Fuse's own cache.
- **BIOS checks** report Unknown, never Missing, when a location cannot be read.
- **Transfers that follow your rules**: how many at once, a bandwidth limit, slower or paused while
  you play, only on Wi-Fi, only while charging, a battery level to wait below, and quiet hours.
- **Patches** (IPS, UPS, BPS and xdelta) applied from a game's options as a copy beside it, checked
  against the game they were made for where the patch says; the game itself is never changed.
- **Storage** shows every drive by name, connected or not, every game by the space it takes, and
  Fuse's own files: the saves Fuse One keeps here, a host's household saves, kept saves of removed
  profiles, and art and caches.

</details>

<details>
<summary><b>Launching</b></summary>
<br>

- **Documented, not guessed.** Emulators and launchers are described as data, each with its source
  (ES-DE's MIT-licensed configuration, the emulator's own code or its vendor's guide) and a
  confidence level.
- **More than 100 definitions on Android** (RetroArch, Dolphin, PPSSPP, DuckStation, NetherSX2,
  ARMSX2, aPS3e, melonDS, Azahar, Eden, Vita3K, Flycast, Lemuroid, MAME4droid, ScummVM and many
  more), **38 on Linux**, found on `$PATH`, as Flatpaks or as AppImages (RetroArch, PCSX2, RPCS3,
  Dolphin, Cemu, Ryujinx, xemu, Xenia, shadPS4, MAME, DOSBox Staging, Ruffle, PICO-8 and more),
  **36 on Windows**, found in portable folders, Program Files, AppData, Scoop, Chocolatey, winget
  and Steam libraries, and **32 on macOS**, found as apps or Homebrew programs. Anything Fuse misses on Windows or macOS
  can be located from Settings. An AppImage is known by the release it updates from, so a renamed
  one is still found.
- **Every Linux emulator in the Store**, installed by Fuse: AppImages, tarballs and 7-Zip
  archives as each project publishes them (Eden from its own server, with its Steam Deck and ROG
  Ally builds), and Flathub for the few that publish nothing of their own. The Emulators tab shows
  the ones on the computer. The last three versions an update replaced are kept to go back to, the
  room an install needs is checked before a download, Update All says every update first, and an
  app can be added by its GitHub, Codeberg, Forgejo or Gitea link with a look at what would install.
  What EmuDeck installed is left to EmuDeck.
- **RetroArch for every system it runs**, with ES-DE's cores in ES-DE's order, on Android, Linux,
  Windows and macOS.
- **Pick an emulator** per system or per game. Forks and renamed builds are recognised by family, and
  shared package names are trusted only after Fuse checks that the expected activity exists.
- **Honest fallbacks.** When an app has no documented way to start a specific game, Fuse opens the app
  and says why.
- **Steam and Windows games** start through GameNative, GameHub Lite, Winlator Cmod, WinNative or
  Bannerlator on Android, through Steam or `.desktop` shortcuts on Linux, and as they are on Windows
  (`.exe` games, `.lnk` shortcuts and `.bat` scripts).
- **DLC and updates**: Fuse explains, per emulator, how they are used.
- **Installs PS3, Vita and 3DS games itself**: packages, updates, DLC and licences, from any drive,
  into RPCS3, Vita3K and Azahar. They go in the right order, missing licences are shown first, and
  each step is checked in the emulator's storage. The game page says Ready to play, Needs
  installation, Missing licence, or Update or DLC available locally.
- **Every emulator has a page**: how it was found, what it runs, how Fuse starts it and its limits,
  with a test launch. A game's emulator list says why one can't open that game.
- **PCSX2 patches** (widescreen, 60 FPS and more) for a PS2 game, turned on in PCSX2's own settings
  for that game. Fuse only turns off what it turned on, and leaves anything set in PCSX2 alone.
- **Cheats, textures and mods** of your own, from GameBanana, or from Nexus Mods with your own key,
  put where each emulator reads them (Dolphin's Gecko and Action Replay codes in the game's own
  settings), with every file shown first, a mod that changes the same files as another said before
  it goes in, and each taken out again exactly.
- **PlayStation disc details**: the serial and PCSX2's CRC, read from ISO and BIN images.
- **How a PS3 game runs in RPCS3**, from RPCS3's compatibility list, asked only when you choose it.
- **Play Together**: host a game in RetroArch or PPSSPP for others to join, or join one your
  household is hosting (Fuse One says who hosts what), and online play set up as each emulator keeps
  it: RPCS3 through RPCN with your account, shadPS4 through ShadNet, PCSX2's network adapter,
  PPSSPP's wireless and its name for revival servers, Dolphin's NetPlay by address or host code and
  WiiLink, and the rooms of Azahar, Eden and Citron, each put back on request.
- **Fuse Ports** (on a computer): games rebuilt to run natively, played with your own copy of the
  game. Diablo through DevilutionX, RollerCoaster Tycoon 2 through OpenRCT2 and Fallout 2 through
  Fallout 2 Community Edition install from their projects' own releases, checked against Fuse's
  signed list, and your game's files are linked in, never copied or changed. Each becomes a game of
  your library, with Steam entries and saves kept in step like the rest. Majora's Mask plays through
  Zelda 64: Recompiled, made from your own ROM, and on Linux and Windows a PlayStation 5 dump can be
  recompiled with AnyPS5 into a program of your computer's own, from its Play With list.
- **Ports on Android**: games rebuilt for Android, each its project's own app. A game's Play With
  lists the ports that play it (Fuse's checked ones first, then those Open Ports Games lists), and
  the one you pick installs from its project's releases and is attached to the game, so Play starts
  it in the emulator's place with the time kept as the game's. Majora's Mask through Zelda 64:
  Recompiled or 2 Ship 2 Harkinian, Pokémon Red to Emerald through Gen1Recomp, Bloodborne through
  Bloodborne-Android and more. An app you installed yourself is a Recomp or Port in its Type, and
  Fuse finds the game it plays, or asks. A ported game shows in the Ports system too (or Android,
  or only its own system, for every game, each system or each game): one game with one time and
  one set of achievements, started with the port from Ports and with its emulator from its own
  system. Every port is drawn with its game's box art, year, publisher and genre, and when your
  copy of its game is on your RomM server or another device of the household, Ports says so and
  brings it here for you.
- **Problems that explain themselves**: when a game can't start, Fuse says why, that nothing was
  changed, and offers the fix. If Fuse itself fails to start three times, it starts in **safe mode**.

</details>

<details>
<summary><b>Films, shows and music</b></summary>
<br>

- **Your Jellyfin server in Addons**, connected at home or from away, switching between the two by
  itself: Continue watching, Next up, Recently added, libraries, seasons and episodes, collections,
  music by artist and album, and search. Pages you've seen work offline.
- **Played by Fuse Player by Fuse** ([how it works](docs/player.md)): Direct Play first, then Direct
  Stream, then a transcode, decided from what your device can decode. Quality caps for home and
  away, and on weak Wi-Fi a lower quality chosen by itself, from the same second.
- **Subtitles and audio** in your languages, with size, position, delay and background; resume,
  up next, the next episode on its own, and your playback speed kept if you like.
- **On two screens**, the film plays on the screen you choose, the other becomes its remote, and the
  two swap live. The second screen keeps its pages (Status and Controls) a swipe away.
- **Home widgets** for Continue watching, Next up and Recently added, and the quick menu's Now
  playing to pause or skip.
- **Setup explains Jellyfin** for anyone who hasn't met it, finds servers on your network and signs
  you in. Your password goes to your server and nowhere else.

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
- **Achievements and trophies** on each game's page: RetroAchievements with your own key, Steam's
  own, trophies from RPCS3, shadPS4 (before the first play too) and Vita3K, and Xenia's
  achievements. **Sign Emulators In** signs RetroArch, PCSX2, DuckStation, PPSSPP and Dolphin in to
  RetroAchievements with one password that is never kept, per profile, and Sign Them Out puts their
  files back exactly.

</details>

<details>
<summary><b>Controllers and interface</b></summary>
<br>

- Custom **key repeat** that accelerates while held, **hold to reorder**, **stick navigation** with
  deadzones, and hint glyphs for Xbox, Nintendo, PlayStation or keyboard.
- **Controller setup**, guided (Fuse asks for each button in turn, and holding any button for five
  seconds leaves) or edited by hand, with capture and a live button test that no button can leave by
  accident.
- **Several controllers at once**: Fuse knows which are connected and which one moves around Fuse,
  chosen per profile; any controller does while the chosen one is away.
- **Haptics** for what happens: a light vibration with each notice, a firm pulse when something
  finishes well and a double knock when something didn't, on Android and on the controller in use on
  Windows and macOS.
- **Home** as a board of widgets you move and resize like a phone's home screen (Fused, the one
  we recommend) or a flowing dashboard of rows (Network): 19 kinds of widgets, each designed for every size from one cell to
  four by three.
- **Library** layouts from square box art to capsules to covers to a compact list; **Systems**, **Apps**
  (on Android), **Search** with filters, game pages, a media manager, a folder browser, the **quick
  menu** you arrange (tiles and widgets moved, resized, added and taken out by controller, touch or
  keyboard), a **Play time** page and a guided setup.
- **Stream from your computers**: with streaming on, the Library's All view has a shelf of the apps
  your computers offer through Sunshine or Apollo, each once, streamed through Moonlight from the
  computer you choose. A game or a whole system can stream from a computer instead of playing here,
  and Moonlight can stand on the Systems page as a system of its own (Settings, Addons, Streaming).
- **Series** on Home and in Search wear their first game's art and logo.
- **Touch everywhere**: every screen answers a finger, and on computers' touch screens a swipe in
  from either edge goes back. On Linux and Windows a finger reaches Fuse as the mouse (Compose
  Desktop is never told a finger made it), so there a held drag scrolls lists, shelves, grids, boards
  and pages and flings them on when let go, a hold opens a game's options, and nothing lights up as
  it passes under the drag. A real mouse dragged with its button held scrolls the same way.
- **A second screen with pages**: what's chosen (or the remote while a film plays), Status with the
  battery large, and Controls with brightness, volume and switches, on the screen below or above.
- **Twenty themes** and community themes from a link or a file ([docs/THEMES.md](docs/THEMES.md)), square
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
Linux app, isn't offered on Windows and macOS (RomM in Fuse is, on every computer).

</details>
