# Fuse 0.4.2.11 - The Native Update

One game, known as the same game everywhere in Fuse; how long a game takes to beat, on its page;
achievements for many more discs; RPCS3 and shadPS4 patches; sound in recordings on a computer; and
Fuse updating itself on Windows and macOS.

## New

- **How long to beat.** A game's page shows how long it takes to beat, from HowLongToBeat, inside
  its play time strip, so the page is no taller: the main story's time, with how much of it you
  have played as a figure and a ring. Select it (with a controller, the keyboard,
  touch or the mouse) for the main story, main and extras and completionist times, each with your
  share played, to choose another game when HowLongToBeat found the wrong one, or to open it on
  howlongtobeat.com. Only the game's name is sent, its times are kept for a month, and Settings,
  Art and details turns it off.
- **Achievements for many more discs.** Fuse now works out the RetroAchievements hash, the way
  RetroAchievements itself does, for Sega CD, Saturn, Dreamcast, PC Engine CD, Neo Geo CD, 3DO,
  PC-FX, Atari Jaguar CD, GameCube and Nintendo 3DS games, and for compressed discs: CHD, CSO and ZSO, and Dolphin's GCZ, WIA and RVZ for
  GameCube. A 3DS game still encrypted is read with your own Azahar or Citra keys when you have
  them. Every one of them is checked against RetroAchievements' own tool. Wii games in RVZ, WIA or
  WBFS, and discs in 7z, RAR or ECM, are still found by name.
- **RPCS3 and shadPS4 patches.** A PS3 or PS4 game's Patches list now shows the patches RPCS3 or
  shadPS4 has for it (for its serial and version), and switches them on and off as the emulator's
  own Patch Manager does. As with PCSX2, nothing is turned on by itself, and Fuse only ever turns
  off what it turned on; patches you turned on in the emulator stay as they are.
- **Sound in recordings on Linux, Windows and macOS.** A recording of Fuse now has its sound: menu
  music, interface sounds and Fuse Player, as on Android. Picture and sound keep to one clock, so
  they stay together however long you record.
- **Fuse updates itself on Windows and macOS.** Restart and Update downloads the new version,
  checks it, and starts Fuse again in it, as on Linux and Android. On Windows the installer runs by
  itself once Fuse closes, without asking for an administrator, and the portable Fuse replaces its
  files, never touching FuseData. On a Mac the new Fuse.app takes the old one's place, which is
  kept: Settings, About can go back to it, and an update that doesn't start properly goes back by
  itself. Where Fuse can't change its own files (a Mac app still in Downloads, a folder it can't
  write to), the release page opens as before, and Settings says why.

## Changed

- **One game, the same game everywhere.** Fuse used to decide which game was which in five
  different ways, one each for the library, Fuse One and Fuse Sync, RomM, Syncthing, Reach and
  RetroAchievements, so a game could match in one place and not in another. They now ask one
  engine, which goes by what is surest first: a link you chose, then the game's content, then its
  serial (the one read from the disc ahead of the one in its file name), then its file name, then
  its name. A weaker match never joins two games that something surer says are different, two
  games sharing a name are never joined by it, and "Not this game" is never asked again. Every key
  an earlier Fuse saved play time and saves under still finds the game, so nothing synced before is
  lost.
- RomM now matches games by their content too: Fuse keeps the checksums it works out (for
  RetroAchievements and for RomM, only of files the same size as RomM's), so the two are found as
  the same game even when their names differ.
- The Sync tab now finds a disc game's saves by its serial, as everything else does, instead of
  by its name alone.

## Fixed

- A disc whose serial is only on the disc itself (not in its file name) was one game on a device
  that had read it and another on a device that hadn't, so its play time and saves didn't meet.
