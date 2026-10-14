# Fuse 0.4.2.12 - The Native Update

Fuseline 4.2: Fuse starts fast and stays smooth with thousands of games, art and details on every
one, on every system. And Fii, a new theme in light and dark with a Home of its own.

## New

- **Fii and Fii Night.** Two new themes: soft white and brushed silver (or deep slate at night), a
  clear sky cyan, slate text, round glossy tiles and glassy bubbles rising slowly behind everything.
  Game and system tiles sit in rounded white (or silver) frames with a gloss across the top, the
  focus ring breathes while it rests, and the interface sounds are round, airy notes that rise a
  little as they start.
- **Fii's Home.** Choosing Fii or Fii Night the first time unlocks a Home of its own and asks
  whether to use it: your board as glossy channels, with the time and date along a bar at the
  bottom, between two round buttons (Fuse's sections on the left, the quick menu on the right).
  Your tiles stay where they are. Fii's Home then sits in Settings, Home, Home style beside Fused
  and Network, marked Unlocked until you look, and works with every theme in that theme's colours.
  Fused keeps Fuse's own look with Fii's colours. Every switch of Home style is asked first, and
  leaving the Fii themes while on Fii's Home asks whether to go back to the Home you had.
- Community themes can use the new pieces: the `fii` background, the `bubble` sounds and
  `"tiles": "gloss"` (docs/THEMES.md).

## Changed

- **Fuse opens fast with a big library (Fuseline 4.2).** Measured with 2,348 games, each with five
  pieces of art and a description, on a 4-core computer drawing without a graphics card: Home
  showed its games after 10.1 seconds before and after about 4.6 seconds now. With the class data
  sharing archive the AppImage carries (and now the Windows and Mac builds too, below), the first
  frame comes at 1.6 seconds, Home at 3.0 and the first art at 3.9. The parts that changed:
  - Finding series no longer slows down as the library grows (it took 70% of the start at this
    size), and it waits until Home has settled.
  - The library's work happens away from the thread that draws the screen, on every system.
  - When art arrives or changes, only the games it belongs to are read again, never the whole
    library (before, each piece of art filled re-read the art of every game).
  - "For you" picks read only the details they use, never every game's description.
  - Series, suggestions, the RomM refresh and the automatic scan start once Home has settled, so
    they never compete with the first screen.
  - The opening animation is decided before the first frame, so Home no longer shows for a moment
    before it.
- **Faster starts on Windows and macOS too.** The installer, the portable zip and the Mac app now
  carry the same class data sharing archive the Linux AppImage has: the classes Fuse needs to start
  are ready to map in instead of being read out of its files. Java checks it at every start and
  goes on without it if anything doesn't match.
- Fuse writes one line to its log at every start saying where the time went (the window, the
  library, the first frame, Home, the first art), so a slow start on any device says why.

## Fixed

- Scrolling the Library while art was still arriving re-read the art of every game on each new
  picture, which held the scroll back on large libraries.
- With 2,000 games, the series pass ran for seconds on the screen's own thread on a computer,
  freezing Home while it opened.

Android: the same library, start-up and drawing code runs there, and Fuse's own code is still
compiled ahead of time at install. It wasn't measured on a device for this release.
