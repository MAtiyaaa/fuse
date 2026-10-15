# Fuse 0.4.2.18 - The Native Update

Switch saves for every game in your library, a RetroAchievements account for each person, a game's
updates and DLC from your other devices, and a sharper, steadier picture on Linux.

## New

- **Switch saves for every game, not only the ones here.** With Switch games on Fuse One that this
  device doesn't have, importing from a Switch first asks **Which games?**: **This Device**, or
  **Every Game**. A save for a game that isn't here yet goes where the emulator looks for it, so it
  is ready the first time you play. Each game says whether it is on Fuse One only, and whether you
  already have a save of it.
- **A RetroAchievements account for each profile.** Connect one in Settings while you are that
  profile: switching profiles shows that person's achievements. A profile without its own account
  shows the first one connected on the device, as before.
- **Updates and DLC from another device.** When another of your devices has updates or DLC for a
  game that this copy doesn't, the game's page offers **Get 2 updates and 1 DLC** (or whatever it
  has). Only those files come over, beside the game, which stays as it is.

## Changed

- **One card per game in the Switch import.** A game's device save comes along with its account
  save instead of being a second card, and a game Fuse found twice is shown once.
- The Switch import's buttons stay on screen, however long the list: **Import** is always in reach
  with a controller.
- **This copy** on a game's page (the card at the end, formerly On this device) shows where the
  game is, with Choose What to Launch and Folder Behaviour for a game that is a folder. It no longer
  lists every other file in the game's folder, and it no longer repeats the developer, publisher,
  series and region shown at the top.
- A folder that holds several games opens with **List Each as Its Own Game**, which shows each of
  them in your library (nothing on disk changes).
- In Library, All, **Left** from a shelf's first game goes straight to its **See all**.
- On Android, a game tile at rest no longer casts a shadow; a selected tile still lifts with one.
  Scrolling a grid no longer works out a shadow for every tile on every frame.

## Fixed

- A Switch save of a game whose file doesn't carry its title id (an XCI, or a renamed NSP or NSZ)
  was left out of the import, as Tomodachi Life was. Fuse now matches it by name and takes the id
  from the backup.
- Names numbered as a list, where the game's own name starts with a number ("27. 7th Dragon",
  "29. 999 - 9 Hours, 9 Persons, 9 Doors", "60. 007 - The World Is Not Enough"), kept the list's
  number, so their art and details weren't found. Rescan the system to fix names already in your
  library.
- On Linux, the backgrounds' fine grain moved with the drifting lights and flickered. It now stays
  still, and Fuse no longer lays a second grain over the whole window, text included. Text is
  drawn with light hinting, so letters keep their shapes. In Game Mode on a handheld's own screen
  the grain is no longer made as strong as on a TV.
- On Linux, a screenshot came out black in Game Mode: Fuse now takes it from its own picture.
- In Game Mode, after Exit Game left Fuse stuck, starting Fuse again did nothing until you went to
  the desktop and back. A Fuse that doesn't answer is now replaced by the new start.
- The first screenshot on a game's page was cut off on the left when selected.
- The Decky plugin's panel couldn't be scrolled to the end with a controller.
