# Fuse 0.4.2.16 - The Native Update

Saves from a modded Switch, one game at a time, and every emulator in the Linux Store.

## New

- **Saves from your Switch.** Import Saves has "From a Switch". Choose one of these on your
  Switch's SD card:
  - the JKSV folder;
  - Checkpoint's `switch/Checkpoint/saves` folder;
  - a single game's folder.

  Fuse reads every backup there and then takes you through them one game at a time:
  - **Is this the game?** Each card shows the game's art, which save manager made the backup,
    the Switch user it came from and when it was made. When there are several backups, the
    newest is chosen and the others are one press away. A backup known only by its folder's
    name says so, so you can check it.
  - **Whose save is it?** Choose one person, several, or everyone. Each of them gets their own
    copy in Fuse One.
  - **Ready to import** shows every game, whose save each becomes and where it goes, before
    anything is written.

  The saves go into Eden (or another yuzu-family emulator, or Ryujinx) exactly where it keeps
  them, for the user it plays as. A game's device save goes to its own place, and when you import
  only one part of a save, the other part already on this device is kept. Nothing on your SD card
  is changed.
- **Eden in the Store**, on Linux and Windows, from Eden's own server (its GitHub was taken
  down). A Steam Deck gets Eden's Steam Deck build and a ROG Ally its Ally build.

## Changed

- **Every Linux emulator is in the Store, installed by Fuse.** Before, PPSSPP, Dolphin and RetroArch
  only linked to their websites, and many emulators Fuse can start weren't listed at all. Now:
  - **AppImages** for PPSSPP, Dolphin, AzaharPlus, Citron and Panda3DS.
  - **Archives:** RetroArch from Libretro's build server, and Redream from its own page. Mandarine,
    DOSBox Staging, Ruffle, SharpEmu and Xenia Canary (now on Linux too) come as tarballs. Fuse
    takes the AppImage from an archive when it holds one, else puts the program's folder in
    Applications as it is.
  - **Flathub**, for the emulators that publish no build of their own: Ryujinx, MAME, Mednafen,
    ScummVM, DOSBox-X and Play!. Fuse installs them with Flatpak for your account, and they update
    and remove like any other Flatpak.
- **The Emulators tab shows the emulators on this computer**, without Installed and Not Installed
  tabs. Installing one is the Store's job. With none installed yet, the tab leads to the Store.

## Fixed

- Fuse didn't find Mandarine or Citron when their AppImage wasn't named exactly `mandarine-qt…` or
  `citron…`, as their newer builds aren't. Any AppImage with the emulator's name in it is found now.
