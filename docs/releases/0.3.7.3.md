# Fuse 0.3.7.3 - The Motion Update

Systems can be made small, as small as a game's box art, with a picture of each console; RomM
uploads finish; and the second screen follows what Downloads has in focus.

## New

- **Small systems.** A system on the Systems page can now be a small square, the size of a game's
  box art. A small system shows a picture of the system itself (its console or handheld) on a
  patterned background washed in that system's own colour, like a shelf of systems. Resize one by
  its handles or hold Options and use the D-pad: narrower down to the small square, wider a card at a
  time, taller and back again. While arranging, Options offers
  Make Every System Small and Make Every System a Card.
- **Change a small tile's look.** Options on a system, Small Tile, changes its picture (every model
  and colour of that system the picture set has, or its logo instead), its pattern (dots, grid,
  stripes, waves or plain) and its colour (its own colour, its colour strong, plain or dark). Reset Small Tile
  puts Fuse's own back, and Settings, Systems, Reset small tiles does it for every system.
- **System size.** Settings, Systems, System size fits one more system in each row of the Systems
  page (Smaller) or two more (Smallest). Every system narrows a little and the rest move up to make
  room. When small squares leave room at the end of their rows, the page is centred.
- **The second screen follows Downloads.** With Downloads open, the second screen shows the
  transfer you are on: its game's art and logo, and where the achievements card sits for a game, a
  card with how it is going. It shows whether it is downloading or uploading, a progress bar, how
  much has moved, how fast and how long is left, or what it is waiting for.
- **A look for each screen on two-screen devices.** On a device with two screens, the Systems page
  is kept once with the menus on top (Fuse Mode) and once with them below (Flipped). Their sizes and
  order are each their own, and changing one never changes the other. While arranging, a small
  switch beside Add system arranges the other screen's look from this one, shown as it will look
  there. Flipped also has its own System size, so the lower screen can hold four systems in a row
  instead of three. None of this is mentioned on a device with one screen.

## Changed

- **Arranging systems moves the rest along in order.** Moving a system now moves the systems
  between where it was and where it goes along by one place, in reading order, the way a phone's
  home screen does. Putting a system on its neighbour swaps the two; nothing jumps to a far corner.
  With the controller, left and right trade places with the system before or after, and up and down
  move to the row above or below.
- **The Systems page fills itself in at any size.** Places come from the systems' order and sizes,
  so a smaller System size, a new system or a resized one never leaves gaps or overlaps. An
  arrangement made before keeps each system's size and its order.

## Fixed

- **RomM uploads never finished.** Each piece of an upload had 30 seconds to arrive and RomM had
  30 seconds to put the game together, which a large game or a home connection from outside never
  managed, so every upload stopped with "RomM is taking too long to answer". Each piece now has time
  for its size on a slow connection, RomM has up to half an hour to put a game together, and an
  upload that is slow waits and carries on from the piece it was on instead of failing. When RomM
  finishes putting a game together after Fuse stopped waiting, Fuse sees the game there and counts
  the upload as done, so it is never sent twice.
- **Arranging systems on the AYN Thor moved everything around.** Moving one system pushed the ones
  in its way down the board and the ones under them further down, so a few moves scattered the
  page. Systems now keep their order (see Changed).
