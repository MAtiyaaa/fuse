# Fuse 0.4.2 - The Native Update

The Native Update makes Fuse feel at home on Linux, SteamOS and Android: Steam and Decky as part of
setup, controllers that are understood, Fuse One as the household's one place for profiles, saves
and the library, and a first step toward Fuse knowing emulators for itself.

## New

- **Steam and Decky in setup.** Setup asks once whether to integrate with Steam, then offers the
  Fuse Decky plugin (on Linux, where Decky runs) and Fuse in Steam's library, each optional and
  each named on the rail. Nothing privileged runs without asking, Steam is never closed for it,
  and a finished part moves straight on. Settings, Library has the same controls afterwards: add,
  update or remove Fuse in Steam, and install, update or remove the Fuse plugin and its bridge.
- **Fuse Decky Bridge.** A loopback-only, token-protected bridge between Decky and Fuse, with the
  Fuse Decky plugin source in tools/decky.
- **Guided controller setup.** Controls has Guided and Edit. Guided asks for each button in turn,
  picks the controller when several are connected, and is left by holding any button for five
  seconds. Pad pictures show sticks as sticks (AYN Thor and others).
- **Multiple controllers.** Fuse knows which controllers are connected and which one moves around
  Fuse (chosen per profile in Settings, Input); any controller does while the chosen one is away.
- **Fuse One Host.** The household host on its own, without the app: a Docker image, a Compose
  file and a one-line installer (tools/host), built in CI and published to ghcr.io on release.
- **Sync without a household.** A device keeping its own profiles has its Sync page: each person's
  games, play time and the saves kept for them here, with what a host would add and the two ways
  to set one up.
- **Keys for the whole household.** SteamGridDB, TheGamesDB, IGDB, ScreenScraper and
  RetroAchievements keys added on one device reach every device through Fuse One, sealed like the
  RomM and Jellyfin sign-ins. A key removed on one device stays off that device only.
- **Fuse Intelligence.** Each emulator's page shows how far Fuse has checked it on this device:
  detected, installed, able to run, set up, and launch verified (a game started through Fuse ran
  in this install). Fuse learns where emulators keep their settings by looking, never touching:
  everything outside Fuse is seen through an interface with no way to open, write, move or delete
  a file, and what it learns stays in Fuse's own file. It looks again only when an install changes.
- **Stream from your computers.** With streaming on, the Library's All view has a shelf of every
  app the household's computers offer through Sunshine or Apollo, once however many computers have
  it, wearing the library's art when the library has the game. With several computers offering
  it, Fuse asks which, saying which are on, asleep (woken first) or not answering.
- **Haptics for what happens.** A light vibration with each notice, a firm pulse when something
  finishes well and a double knock when something didn't. On Windows and macOS the controller in
  use rumbles too, scaled by Settings, Input, Vibration.
- **Fuse's own files in Storage.** Beside the games, Storage shows the saves Fuse One keeps on this
  device (each file once, however many versions share it), the household's on a host, saves of
  profiles removed from this device, and the art and caches Fuse fetched. Nothing is deleted to
  make room: Fuse never removes the last copy of a save.
- **When transfers move.** Downloads has "Only while charging", "On battery, wait below" and
  "Quiet hours" beside Wi-Fi only, the bandwidth limit and the rules for while you play. A held
  transfer says what it waits for and carries on by itself. Saves always go: they are small.
- **Patches.** A game's options apply an IPS, UPS or BPS patch (a translation, a hack, a fix) as a
  new copy beside the game, which joins the library; the game itself is never changed. UPS and BPS
  patches are checked against the game they were made for, and one for another version is refused
  with both checksums rather than making a broken copy. A copier header is left off when the patch
  expects it.
- **Patches and cheats in the emulator.** A game's options turn on its emulator's own patches and
  cheats, read the way the emulator reads them: PCSX2's cheats beside its patches for PS2 games,
  DuckStation's patches (widescreen and other fixes, its bundled ones and your own) and cheats for
  PlayStation games, PPSSPP's cheats for PSP games, and RetroArch's for any game that starts there
  (the game's own cheat file, or the cheats RetroArch downloaded for it). Turning one on turns on
  the emulator's cheats for that game alone when they are off (in its game settings, or RetroArch's
  game override), and Fuse only ever turns off what it turned on: anything set in the emulator stays
  as it is, and Fuse says when a setting there (hardcore mode, cheats off for every game) keeps them
  from applying.
- **Emulator settings in Fuse.** A PS2, PlayStation or PSP game's options, and PCSX2's, DuckStation's
  and PPSSPP's pages in Settings, change the emulator's own settings: renderer, internal resolution,
  texture filtering, aspect ratio and the like, only those each emulator really reads, and for one
  game only those it takes per game. Each says whether it is the game's own or follows every game's.
  Fuse keeps what a setting held before it first changed it, and Put Back What Fuse Changed restores
  exactly that, leaving anything changed in the emulator since.
- **Going back after an emulator update.** When the Store updates an emulator on a computer, Fuse
  keeps the version it replaces in its own folder, out of the way of the new one. An update that
  fails to install puts it straight back; after one that works, the Store offers Go Back to it, and
  so does the problem Fuse shows when an emulator updated in the last month won't start. The
  emulator's own settings and saves are never touched.
- **Why an emulator didn't start.** When an emulator stops as it starts, Fuse reads what it wrote
  and says the likely reason in plain words (a missing core, missing firmware, graphics that
  couldn't start, a missing library, a file it couldn't open, a crash), with the fix that goes with
  it first: changing the renderer in its settings, checking the system's firmware, or choosing
  another core. The details hold the command, the exit code and its output, with the home folder
  taken out. When nothing it wrote says why, Fuse says only that.
- **Steam achievement rarity.** The share of players who have each Steam achievement, and when it
  was unlocked, under the chosen badge on a game's page.
- **Add a game folder.** A folder holding a game with its updates, DLC or discs, read the way the
  library scan reads a system's folders.
- **Updates and DLC.** A game Fuse took for its own can be made an update, DLC, patch or mod of
  another game of the same system, and back again.
- **Fuseline 4.1.** Cues: one clock that many parts of an entrance play from, so a page of tiles
  coming in is one frame callback. Fuseline is publishable on its own as an SDK
  (io.github.matiyaaa.fuseline:fuseline:4.1.0); see docs/fuseline-sdk.md.
- **Quick menu sections.** Along the top of the quick menu, as on Steam's: Quick settings (your
  grid), Downloads (what is moving with its progress, Pause everything, and what finished lately),
  Performance (the quality choice, Low Power Mode and the performance overlay) and Fuse (its own
  actions). LB and RB move between them, or a tap; the menu opens where it was left.
- **Simple and Advanced theme making.** The theme studio opens in Simple: light or dark, a colour,
  corners, the background, glass and the quick menu's look, in six steps. Advanced is the full
  studio, every colour, amount, motion and sound. Switching keeps what was chosen, and the studio
  remembers which you like.
- **The quick menu in your theme.** A theme says how the quick menu's tiles and widgets are drawn:
  Soft, Solid, Outlined or Glass (the `quickMenu` key in a theme file). Built-in themes each have
  the one that suits them, and the studio shows a quick menu on its preview while you choose.
- **Liquid glass.** With Glass on, every menu, sheet, dialog and notice that floats over the
  interface is a pane of glass over what is behind it: the screen frosted and its colours richer,
  bent along the pane's rim and lit around its edge, with a lighter tint. It is made small and drawn
  large, and refreshed about ten times a second at most, so it stays light; with Low Power, without
  a graphics card or before Android 12 it is the tinted pane it was.
- **A mark for Fuse One.** Fuse's own frame and fuse, the fuse rising into a 1 with the spark at
  its top, wherever Fuse One appears (docs/assets/brand/fuse-one-mark.svg).
- **Pictures of what a choice does.** Choosing the Library's view, what it opens on, what
  selecting a game does, game art, Home's style, text size, screen edges or motion shows a small
  moving sketch of the option under focus beside the list (above it on a narrow screen).
- **Calibrate a screen.** Settings, Accessibility (or Displays), Calibrate this screen: the
  interface's size, where the screen's edges are (corner marks move in until they sit inside a TV
  that cuts off its picture) and the text size, each seen as it changes. Kept for that screen
  alone, known again by its name and size; other screens keep the settings they share.
- **Game manuals.** A manual kept with a game (a PDF or a CBZ of page pictures) opens from the
  game's Updates and extras, read in Fuse page by page: LB and RB (or a swipe, or a tap at either
  side) turn the page, X zooms in steps (or a pinch, or a double tap) and the D-pad or a drag moves
  about, Y marks a page and A lists the marked ones. It opens where it was left, straight from the
  file, so it reads offline. PDFs are drawn by PDFBox on computers and by Android itself on Android.
- **For you, and a spotlight.** Two Home widgets: For you suggests games from your own library, each
  with why (a favourite left alone, the next game of a series you play, more from the maker or of the
  kind you play most, a game you left, a well rated one not started yet), and Spotlight shows one game
  a day with its reason. A system's page names its game of the day too (Settings, Library). Worked out
  on the device from the library alone; nothing about what anyone plays leaves it.
- **Your games in Steam.** A game's options, or a system's or collection's page, put games in Steam's
  library on a computer, each starting through Fuse (so with its emulator, saves and play time as in
  Fuse, and Fuse closes when it ends), with the art Fuse keeps for it and a Steam collection for its
  system. Fuse says first which are new, which are brought up to date and which another tool (Steam
  ROM Manager, EmuDeck) already put there, and leaves those alone. Settings, Library, Steam looks them
  over (games no longer in Fuse, entries for an older Fuse, doubles), takes them out, or undoes Fuse's
  last change. Steam is never closed for it: while it is open, the games go in when it next closes.
- **Arrange the top line in place.** Hold a tab (touch, mouse or confirm) and it lifts; move it
  along with the D-pad or a drag, as Addons' tabs move, and put it down. Home stays first.

## Changed

- **Fuse One** is the name of Fuse's household system throughout (profiles, Fuse Save Sync and the
  Fuse Library); every one of its settings is in Settings, Fuse One, in those parts. RomM is called RomM, and its games are in the
  Library's All view with every other game.
- **Library.** All, then Installed; All gathers this device, RomM and other devices as shelves,
  with Installed as one of a tile's own marks. Library opens on All, or on Installed when chosen in
  Settings. View As is gone.
- **Sync page.** How things stand is always in view, with the last saves as they went; history
  lasts across restarts and names the device; the top line is Sync Now, Library, Switch Profile
  and More.
- **Steam achievements** come from one Steam account only: the one chosen in Settings, Library,
  Steam (offered when Steam remembers more than one), else the one Steam remembers as most recent.
- **Controls.** "Detect my buttons", "Button layout" and "Swap confirm and back" are one
  "Controller setup".
- **Fuse Engine's Performance Governor.** Fuse knows whether it is in use, idle on screen, behind
  another window, running a game or just woken from sleep, and each has its rules: moving
  backgrounds slow down when idle and stop behind another window or while a game runs, and finding
  art and details by itself and looking for emulators wait while a game runs and for a few seconds
  after waking. Saves are never held back. The diagnostics report names the state.
- **Sound.** A master switch and volume over music and interface sounds.
- **Mouse.** Joystick-style pointers and tablets are no longer taken for controllers, hovering never
  scrolls a list, any pointer that isn't touch clicks once, and hints follow the mouse.
- **Game Mode on a TV.** Fuse says when gamescope enlarges its picture, with the fix, and keeps
  the anti-banding grain from showing as squares.
- The game page names the game's folder and file, small, at its end. Option sheets show Select
  and Back. Hidden or removed games never ask for an art choice.
- Developer, Start Onboarding can rehearse setup as Linux, Windows, macOS or Android.
- Developer, Emulator support lists every emulator Fuse knows for the system, installed ones first:
  its version, how Fuse knows to start it, what Fuse has checked for itself and what it does with it
  (games, saves, patches, cheats, settings), with a report to copy or save without folder or user
  names.

## Fixed

- Saves are found where emulators really keep them, checked against each one's own source:
  DuckStation's newer Windows folder (%LOCALAPPDATA%), portable settings.ini and its Flatpak's
  config folder, Eden's own Save Data and NAND directories (on Android too, with the setting to use
  named), Kenji-NX read as the Ryujinx
  it is built from, and RPCS3's dev_hdd0 where its vfs.yml moved it. Every other emulator was
  checked the same way: RetroArch's Mac default (Documents) and its by-content folders, Flycast's
  per-game VMUs (named by the disc's product number, never mixed with a shared VMU), Play!'s card
  (`vfs/mc0`), shadPS4's newer per-user save folder, Vita3K's portable folder and pref-path, Cemu's
  portable folder and mlc path, Xenia's home folder on Linux, Ryujinx's account save before its
  cache, Mupen64Plus's shortened save names, and the save folders named in MAME's and ScummVM's
  own settings.

- A choice made just as a profile switch or a sync round was being applied (a theme picked the
  moment after switching) is no longer thrown away; it stays the person's newest choice.
- A profile switch records the outgoing person's latest choices first; someone new to a device
  starts from Fuse's defaults, never the previous person's look.
- A PIN-protected profile opens offline against the PIN entered on this device before.
- Games that are another game's parts never show among removed games.
- Phone Link starts on the next port when its own is taken (by another program, or by connections
  that just closed), instead of never starting and never saying why.
- Restarting Fuse on a device that keeps its own profiles no longer shows the profiles for a moment
  with nobody chosen.
- The Controls page no longer crashes on its tabs.

## Acceptance

Tested by the automated suites in CI (Linux, with Windows and macOS packaging) and by rendered
screen audits. Not verified on physical hardware for this release: SteamOS Gaming Mode, the Decky
runtime and plugin installation on a real Steam Deck, the AYN Thor, Android devices, macOS and TVs,
controller rumble on Windows and macOS, and streaming real apps from Sunshine or Apollo (Fuse lists
the apps entered for each computer; it doesn't discover them). On Linux Fuse doesn't drive
controllers' motors.
The Fuse One Host image was built and its installer run in a container; pairing real Android,
Windows and macOS clients with it was not tested.
