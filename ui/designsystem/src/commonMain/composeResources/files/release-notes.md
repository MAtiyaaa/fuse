# Fuse 0.4.4 - The Tune-Up Update

A tune-up from a whole-app audit. The Library's All view is rebuilt around what you can do with each
game, Settings are set out in cards with nothing in two places, RomM brings a game's files several at
once and checks them as they arrive, recompilations for the NES, SNES and Mega Drive start straight
from your own ROM, and the quick menu and Phone Link do more. On a two-screen handheld with the
menus below, the touch screen becomes a DS's, a grid of games to touch, while the screen above shows
the one chosen. Play time leaves out the time a device slept, a game has the device to itself while
it runs, and Low Power turns fully off again.

## New

- **Library, All, rebuilt.** The chosen game sits in a compact stage with its art, its name, where
  it is and its system, and Play (or Open, or Stream) and Options buttons a mouse or a finger can
  press. Where your games are (this device, RomM, your other devices, the computers that stream) is
  a row of cards, each with its count and whether it answers now, each opening its whole list.
  Jump Back In leads the shelves. Tiles are sized to the screen, from a phone to a TV, pointing a
  mouse at a game chooses it, and pointing at a shelf shows arrows to page through it. An empty
  library offers to add a game folder.
- **Recompilations start from your own ROM.** Thirteen NES, SNES and Mega Drive recomps in Fuse
  Ports (Super Mario World, A Link to the Past, Star Fox, F-Zero, Super Mario Bros., Metroid, Sonic
  the Hedgehog, Sonic 3 and more) are handed your own ROM from the library by its path, checked by
  the CRC32 or SHA-1 their port checks, and start straight into the game instead of opening their
  own launcher or file picker. Your file is never changed. Sonic the Hedgehog's Linux build, which
  has no picker at all, now starts from Fuse.
- **The quick menu knows what you're playing.** While a game runs it opens on a Playing section:
  the game and how long it has run, its saves, its emulator settings, its updates, DLC and mods, a
  screenshot, and syncing its save now. New tiles: Continue (the last game, in one press),
  Downloads (what is moving and how far along; hold it to pause or resume everything), Sync Now,
  Achievements, Favourites, Mute and Text Size.
- **Phone Link does more.** A phone sees Fuse's own downloads and uploads (RomM's, the household's,
  the Store's) with their progress and pauses or resumes them all, shows a game's page on the
  device from that game on the phone, marks favourites, and asks the device to look for new games.
  Phone Link may now open games in Fuse; starting one is still never the phone's.
- **Home below, as a DS has it.** With the menus on the touch screen of a two-screen handheld, Home
  there is now a grid of game icons that runs sideways, a ring round the chosen one, under a row of
  filters to touch (Recent, Favourites, New, All, then each system by its logo). A system's filter,
  chosen, fills with its colour and picture from the middle out, gives a small bounce and a glow, a
  light runs across it, and it says how many games the system has. The first touch chooses a game
  and the screen above shows it; a second touch, or Play, starts it, and a hold opens its options. A
  slim bar shows where the grid is and slides it under a finger, and a dock along the foot holds the
  chosen game's cover and name, Options, the icons' size (one row of large icons, two or three, kept
  for the device) and Play, each with its controller button. The D-pad moves through the grid, the
  right stick changes shelf and the triggers page by a screen. The touch screen keeps the theme's
  own room, lit softly in the chosen game's colour, and leaves the game's art to the screen above.
- **GIFs that move.** Choose a GIF (or an animated WebP) as a game's or a system's background
  picture, as its logo, or as your theme's picture behind everything, and it plays wherever it is
  shown: in a system's filter on the screen below, on game pages, in the Library and on Home. A GIF
  logo keeps its own colours. Moving pictures hold their first frame under Reduced motion, in Low
  Power Mode and on the second screen while a game runs.
- **More on the screen above.** On a two-screen handheld with the menus below, a game keeps its
  few lines about it, its series, this week's play and what plays it. With nothing chosen, the
  week's play sits beside the clock with a bar a day, and a system shows its games, those played
  last first.
- **Saves for each port, on each system.** A Fuse Ports game's saves are now its own on each kind of
  system, so a Linux build's save never lands in its Windows build's folder. Android ports and
  recomps have saves of their own too, from the folder chosen for each in Settings, Save Folders.
  A save made before this is taken once, by whichever system plays it next.
- **Emulator settings on Android, a press away.** An emulator that keeps its settings in its private
  Android folder (BachataS4, shadPS4, DuckStation) offers to open itself to change them, instead of
  stopping at saying Fuse can't reach them.

## Changed

- **Settings in cards.** Each group of settings sits on a card of its own with its name above it,
  and each section's heading carries its mark. Fuse One's page no longer repeats Playing As and
  Profiles, which lead Settings, Profiles.
- **Low Power is one switch.** It was both a Performance profile and the Low Power Mode switch, and
  turning one off left the other on. The profiles offered are Automatic, Balanced and High Quality,
  and a Low Power profile an older Fuse saved reads as Low Power Mode on.
- **Faster RomM.** A game's files come four at a time, so a folder game of tens of thousands of
  small files (a PlayStation 4 game) no longer spends most of its time between them. Every download
  is hashed as it arrives instead of read back in full afterwards, and written a megabyte at a
  time. An upload sends its next piece as soon as one is in, never waiting for the slowest of a
  batch.
- **A game has the device while it runs.** Under a game, a video preview pauses, the second screen
  keeps its picture but stops moving, Fuse One's, the household's, Jellyfin's and RomM's rounds hold
  and catch up when the game ends, and Fuse's own pacing ticks every thirty seconds. The game's save
  still goes as it is written. With no household transfer moving, Fuse no longer looks every two
  seconds.
- **Posters everywhere.** With Game Art set to posters, search results, Play Time, a collection's
  picker, Storage, Uploads and the Fuse Library's fanned covers show posters too.
- **Ports look like Ports.** A game played with its port (an Android recomp, or a computer's Play
  With) shows Ports' icon, logo and colour on Home, the library's backdrop, the second screen and
  tile borders.
- **The Store and Addons on handhelds and phones.** On a short screen the Store's header is one band
  with a smaller spotlight, so its apps show on the first view; on a phone held upright, Addons,
  Ports' filters scroll and its buttons take a line of their own.
- **Folder games are counted as RomM counts them.** Every file at every level counts, so a PS4,
  PS5 or PS3 dump shows its full size (Bloodborne's 29 GB, not a tenth of it), and RomM's dlc/ and
  update/ folders inside a dump become the game's content. The first scan after updating reads
  every folder again to put sizes right.
- **NFSMW Recompiled leaves Android's Ports.** Its project now releases only for Windows, so there
  is no Android app to install. NFSMW Android Evolved still brings Need for Speed: Most Wanted to
  Android.
- **A shorter README.**

## Fixed

- A game opened for a few minutes and closed before it saved no longer says Fuse found no save for
  it. A play under five minutes that leaves no save says nothing.
- Play time no longer counts the time a handheld's screen was off or a computer slept with its game
  open, and a session Fuse didn't see end ends where Fuse last saw it going.
- RetroAchievements progress shows offline again: answers past their time are kept for sixty days
  instead of purged at every start.
- A system's settings page opens on Android when none of its games are on the device (a PS4 whose
  games are all on RomM).
- Turning Low Power off brings back video, blur and moving backgrounds.
- On the screen above, the glow behind a game's cover is no longer cut square when the game
  changes, and on the AYN Thor at its largest text a game's page no longer runs up under the top
  line.
- On a phone held upright, Home's rows keep the chosen game's wide tile inside the screen.
- Phone Link says only how big a transfer still waiting its turn is, instead of "0 B of" it.
