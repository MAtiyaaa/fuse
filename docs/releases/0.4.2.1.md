# Fuse 0.4.2.1 - The Native Update

More of the Native Update: Steam in Game Mode, a better Store, checks before an emulator is
installed or launched, more achievements, PS2 packs and cheats, the whole household's games in All,
and touch screens on Linux and Windows.

## New

- **Steam restarts in Game Mode too.** Adding, repairing or rebuilding Fuse in Steam, or adding a
  game to Steam, now works from Game Mode: Fuse asks first, closes Steam through a small helper of
  its own, writes Steam's library, lets Steam come back and opens Fuse again. It never does this
  while a game is running, and waiting for Steam to close stays the other choice. Not yet tried on
  a real Steam Deck.
- **The Store.** A spotlight, emulators grouped by maker, and every update in one place: the
  installed and available versions, size, changes and any warning for each app, Update All,
  per-app exclusions, and Stable or Prerelease where the app publishes both. Updates follow how an
  emulator was installed (Flatpak, AppImage), and ones your system manages are left to it.
  Cartridge is in the Store on Linux and Android.
- **Checks before installing and when a launch fails.** Fuse reads an emulator's program to catch a
  build for another processor, checks the processor features it needs (AVX2 for shadPS4, SSE4.1
  for PCSX2) and whether Vulkan is there, and says so in the Store before installing and in launch
  problems. Where it can't know, it says nothing.
- **Achievements from Xenia and trophies from Vita3K,** read from the emulators' own files, matched
  by the game's title id, with secret ones kept secret. Vita3K keeps its files private on Android,
  so they are read on computers only.
- **PS2 texture packs, patches and cheats from EmuCoreX's public catalogues,** matched by the disc's
  serial and CRC, never by name. Packs are checked against the catalogue's size and SHA-256, and
  packs whose makers gave no permission to share them are left to their own pages.
- **All means all.** All's count includes the games on RomM and on the household's other devices
  that this device doesn't have. Opening a system from All shows every game of it from everywhere,
  installed or not, each once, and systems only the other devices have join All's Systems shelf.
- **Touch screens on Linux and Windows.** A finger drags lists, shelves, grids, boards and pages and
  flings them when let go, a hold still opens a game's options, and nothing lights up as a drag
  passes over it. Compose Desktop is only told about the mouse, so a mouse dragged with its button
  held scrolls the same way. Not yet tried on a real touch screen.

## Changed

- **Every game on the household's other devices gets its details,** not only the newest, the one
  you open first.
- **Scraping providers keep a reserve:** when one runs low, what's left is kept for the games you
  open.

## Fixed

- **Resizing a widget with the controller no longer hitches.**
- **Series cards draw faster:** in the desktop benchmark, about 350 ms down to 230 ms a frame.
