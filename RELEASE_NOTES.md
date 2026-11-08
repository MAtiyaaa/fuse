# Fuse 0.4.7.2 - The Cool Running Update

Switch 2 games in their own package, the .nup, are found and played.

## New

- **Switch 2's .nup.** A .nup, Switch 2's package holding a game with its updates, is a Switch 2
  game wherever it is, in a Switch 2 folder or not, and starts in Lichii on Linux, Windows, macOS
  and Android. Where a game is in several files, the .nup is the one Fuse plays.
- **Compressed Switch 2 games.** A Switch 2 game in .nsz or .xcz, the compressed .nsp and .xci,
  starts in Lichii too.

## Changed

- Switch 2 title ids (they start 04, as 04004860224DEC00) are read from file names as Switch ones
  are, so a Switch 2 game's updates and DLC are told from the game and its saves are found by its id.

## Fixed

- A Switch 2 file named with its title id was read as having none: its updates and DLC were only
  told apart by the words in their names, and its saves weren't found by the id.
