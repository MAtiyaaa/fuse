# Fuse 0.4.2.15 - The Native Update

A download two-thirds smaller, and every achievement in one place.

## New

- **Every achievement on one page.** The Achievements page now shows everything, wherever it was
  earned, with no tabs by source:
  - RetroAchievements;
  - PS3, PS4 and Vita trophies, as RPCS3, shadPS4 and Vita3K keep them;
  - Xbox 360 achievements, as Xenia keeps them;
  - Steam's achievements.

  It has one row of recent unlocks and one shelf each of games in progress and completed, mixed by
  time. Each item has a small mark saying where it is from, and a trophy's grade in its own colour.
  The header adds it all up: everything you've unlocked, the games you've completed, and under that
  your RetroAchievements points, trophies by grade, gamerscore and Steam achievements.

  The page needs no account: RetroAchievements is offered as a quiet row at the end of it, never a
  gate.
- **Songs for offline.** Settings, Sound has "Songs on this device": download every song at once
  (about 40 MB) for playing offline, or remove the downloaded ones to free the space.

## Changed

- **A much smaller download.** The menu music was 69 MB of the 104 MB Android app, and as much
  again in every desktop package. Now:
  - The songs are re-encoded to Opus (in WebM), which sounds the same at about two-thirds the size.
  - Only two ship inside Fuse: puddleworld (the menu's song) and alright apothecary (setup's).
    That's 4 MB, so there is music from the first start with no network.
  - The other 22 download the first time they are wanted, from Fuse's own repository, and stay,
    so after that they play offline. Each is checked against its size and sha256 before it is
    kept.
  - Shuffle picks and fetches the next song while the current one plays, so there is never a gap.
  - Offline, shuffle picks only songs already on the device, and a chosen song that can't be
    fetched leaves the menu's song playing rather than silence.

  The Android app goes from 104 MB to under 40 MB, and each desktop package is about 65 MB smaller.
  On Windows, macOS and Linux the songs are decoded by Concentus, a Java version of the Opus
  decoder. Your own menu song still plays as before, MP3 included.
- **PS4, Vita and Xbox 360 unlocks travel with you.** Like PS3's, they join the
  game's history, which Fuse One carries to your other devices. A trophy earned on another device
  says where it was earned.

## Fixed

- The Achievements page said "No unlocks yet" whenever RetroAchievements had nothing, even with a
  shelf of trophies in RPCS3 or another emulator, and without a RetroAchievements account it showed
  only a button to connect one. It now shows what is there from every source.
