# Fuse 0.3.2 - The Corner Update

## New

- **The menus travel between screens.** On a two-screen device, switching between Fuse and Flipped
  no longer blinks: the menus sink toward the other screen and shrink a little as they go, and
  arrive on it from the edge facing the first, settling with a soft spring, while what takes their
  place rises in behind them. Both screens move as one picture.
- **Hello, whoever is playing.** Switching profile now plays an arrival: the person's avatar
  colours bloom out from the middle of the screen, their avatar springs up with "Hi" and their
  name, and the whole thing gathers itself into their avatar at the top right as their games,
  saves and Home arrive behind it.

## Changed

- **Who's playing opens gently.** The room settles in from a touch larger, and the people's cards
  rise into place one after another.
- **Whole names on small widgets.** A widget's name, date or figure that doesn't fit is set smaller
  instead of being cut short, so "Continue playing" reads in full even on the small screen.

## Fixed

- Home's carousel widgets showed a square corner at the top right of the card in front, where the
  shade behind the widget's name poked out past the card's rounded corner. It is cut to the card's
  shape now.
- On a small screen, a carousel's dots ran into its name and into the game's system chip. The dots
  now sit at the card's top right, and the system chip just under them.
- Jellyfin's widgets stayed empty when they were on a Home page other than the first, and when the
  server didn't answer at first. They now load on every page, and reconnect and ask again soon
  rather than waiting five minutes.
