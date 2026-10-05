# Fuse 0.3.0 - The Sync & Clean Update

## New

- **Fuse Sync by Fuse.** Your saves, save states, play time, library and settings, the same on every
  device you play on, from a computer of your own at home. Make one computer the host in one step
  (it keeps running when Fuse is closed and after a restart, if you like); every other device finds
  it on the network and joins with its code. A game's newest save is in place before it starts and
  kept after it closes, matched by the game rather than its path, so a ROM named differently on a
  card still gets its save. Play time adds up across devices, offline too. When two devices both
  played, Fuse asks which save to use and keeps the other, and every save keeps its versions, from
  every device, to go back to. Off, it does nothing at all.
- **Profiles.** Everyone on a host has a profile, with one of Fuse's own avatars and a PIN if they
  want one: their own library (favourites, names, collections, play time), Home, theme and quick
  menu. Who's playing? opens from the avatar at the top right; switching changes everything at
  once, without a restart. Fuse can start as the last profile, ask, or always start as one.
- **Home on this device, or everywhere.** Arranging Home, a switch beside Add widget and Done keeps
  this Home to this device or makes it the profile's on every device. Settings has the same choice.
- **Addons, Sync.** Fuse Sync at a glance: where it stands, who's playing, every device on the host
  and what happened lately, with Sync Now, Switch Profile and Add a Device.
- **A game's save history.** In a game's options, every version of its saves from every device:
  put one back (what is here is kept first) or keep one for good.
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
- **PlayStation 5, and a better PlayStation 4.** PS5 games start in SharpEmu (Windows, Linux and
  macOS) or KytyPS5 (Windows), from a dumped game folder. PS4 and PS5 games show their own art
  (the console's square tile and full-screen backdrop from sce_sys) before anything is scraped, an
  update folder beside its game (CUSA00900-UPDATE, shadPS4's layout) is that game's update rather
  than a second game, and shadPS4 is found as a Flatpak and under its newer AppImage names. PS5
  emulation is young, so Fuse says so on the emulator's page.
- **Fuse Player by Fuse rides out weak Wi-Fi.** It starts on a short buffer and keeps a deep one,
  retries a flaky connection, and when the connection can't keep up it lowers the quality by itself
  and carries on from the same second, saying so in a small note.

- **Home has pages.** In Channels, Home can be several boards side by side, like a phone's home
  screens, each arranged on its own. The right stick (or [ and ] on a keyboard), a swipe, or the
  D-pad run off a board's side turns the page, and dots under the board say which one shows.
  Arranging, New page adds one; an empty page says how to fill it, and a page can be removed.
- **The right stick works.** Fuse reads it on Linux, Windows, macOS and Android; for now it turns
  Home's pages.
- **A remote that feels like one.** The remote on the second screen (and on the touch screen in
  Flipped mode) is redesigned: the film's backdrop, its logo, a live dot while it plays, a glass
  deck with the timeline and the transport, Play in a ring that fills as the film goes on, soft
  presses under a finger, and Sound, Subtitles, Play here and Stop in one bar.
- **Your phone is the controller you're holding.** Fuse now knows a DualSense from an Xbox pad or a
  Pro Controller (by its name, or on Android by who made it). Hints show PlayStation shapes or
  Xbox letters for the pad in your hand, and a phone used as a controller through Phone Link
  relabels its buttons to match, live, when you pick up another one.
- **Touch controls for a film on the other screen.** Playing a film on the screen above in Flipped
  mode (or on a second screen), the controller stays with the menus, so the picture answers to
  touch: a tap shows Stop, skip back, play or pause, skip forward and a timeline to drag, and they
  fade again while it plays. A double tap on either side skips.

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
- Typing on a hardware keyboard, or using the mouse, Fuse's on-screen keys step aside: the field
  takes what you type, Enter finishes, Esc closes, and Show keys brings them back. A controller
  press or a touch brings them back too.
- Add widget only offers what you use: RetroAchievements' widgets once it is connected, Cartridge's
  while it is on and installed, collections while they are on, Jellyfin's while it is on.
  Achievement widgets wait off Home until RetroAchievements is connected.
- Addons opens on its first tab, in the order you dragged them into.
- The second screen's page name sits in the middle of the screen, above and below.
- A film's or show's logo and backdrop on the second screen are fetched before you reach it, for
  what is in focus and its neighbours on either side, so they show at once.
- About's credits lead with Fuse Sync by Fuse, Fuse Player by Fuse and Fuseline by Fuse.
- On a small 4:3 handheld, the Capsule layout keeps its game title to one line instead of running
  under the toolbar.
- The README, rewritten.
- Every screen is rendered and checked at 23 sizes, from a 3.5 inch 4:3 handheld to a 32:9 monitor
  and a 4K screen at 100%.

## Fixed

- A film's or show's page on the second screen could be hard to read with a light theme: it is
  now always shown as a cinema shows it, light text on a darkened backdrop.
- Fuse Player's Back button couldn't be reached with the controller: Up from the timeline goes to it.
- Jellyfin opened scrolled past Search, Refresh and Settings.
- Fuse Player's settings, audio and subtitle sheets couldn't be closed by touching outside them.
- Moving left from Search in the top line went to Achievements rather than the last tab (Addons).
- The second screen's Controls page cut off the target chips and its tiles on a wide lower screen.
