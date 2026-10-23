# Fuse 0.4.3.11 - The Overdrive Update

PS4 games from RomM download, and the Store offers a PS4 emulator for Android.

## New

- BachataS4, the PS4 emulator for Android built on shadPS4, is in the Store (from its own GitHub
  releases), so a PS4 game has something to play in. Fuse starts games installed in it by their
  title id. shadPS4 has no Android build to download; installed, Fuse opens it.

## Changed

- Downloading a game from RomM no longer waits on an emulator for its system being installed.

## Fixed

- A PS4 game (or any game RomM hadn't listed the files of) wouldn't download from RomM: Fuse said
  it needed RomM to list the game's files first. It now asks RomM for the whole game by its name.
