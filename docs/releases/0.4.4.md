# Fuse 0.4.4 - The Overdrive Update

Steam's Play goes straight to the game, every emulator setting is in Fuse, RomM works both ways,
the Store looks after your emulators, mods and patches come from more places, games are played
together as each emulator does it, and Fuse Ports brings games rebuilt to run natively.

## New

- **Fuse Fast Start for your games in Steam.** A game Fuse puts in Steam's library now starts with
  no Fuse window at all: Steam runs Fuse in the background, which puts your newest save in place,
  starts the game exactly as Play does, counts the time, keeps and sends the save, and leaves. On a
  release build Steam's Play reaches the emulator in under a second (0.74 to 0.87 seconds on Fuse's
  Linux build container, Fuse's own part about 0.4 seconds). When something needs you (two saves to
  choose between, another device still playing it), Fuse opens on the game to ask, and with Fuse
  already open, the open Fuse plays it. Entries made by 0.4.3 and before still work, and Look Them
  Over offers to move them to Fast Start.
- **Straight in the Emulator, on Linux.** Settings, Library, Steam, How Steam Starts Them can make
  each game's Steam entry start its emulator itself, with the command Play would run and nothing of
  Fuse's in between. Fuse says first what that gives up (saves put in place and sent, play time,
  profiles), and a game that can't start on its own goes in with Fast Start, with the reason. Fuse
  still knows these entries as its own, so it brings them up to date, takes them out and looks them
  over like the others; switching between the two keeps each entry's art and the name you gave it.
  A game's own menu has Steam Entry too, to start that one game either way.
- **Shortcuts From Other Tools**, in Settings, Library, Steam: the shortcuts Steam ROM Manager,
  EmuDeck or you made for games Fuse has, found by the game's own file (never by its name). Take
  them over, all at once or one by one, and each becomes Fuse's entry in the same place, keeping its
  name, art and collections; or leave them as they are. Undo puts Steam's library back.
- **Direct entries start the way you do.** Fuse learns from your own shortcuts how you start each
  system's games (the program, its folder and the launch options, exactly as you write them, quotes
  and variables kept) and starts its Direct entries for that system the same way. Settings,
  Library, Steam, Learned From Your Shortcuts lists each system's way, with a switch to start them
  as Play would instead. Frame generation your shortcuts add is offered as Fuse's own Frame
  Generation for that system rather than copied, and a start folder inside an AppImage that has
  since closed becomes the AppImage's own folder.
- **Play From Steam, and logos in place.** In Game Mode with the Fuse Decky plugin, a game's
  options have Play From Steam, which starts its Steam entry the way Steam's own Play does, with
  Steam's overlay and the controller layout you gave it. Games the plugin adds get their logo placed
  as Fuse places it, unless you placed it yourself.
- **Windows ports use your Proton.** A port's Windows build on Linux starts with the Proton you
  chose for every game in Steam, or the newest one you have, instead of one umu-launcher fetches.
- **Your collections and Fuse's, together.** Fuse's Steam collections are named maker then console.
  Settings, Library, Steam, Your Collections finds your own collections that are the same system
  (GBA, PlayStation) and lets you keep both, put Fuse's games in yours, or rename yours Fuse's way;
  yours are never deleted. If Steam Cloud drops Fuse's collections, they're back when Steam closes.
- **Steam entries looked after.** Pictures are used only in the shape Steam shows them in, Direct
  entries whose emulator updated or whose game moved are found and put right in place, and System
  health says when a game's drive is out.
- **See each entry before it goes into Steam.** Adding games to Steam now offers See Each Entry:
  what Steam will start for each one, from which folder, with which launch options, before
  anything changes.
- **No more Steam restarts with the Decky plugin.** With the Fuse Decky plugin loaded, games you add
  to Steam from Fuse (and ones you take out) go in at once through Steam itself, with their art,
  while Steam stays open; their collections follow when Steam next closes. Without the plugin,
  Fuse still asks to restart Steam or waits for it to close, as before. The plugin and Fuse now
  speak bridge protocol 2, so update the plugin from Settings, Steam.

- **Time in Direct entries is counted.** With the Fuse Decky plugin, the time you play a game from
  its Direct entry in Steam is counted in Fuse from Steam's own start and stop, even when Fuse
  wasn't running then: the plugin keeps it until Fuse is back.
- **Frame Generation**, on Linux, for a system or a game: Off, 2x, 3x or 4x through lsfg-vk, set on
  the emulator's command the way lsfg-vk documents it. It works from Fuse, from Fast Start and from
  Direct entries, and shows once lsfg-vk is set up on the computer.
- **Keep Steam in Step**, in Settings, Library, Steam, off until you turn it on: games that join your
  library go into Steam by themselves, and Fuse's entries for games that leave it come out. Fuse
  offers it once, after the first game you add to Steam yourself.
- **Undo a Change** lists Fuse's last ten changes to Steam's games, each with what it did, and takes
  Steam's library back to before any of them.
- **Every Setting, by Fuse Tune.** An emulator's settings in Fuse now go past Fuse's own picks: Every
  Setting has all of PCSX2's, DuckStation's, PPSSPP's, Dolphin's and RPCS3's settings (a thousand
  and more), read from each emulator's own code at its last four releases, grouped and named as
  the emulator itself does, with search. Switches turn on and off where they are, choices list
  only what this computer's build of the emulator has, and numbers are checked against the
  emulator's own range before anything is written. Only the settings the installed version has
  are shown. It works for every game and for one game, and Put Back undoes any of it.
- **RPCS3's settings in Fuse.** RPCS3 joins the emulators whose settings Fuse changes, through
  Every Setting, for every game and for one game (its file kept under the game's title id). Fuse
  says first that a game's own RPCS3 settings take the place of those RPCS3's database gives it.
- **Many more emulators' settings in Fuse.** Every Setting now has RetroArch's own settings, named,
  described and grouped as its menu has them, for every game and for one game, kept in the
  override RetroArch reads for that game with the core that plays it. Eden's and Citron's are
  there too, for every game and for a Switch game by its title id; Azahar's, AzaharPlus's,
  Lime3DS's and Mandarine's, for every game and for a 3DS game by the title id in its own file;
  shadPS4's for a PS4 game by its serial; Xenia's for an Xbox 360 game by its title id; and
  Vita3K's for a Vita game by its title id; Flycast's for a Dreamcast game by its product number,
  with its renderer and volume; MAME's for a system by its short name; Supermodel's for a Model 3
  game by its ROM set; and melonDS's, which has settings for every game only. Each is written as
  the emulator writes its own, so it reads every value back exactly. A Wii U game's Cemu profile is there as well, made as a copy of
  the one Cemu ships for the game, and a Vita game's own file is made with every setting it holds,
  so nothing else about the game changes. Put Back undoes any of them.
- **Save states for more emulators.** With Save States on, PCSX2's, PPSSPP's, Dolphin's, melonDS's,
  mGBA's, Mednafen's, Flycast's, MAME's and Supermodel's save states are kept in step too, beside
  RetroArch's, DuckStation's and Azahar's: each game's own, found where the emulator keeps them,
  and the game page's Play line says so. For a game of Steam's own, the line says it starts
  through Steam, which keeps its saves and play time.
- **Systems tell what you play.** The Systems page now says, for the system in focus, how long you
  have played it, how many of its games, the one played most, and shows the covers of those played
  last.
- **No sign-ins in Every Setting.** Passwords, tokens and keys an emulator keeps among its
  settings (RetroArch's RetroAchievements and WebDAV sign-ins, Flycast's RetroAchievements token,
  Azahar's and Citron's web service tokens) are never shown or written by Fuse.
- **Supermodel's own build.** Model 3 games can start in Supermodel itself, from the game's folder
  as ES-DE starts it, and their NVRAM is kept in step like any other save.
- **A game's core options.** A RetroArch game's page now has its core's own options too (85 cores,
  from Snes9x and mGBA to Beetle PSX, SwanStation, Flycast and Mupen64Plus-Next), named and grouped
  as the core names them. Changing one gives the game its own options file, made as RetroArch's
  Quick Menu makes it, with every option as the game had it, so nothing else about the game
  changes; Put Back takes the file away again.
- **Settings for your device.** Beside Vanilla, Performance, Visual Quality and Battery Saver, a
  game in PCSX2, Dolphin, DuckStation or PPSSPP can be given Steam Deck, ROG Ally or 4K TV: the
  resolution fitted to that screen (in the hand, the step nearest the screen, to spare the battery;
  on a TV, the first that fills it), frames paced to the screen, and each choice said. The one that
  fits this screen says so.
- **When Fuse can't tell the version.** Fuse now reads more emulators' versions without starting
  them: a Flatpak's from its own files even where the `flatpak` command can't be asked, and a
  distribution package's from what the package says, with RetroArch and PPSSPP asked themselves.
  An emulator whose version Fuse still can't read shows every release's settings, and one that only
  some releases have is locked, saying which, rather than written to a version that may not read it.
- **RomM keeps saves made of folders.** A PSP, PS3, Vita or Switch save (or any save of several
  files) now goes to RomM as one zip and comes back whole on your other devices, where before only
  saves of one file did. RomM shows each save in a slot named for what it is (PSP Save Data, PS2
  Memory Card), and with more than one person on a device each person's saves are their own.
- **Saves wait for RomM.** A save that can't reach RomM (the server away, the device offline) waits
  and goes by itself, in order, once RomM answers, even after Fuse restarts. Addons, RomM Saves says
  how many wait, with Send Them Now.
- **RomM, Then Fuse One**, in Save Sync on this device: RomM moves the saves of games from RomM, and
  Fuse One every other game's, never both for one game. Fuse One still counts the time you play
  the games RomM keeps.
- **Saves from an earlier folder.** When an emulator's save folder moved (the Flatpak beside the
  native install, an installed copy beside a portable one, a save folder you chose since), Saves on
  This Device finds a newer save of the game in the folder it used before and offers it. Use This
  Save copies it to where the emulator reads it now, the save there kept in Fuse's kept folder
  first; the earlier folder stays as it is.
- **Your changes go back to RomM**, when you turn on Settings, Addons, RomM, Send Changes to RomM:
  favourites (into RomM's own favourites), games you hide, and the time you play games from RomM.
  Made while RomM is away, they wait and go in order once it answers. Needs RomM 5.4.
- **Cloudflare Access.** A RomM server behind Cloudflare Access works from outside: add its service
  token in Settings, Addons, RomM, and Fuse sends it only to that address.
- **See RomM's scan.** After an upload, RomM's status line shows how far its scan has come, and Fuse
  says how many new games it found.
- **RomM on this computer.** No RomM yet? With Docker or Podman installed, Fuse sets RomM up on
  this computer, your library read-only to it, and starts and stops it from Settings.
- **Go back to any earlier version.** When the Store updates a program on a computer, it keeps the
  version it replaced, now the last three of them rather than only one. Go Back to an Earlier
  Version on its page picks any of them, and the version in place is kept in turn, so going back
  never loses one.
- **Update All says each update first**: the version each app goes from and to, its size, and how
  (Fuse from the project's releases, or Flatpak). On Linux it now updates Flatpaks from Flathub too,
  through Flatpak, and when it's done one notice says what was updated and what wasn't, and why.
- **Add an app from Codeberg or any Forgejo or Gitea server**, not only GitHub. Before anything is
  added, the Store shows what its link would install: the newest release, the file and its size,
  and the room it needs.
- **Room is checked before a download.** A program's page says how much room it needs to install
  (an archive is unpacked beside its download) against what is free, and the Store won't start a
  download there isn't room for.
- **EmuDeck's emulators are left to EmuDeck.** An emulator EmuDeck installed is marked as EmuDeck's,
  and the Store leaves its updates to EmuDeck instead of offering its own. With RetroDECK on the
  computer, the Store says which emulators RetroDECK already has inside it.
- **Builds and channels on a program's page.** Builds shows whether it follows stable releases or
  pre-releases, and for an emulator carried on by several builds (Eden and Citron, Azahar and its
  forks), the others, each a choice away.
- **PS3 and Vita system software from Settings.** Settings, Systems, PS3 or PS Vita, Firmware has
  Install With RPCS3 (or Vita3K): choose the system software file and the emulator's own installer
  puts it in place.
- **Nexus Mods, with your own key.** Add your personal API key in Settings, Accounts, Nexus Mods,
  choose which game there a game of yours is, and its Trending, Latest Added and Latest Updated
  mods are in the game's options, beside mods of your own. With Premium a file comes straight in; otherwise paste
  the file's Mod Manager Download link from its page (Fuse opens it), or add the file you downloaded.
  Either way you see every file and where it goes first. The key stays on this device: it is never
  shared with your household.
- **xdelta patches.** Apply a Patch takes xdelta files beside IPS, UPS and BPS, including the ones
  xdelta compressed with LZMA (how it makes them by default), and a patch made for another version
  of the game is said, with nothing made.
- **Dolphin's cheats.** A GameCube or Wii game's Cheats, Textures and Mods now takes Gecko and
  Action Replay codes (a game's .ini, or a .txt of codes), added to the game's own settings in
  Dolphin with cheats turned on for that game; tick the ones you want in Dolphin's game properties.
  Codes the game already has are left out, and Remove takes out exactly what went in.
- **Two mods changing the same file are said first.** Before a mod goes in, Fuse names any mod
  already added that changes the same files, since the emulator uses only one of them.
- **Mods for PS3, PS4, Switch and Wii U games** are in each game's options now (Patches and Mods,
  or Mods), beside GameCube, Wii and PlayStation games' cheats and textures.
- **Play Together**, in a game's options on a computer. With RetroArch, Host a Game starts it for
  others to join, and Join starts it connected to the host. With PPSSPP, hosting runs its own ad hoc
  server and joining points PPSSPP at the host's. With Fuse One, your household sees a game you host
  ("Ana is hosting Kart Racer on the Steam Deck") and joins it from its Play Together.
- **Online play, set up as each emulator keeps it.** Play Together turns on RPCS3's network
  through RPCN and signs it in to your RPCN account (your password is used once, as RPCS3 itself
  uses it, and kept nowhere), turns on PCSX2's network adapter, PPSSPP's wireless and your name in
  Dolphin's NetPlay, with the host's address on its Connect tab to join. Put Back undoes exactly
  that, and after the Store updates one of them, Fuse says if its setup is gone.
- **Fuse Ports**, in Addons on a computer (turn it on in Settings, Addons): games rebuilt to run
  natively, played with your own copy of the game. Diablo (DevilutionX), RollerCoaster Tycoon 2
  (OpenRCT2) and Fallout 2 (Fallout 2 Community Edition) install from their own projects' releases,
  each checked against Fuse's list before it goes in, and so does Majora's Mask through Zelda 64:
  Recompiled, on Linux, made from your own ROM: Fuse finds it in your library by its hash, as the
  recompilation itself checks it, and links it in (or a copy in the form it reads), never changing
  your file. Choose your game's folder and its files are
  linked in where the port reads them, never copied or changed; what the port writes, its saves
  too, stays in Fuse's folder. Each becomes a game of your library, with Steam entries and saves
  kept in step. A nightly check starts every port, and once Fuse carries the key the list is signed
  with, the ones that passed show as verified. Your copy can come straight from the library, and
  the original game's page offers the port beside its emulators: pick it, and Play (and its Steam
  entry) starts the port instead. Each port's Game Settings are every setting of its own, read from
  its code (DevilutionX's with its own names and descriptions), changed in its own file and put
  back exactly.
- **PS4 trophies before the first play.** A PS4 game's trophies show on its page before shadPS4
  has opened it, read from the game's own trophy file with the trophy key you gave shadPS4.
- **Sign Emulators In**, in Settings, Accounts, once RetroAchievements is connected: type your
  RetroAchievements password once and RetroArch, PCSX2, DuckStation, PPSSPP and Dolphin are signed
  in, each in its own way (DuckStation's token locked to the computer as DuckStation does it).
  The password goes to RetroAchievements and is never kept; the token it gives back is kept with
  Fuse's other secrets. Each profile signs in as its own account, switching profile switches the
  emulators, and Sign Them Out puts every file back as it was.
- **Trophies through RomM.** Without a Fuse One host, the trophies you earn in RPCS3, shadPS4,
  Vita3K and Xenia reach your other devices through RomM 5.4: for a game from RomM they are kept
  in a note of your own on it (with Send Changes to RomM on), and each device's game page reads
  it, saying where each was earned. Trophies are only ever added, each with the first time.
- **Update Channel**, in Settings, About: Stable (published releases) or Beta (pre-releases too),
  each taking the newest Fuse it follows. Nightly is there for nightly builds, and gets what Beta
  gets until they are published.
- **Fuse's notices in Game Mode.** While a game is in front of Fuse, what Fuse would say on its own
  screen (a save sent, a download done) shows through Steam's own notifications with the plugin.

## Changed

- **The game page says what Play will do.** Under Play, one line says where the game starts and
  with what: the RetroArch core, frame generation where it is set, and your newest save put in place
  first where Fuse keeps that game's saves in step.
- **Systems uses the room it has.** On a screen with room to spare (a monitor turned upright, a TV
  with a few systems), the Systems page shows fewer, larger cards, as long as every system still
  fits. A System Size you chose in Settings stays as you set it.
- **Fuse starts faster on a computer.** Its network client, Jellyfin and its own updater are made
  only when something first needs them, and the release's class data sharing archive now also
  learns a start with games in the library and a Fast Start.

## Fixed

- **The Fuse plugin for Decky no longer always says it needs updating.** The plugin Fuse carries
  now has Fuse's own version, so Settings, Steam shows it as up to date once it is installed,
  instead of offering Update every time.
- **A Fuse One host set up in a folder that holds other things stays in its own folder.** Setting
  up the host just as Fuse was starting could put the host's files straight into the folder you
  chose (a whole drive, say) and then lose track of this device. The host now always goes in its
  own folder inside it.
- **The hints under a problem or a question are its own.** A problem sheet or a confirmation shows
  Select and Back along the bottom, not the buttons of the page beneath it.
