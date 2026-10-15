# Fuse 0.4.2.14 - The Native Update

Lists glide instead of jumping, tile art is ready before it scrolls in, every part of a game's page
is in reach, the start-up sounds as it should, and Fii, Duo and Trio are gone.

## New

- **A sound at the start-up's climax.** After the launch sound, the start-up now plays a second
  sound as the wordmark burns in: a quick run up into a wide chord that rings out. The first-start
  opening does the same: the launch sound as the mark lights, and the climax as the tiles fly out
  from it. Both are made by Fuse's own synthesizer, in your interface sound.

## Changed

- **Shelves and lists glide.** Moving down the Library's All view, the shelves now glide to the
  next one with Fuseline's follow spring, measured on every frame. Before, the page snapped across
  in a couple of frames while the shelf you left closed its name line above, so it jumped and
  sometimes went past. A shelf that already shows whole still doesn't scroll. The same glide now
  moves:
  - the rows of every shelf, from RomM, other devices and streaming;
  - the household and streaming pages;
  - Downloads, offline Jellyfin and the sync pages;
  - the sectioned game grids.
- **Tile art is ready as you scroll.** The art of the next rows was being decoded ahead of time,
  but at a slightly different size, and fitted where the tile crops it. The memory cache only hands
  an image to a request of the same size, so every tile was decoded again from disk as it scrolled
  in and showed an empty frame first. Art is now decoded ahead exactly as each tile asks for it, in
  the Library's grids and on Home's shelves, so a quick scroll down Installed shows art straight
  away.
- **The start-up's sounds.** The match strike, the flare and the swell from 0.4.2.13 are gone.
  The start-up plays its launch sound as the spark bursts again, as it did before them.

## Removed

- **Fii, Duo and Trio**, light and night, with their Homes, backgrounds, glossy and panel tiles, and
  the Bubble, Pip and Tap sounds. Anyone using one of them is moved over the first time this
  version starts:
  - a removed theme becomes Fuse;
  - a removed Home becomes the Fused board, with every tile where it was;
  - a removed sound becomes Soft.
- Community themes that name the `fii`, `duo` or `trio` background, a removed sound or `tiles` still
  load. The parts Fuse no longer has fall back to the theme they start from, and the note says so.

## Fixed

- **On a game's page, How long to beat couldn't be reached** when it wrapped under play time on a
  narrower screen. Down acted as if it were beside play time and left the row. Play time and How
  long to beat now move by where they actually are, as the buttons above them have since 0.4.2.13.
  The same goes for the disc buttons and RomM's download buttons when they wrap.
- **On a game's page, coming back up to play time left it off screen.** The page only ever scrolled
  down to it, so Up from Available on stopped on play time with the page still showing what was
  below. It now scrolls back up until play time's top is in view.
- **The theme filters overlapped.** Choosing Dark or Bright opened the count beside it while the
  next filter slid back to where it had been, so for a moment the pill ran into the word after it
  ("Dark 16Bright"). The filters now make room as the count opens. Tabs still slide aside when they
  are put in another order.
- The README's pictures and tour showed the removed themes. They are left out, and the tour is cut
  again from the Library, a game's page, Systems and Jellyfin.
