# Fuse 0.4.6.1 - The Continue Update

RomM's games each once and by their own names, Jellyfin's films and shows at a size that fits the screen and quicker to come, and every pill and See all easy to read.

## New

- **Which system, where a name is on several.** Where a list mixes systems (every game on RomM, a
  collection, Library's shelves), a game whose name another system has too says its system on its
  tile: Deadpool on PlayStation 3, PlayStation 4 and Xbox 360 reads as three games, not one three
  times.

## Changed

- **RomM's games by their own names.** Where RomM gives games on one system the same name (several
  games matched to one, a game's discs kept apart, one game kept in two formats), each is named by
  what tells it apart: its file's own title, else its disc, region, revision or format. Four Splinter
  Cells are Splinter Cell, Pandora Tomorrow, Chaos Theory and Double Agent again.
- **Filters read the same everywhere.** The filters on Android Ports, Ports and Downloads are one
  kind of pill: the chosen one in the theme's own text colour, filled and ringed while the row has
  the controller, and readable in every theme.
- **New on RomM sits with the rest.** A game new on the server says so in the small mark at its
  tile's foot, beside the cloud, instead of a badge over its art that ran into its other marks.
- **Jellyfin's posters fit the screen.** A library's grid sizes its posters to the screen's height
  too: a handheld's short upper screen shows a row and the start of the next, a phone held upright
  three across.
- **Jellyfin's pictures come sooner on a computer.** Up to sixteen pictures from one server come at
  once, as on Android, instead of five at a time.

## Fixed

- Going down the Add a widget gallery and back up with a controller stopped short of the top: the
  sheet's title and its first heading stayed out of view. Up to the first line brings them back.
- See all's words were white on the first game's own cover, unreadable on a bright one, and on a
  small tile its arrow sat over them: a shade behind them keeps them clear, and the arrow follows
  the words.
- A place's card and See all fanned their newest games' covers, so a RomM server's newest games,
  with no art yet while RomM still identifies them, made RomM's covers look as if they hadn't
  loaded. Games with art lead them now.
- A copy left unfinished on the RomM server ("Game.iso.part") showed as a second copy of its game.
  It is left out.
- Jellyfin could say it couldn't reach the server, and only Refresh brought the page: a connection
  dropped while an answer was read wasn't asked again, a moment's error from a proxy in front of
  the server (a Cloudflare tunnel) counted as the server's, and a load called off as the connection
  settled counted as one that failed. A first page that doesn't come is now asked for again by
  itself before the page says so.
- In what's new on Jellyfin, a show said "2 seasons" where it had two new episodes: it now says new
  episodes.
- A system's tag on a tile, where a border carries it, had light words on a light colour in light
  themes.
