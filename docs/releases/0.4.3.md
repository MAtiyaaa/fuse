# Fuse 0.4.3 - The Steady Update

A Library that opens quickly on a computer without a graphics card, art found once for the whole
household, Power and heat in System health, themes with tiles of their own, a shorter setup, and
Linux that looks and plays more the way Android does.

## New

- **Power and heat**, in Settings, System health: the battery (and how warm it is), how warm the
  device is and whether the system is slowing it down, the rate Fuse asks of the screen right now,
  whether Fuse lets the device sleep or what it is keeping it awake for, and Low Power Mode a press
  away. Search finds it ("battery", "heat", "drain").
- **"It launched" in the diagnostics report.** Beside the launch problems, the report now lists the
  last ten games that started: which emulator (and core), how Fuse handed the game over (the Android
  app and activity, or the program it ran), the kind of file it was given, and how long it took from
  your press to the emulator taking it. A report then shows what works, not only what didn't.
- **Art found once for the household.** Each device's list now carries the art it chose for each
  game from SteamGridDB, IGDB, TheGamesDB, ScreenScraper, Libretro and GameTDB, and its details.
  Another device with the same game (the same serial, household game, RomM game or files) takes them
  as they are before asking any provider, so a household searches each game once instead of once a
  device, and the providers' daily allowances go as far as one device's would. Only filling does
  this; Replace still asks anew. Art you picked yourself, a folder's own pictures and RomM's art stay
  on their device.
- **Tiles of their own for every theme.** Themes now frame their game and system tiles differently,
  not only in other colours: **Gloss** sets the art in a rounded frame with a gloss across the top
  and a focus ring that breathes (Glass, Starlight, Lagoon, Opal, Fused), **Panel** is a flat card
  with a thin grey frame (Pitch, Blades), **Ink** a firm outline in the text colour (Ridge), **Neon**
  lines each tile in the accent and glows as you land on it (Orbital, Sundown, CRT), and **Mounted**
  sets the art in a border of the panel colour, like a print (Daylight, Blossom, Canopy, Noon). Fuse,
  Fusi, Olive, Paper Mint, Crossbar and Wave keep theirs. The Theme Studio has a Tiles row, and
  theme files take `"tiles"` (docs/THEMES.md).
- **Video previews on Linux, Windows and macOS.** After resting on a game, its art turns into a
  muted gameplay clip behind the interface, as on Android, played by Fuse Player. Off when drawing
  without a graphics card, and in Low Power Mode.
- **Game Mode, said plainly.** When Steam's Game Mode draws Fuse at a handheld's size and enlarges
  it to a TV (which softens every letter), System health now says so with its badge, and how to fix
  it in Steam (Fuse's Properties, Game Resolution, Native).
- **Controllers on Linux are known by their maker.** The button labels follow the pad's USB vendor,
  so a DualShock 4 (which calls itself "Wireless Controller") shows PlayStation's shapes. In Game
  Mode, where every pad reaches Fuse as Steam's virtual one, the labels follow the pads Steam wraps
  when they are all one maker's.

## Changed

- **The Library opens quickly on a computer without a graphics card.** Measured on a 4-core
  computer drawing on the processor at 1080p with 3,000 games: switching to the Library took 617 ms
  a frame (1,433 ms for its first) and now takes 286 ms (349 ms for its first). The parts that
  changed:
  - Fuse's database on Linux, Windows and macOS closed its connection after every read, so the next
    read opened the file again and read the whole layout of the library back before it could start.
    The connection now stays open, as Android's always has.
  - Opening the Library read every game's art up to six times over just to count them (the counts
    on its tabs and segments). Counts and the list of what arrived now read only what they need.
  - Without a graphics card, the room behind the interface (the game's art cropped to the screen,
    its shade, the scrims, the glow, and the lit room of a game without art) is drawn once into a
    picture of the screen's size and copied each frame. Drawn anew every frame, it cost more than
    the rest of the Library together.
  - Without a graphics card, only the tile you are on casts a shadow (as on Android), not every tile
    at rest.
- **Smoother first scrolls.** Finding art by itself now waits for a pause in your presses instead of
  working under a scroll. On a first start, with every game's art still to find while Android has
  yet to compile Fuse, that work running under the scroll was what made it stutter.
- **Stronger text on Linux.** Android's text renderer thickens light letters on a dark room; Skia on
  Linux doesn't, so the same text came out thinner and greyer there, worst on a TV across the room.
  On Linux, reading text is now drawn a weight heavier and muted text a little brighter.
- **A shorter setup.** "Tuned for this device" is gone (the Performance step at the end covers it),
  the BIOS step comes only when a system needs firmware, art, achievements and Jellyfin are one step
  with Continue first, "Which button confirms?" is asked only with a controller in hand, and "When
  you choose a game" stays as Fuse comes (Settings, Library, Selecting a game changes it).
- **Every setting can be searched.** 67 settings search couldn't find (the glass and CRT tuning,
  repeat speed, master volume, max refresh rate, interface size, the second screen's options and
  more) are found by their own names now, and open their fold. The Apps tab's setting is called
  "Apps tab shows first" (it read "Apps opens on", like the second screen's "Apps open on").
- The roadmap is up to date: checked on real handhelds and on real computers, and a storage mode
  without All files access and signed Windows and macOS builds are no longer planned.

## Fixed

- "Touch controls on the second screen" did nothing at all (the second screen's touch controls
  always work): the switch is gone.
- Low Power Mode said it held the screen at 60 Hz; it never touched the refresh rate, which stays
  as Settings, Display sets it.
