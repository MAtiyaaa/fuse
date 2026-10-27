# Fuse 0.4.5.1 - The Evolution Update

Downloads keep going while your device sleeps and carry on the moment it wakes, the Sync tab scrolls back up properly with a controller, and a second disc copied into a game's folder shows up by itself.

## New

- **Try Now for a waiting download.** A download waiting for the connection has Try Now in its
  options: it carries on from where it stopped at once, instead of in a moment.

## Changed

- **Downloads keep going while the device sleeps.** A download the connection dropped (Wi-Fi
  going quiet as the screen turns off) now waits to try again with Fuse still running, instead of
  letting the device sleep through every try. The Downloads notification says when one waits for
  the connection.
- **Downloads carry on when the device wakes.** When the screen comes back, the connection comes
  back or a computer wakes from sleep, waiting downloads try again at once, one left stuck by the
  sleep starts again from where it was, and one that gave up on the connection in the last day
  goes again by itself.
- **Game folders are followed.** A file added inside a game's own folder (a second disc, an
  update kept with the game) is in the library seconds later, as a game copied into a system
  folder already was.

## Fixed

- After the device slept, a download could sit failed until someone retried it, or wait up to a
  minute after the device woke before trying again.
- Pausing a download stuck on a dead connection could wait until the connection gave up. A pause
  now answers within ten seconds.
- On the Sync tab, going down past the games to your devices and back up with a controller left
  the page scrolled down with nothing chosen in view (most visible on an AYN Thor's upper screen).
  Up now scrolls back the way Down came, and what is chosen comes back into view.
- Sending a game to another device while a download ran could let go of the device when either
  one finished, though the other was still going.
