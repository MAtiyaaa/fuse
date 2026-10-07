# Fuse 0.3.8.1 - The Reach Update

Switch libraries read the way people really keep them: every game once, with its updates and DLC,
however the files are named and wherever they sit.

## New

- **Updates folder and DLC folder.** A Switch system's settings (Files) can name a folder its
  updates are kept in and one for its DLC, anywhere. Both are optional: Fuse finds updates and DLC
  beside their games, and in `updates` and `dlc` folders inside the games folder, without them.
- **.nsz and .xcz files** are Switch games, updates and DLC too.

## Changed

- **Switch names are read however they are written.** With or without a title id, Fuse tells a game
  from its update and DLC: versions (`v1.1.3`, `v524288`, `Update 1.0.3`, `(Update 1.0.1)`,
  `[Up v1.86]`), "DLC" anywhere in a name, a DLC's name in brackets (`Game [Hat DLC]`,
  `Game [New Uniform Set]`), and an update named with its game's own id. Words that only describe
  the file (`Base eShop NSP`, `Switch XCI Base Game`), scene and download-site names, copy numbers
  like `(1)`, and look-alike punctuation no longer get in the way.
- **Updates and DLC join their game wherever they are.** In the game's own folder, beside it, in one
  big folder of many games, in `updates` and `dlc` folders, or in the folders chosen in settings,
  each update and DLC is listed with its game. One whose game isn't there stays on its own, so
  nothing disappears.
- **Copies count once.** The same file in several folders (the same title id or name, version and
  size) is one game, kept from the folder of its own. Another region, another version or one file
  holding a game with all its updates and DLC stays a game of its own.
- **Uploading a Switch game to RomM brings its updates and DLC.** They go into `update` and `dlc`
  under the game, as RomM keeps them, from wherever they are on this device, and each is sent once.

## Fixed

- **Copies and updates no longer show as missing.** Files Fuse once listed as games of their own,
  and now lists with their game, are forgotten after the next scan instead of shown as missing,
  unless you made one a favourite, played it or renamed it.
- **Uploading a game kept in base, update and dlc folders stopped.** Its game file is now sent first
  as the game, and the rest goes under it.
