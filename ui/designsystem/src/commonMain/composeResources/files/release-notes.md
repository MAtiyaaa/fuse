# Fuse 0.3.0 - The Clean Update

## New

- **A quick menu that's yours.** Press X in the quick menu (or the pencil, or Edit quick menu) to
  arrange it: A picks a tile up and the D-pad carries it, X changes its size, Y takes it out, and
  Add puts things back, or everything as it came. By touch or the mouse, drag a tile where it
  should go, with corner buttons for size and removal. The others glide out of the way.
- **Widgets in the quick menu.** Now playing shows the menu music or what Fuse Player by Fuse is
  playing, with skip back, play or pause and skip forward: the song slides out the way you skipped
  and its art turns over. Brightness and volume are bars across the menu or tall tiles. New items
  to add: Surprise me (a random game), Search, Themes, Phone Link, Play time, Standby, Frame times
  and Full screen.
- **The second screen in three words.** The quick menu's Second screen is Off, Fuse (your games on
  the screen below) or Flipped (your games on the screen above), side by side.
- **Every system ES-DE knows.** 86 more systems, from the Apple II, VIC-20, PC-98 and X68000 to
  the CD-i, Game & Watch, Sega Model 2, Model 3 and ST-V, TIC-80, WASM-4, Doom and Quake, under
  RomM's names, each with ES-DE's folder names and file types, RetroArch's cores where it runs
  them, RetroAchievements where it covers them, and libretro thumbnails. 157 systems in all.
- **Jellyfin in setup.** A new step says what Jellyfin is for anyone who hasn't met it (a free
  media server at home that keeps your films, shows and music in one library) and connects it
  right there: servers found on your network, or a typed address, then your name and password.
- **Swipe back on touch screens.** On Linux, Windows and macOS, swipe in from either edge to go
  back, as on a phone: an arrow follows your finger and lights when letting go will go back.
- **Pages above, too.** With the menus below (Flipped), the screen above has the same three pages
  as the screen below: what's chosen, Status and Controls.
- **Fuse Player by Fuse rides out weak Wi-Fi.** It starts on a short buffer and keeps a deep one,
  retries a flaky connection, and when the connection can't keep up it lowers the quality by itself
  and carries on from the same second, saying so in a small note.

## Changed

- **The second screen's Status and Controls pages, redesigned.** Status leads with the battery
  large in a ring, then a grid of measures (processor, memory, storage, Wi-Fi), each with its own
  meter. Controls has full-width sliders with what they set beside them, and control-centre tiles.
- **The second screen keeps its pages while a film plays.** The remote is its first page, and
  Status and Controls are a swipe away.
- **Fuse finds renamed emulator AppImages on Linux** by the release they update from, read from
  inside the AppImage without running it.
- **Existing systems read more file types**: archives for disc systems, MSU-1 and MSU-MD sets, more
  C64, MSX, ZX Spectrum and Atari images, and ES-DE's other folder names for them.
- PS5 game folders (sce_sys/param.json) are one game each, like PS4 and Vita folders.
- Right from Settings or Search in the top line goes into the page instead of opening Search, and
  moving left from Search goes to the last tab.
- Addons goes away when Jellyfin, the Store and Cartridge are all off. The Store has its own
  switch in Settings, Addons.
- About's credits lead with Fuse Player by Fuse and Fuseline by Fuse.
- The README, rewritten.
- Every screen is rendered and checked at 23 sizes, from a 3.5 inch 4:3 handheld to a 32:9 monitor
  and a 4K screen at 100%.

## Fixed

- Fuse Player's settings, audio and subtitle sheets couldn't be closed by touching outside them.
- Moving left from Search in the top line went to Achievements rather than the last tab (Addons).
- The second screen's Controls page cut off the target chips and its tiles on a wide lower screen.
