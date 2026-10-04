# Fuse 0.2.6 - The Detail Update

## New

- **A Store on Linux, Windows and macOS.** Emulators and companions are fetched from each project's
  own GitHub releases, exactly as their developers publish them: AppImages only on Linux, portable
  zips on Windows, apps from their disk images or zips on macOS. Nothing is repackaged and no
  installer is run. Programs land where Fuse finds emulators (~/Applications, or ~/Emulators on
  Windows) and are used right away. Programs whose builds live on their own sites are listed with a
  way there.
- **Add any app to the Store by its GitHub address**, on any system. It goes on the Other shelf, or
  a shelf you pick, and Fuse checks that its releases really hold an app for your device first. On
  Android the catalogue can also follow a fork of the Obtainium Emulation Pack.
- **Move games to an SD card or another drive.** From a game's options, or many at once in Storage
  (select a system, then "Select all shown"). Each game lands in its system's folder on the other
  drive and leaves the first one only once its copy is whole; play time, favourites and art stay
  with it. Progress shows in Storage like a download, with the time left.
- **New SD cards and drives are noticed.** Fuse asks once whether to set one up for games: an
  Emulation folder with a ROMs folder for every system and a bios folder, wherever you choose,
  added to your library. Say no and it never asks about that drive again; Storage can set it up
  later.
- **Achievements beyond RetroAchievements.** Steam games show their Steam achievements, read from
  Steam on your computer with no account or key, and PS3 games show their RPCS3 trophies, on the
  game page and the second screen. RetroAchievements now also covers SuperGrafx, PC-FX, MSX2, ZX
  Spectrum, Amstrad CPC and Neo Geo.
- **Search in Settings.** A search row above Appearance jumps straight to any setting; Settings
  still opens on Appearance.
- **Setup, rebuilt.** A guided setup that matches the rest of Fuse and looks right on every screen
  from a handheld to a 4K TV, with a chapter rail, live previews of what each choice does, a choice
  between starting games straight away and opening their page first, and on a computer, your Steam
  games found across every drive (each one yours to add or leave).
- **Game Mode and SteamOS.** Fuse opens full screen in Steam's Game Mode, can add itself to Steam,
  and starts Cartridge directly there.
- **A Theme Studio that shows what it changes.** Each choice lights up the part of the preview it
  affects. Your own picture can be the background, with its dimming and position. Recent colours
  are a press away, and Y takes back the last change.
- **Standby.** Left alone for a while (5 minutes unless you choose otherwise), Fuse fades to a dark
  screen with a drifting clock, so an OLED screen never holds a still image. Any button wakes it.
- **A new album for the menu music:** creature interchange by boipurple, fourteen songs, with a
  Shuffle switch that plays through every song.
- Firmware Fuse can't find (it is somewhere Fuse can't look, or named its own way) can be marked as
  set up for its system, and its warnings go away. Taking that back is one press.
- "Remember where you were": each tab keeps its place when you come back to it (on by default).
- The game page has a time-together strip: how long you have played, this week's share, sessions
  and when you last played.
- The status area at the top can be reached by moving right from Settings, and shows an Ethernet
  icon when you are on a cable.
- The battery icon plays a short charging flourish when you plug in.
- Back after a long time away (the device slept), the startup animation plays again.

## Changed

- Cartridge's uploads look like its downloads: the game's art beside its title, a progress bar,
  sizes, and the time left. Downloads show their percentage and time left in the same place.
- "Connected to RomM" is no longer shown; only a lost connection is.
- Many tags on a game page fold into a "+" that lists them all.
- Screenshots on a game page open full screen, and can be swiped.
- A favourite's heart is filled, so a hearted game looks different from one that isn't.
- The Library's grid shrinks a little as it scrolls, so three full rows are always in view.
- The "Play on which screen?" question shows the game's box art.
- Storage is clearer on every screen size: compact drive cards on short screens, a "Select all"
  row, and a way to set up any drive that has no games folder.
- On a computer Fuse opens in a window sized to the screen; Game Mode still opens full screen.
- Bottom hints can be tapped.
- Holding Backspace deletes faster the longer it is held, a word at a time after a moment, as on a
  phone.
- The Favourites widget opens the Library on Favourites.
- The second screen's volume follows your finger smoothly, and its "Playing for" line has room
  from the logo.
- The Store's buttons and chips wrap onto another line instead of running off a narrow screen.

## Fixed

- After an update, Fuse could reopen on the bottom screen. It opens on the main screen.
- L1 out of Settings went to Addons instead of Apps.
- Scrolling up with a stick in Appearance never showed the theme card at the top, and the same in
  Storage's list of drives.
- With the music volume at zero, a screen recording slowed down. Silent music now pauses, and a
  recording keeps time even when no sound comes.
- Coming back from Cartridge, the Apps tab wasn't highlighted.
- A game page's last rows and its description couldn't be reached with a controller.
- A game renamed after RomM mixed it up with another kept the other game's description. Renaming
  now looks for the right details under the new name.
- The newest game in Cartridge's "Just arrived" had no box art or background until you left the
  page; its tile now picks up art as it arrives. The red focus line under those tiles sat over the
  game's name.
- A PS Vita game in a .zip (such as "Uncharted - Golden Abyss.zip") wasn't found when its files sat
  deeper in the archive or used compression Fuse doesn't read.
- Automatic series missed series with only two games, games without details whose names start with
  a series, and names written with accents.
- The "Upload to RomM" notice stayed up long after the upload was done.
- Buttons and their focus rings were cut off at the edges of setup on some screens, and Install
  Cartridge was available in setup on Windows and macOS, where Cartridge doesn't run.
- On the AYN Thor, closing the lid didn't let the device sleep, and its screensaver let Fuse take
  controller presses underneath it.
- Input during the startup animation went to the screen behind it. Any press now just skips it.
- In developer mode, the startup animation played the menu music over itself.
