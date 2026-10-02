# Fuse 0.2.0 - The Everything Update

## New

- **Drives Fuse understands.** Every library folder now remembers the drive it lives on: an SD
  card, a USB drive, an internal disk. Each system reports its drives with a stable identity
  (Linux mount and disk ids, Windows volume serials, macOS disk info, Android storage volumes).
  - A card or drive that is out is **offline, never deleted**. Its games stay in the library, dimmed
    with a drive mark, and none of them is marked missing, even when the drive is pulled in the
    middle of a scan.
  - A drive that comes back under another path (E: becomes F:, /run/media becomes /media) is
    followed, and every game, disc, manual and picture on it moves with it in one step.
  - Playing a game on a drive that is out says which drive to connect. Deleting games refuses while
    their drive can't be checked.
  - Storage lists real drives by name and kind, offline ones too, with what each holds; choosing a
    drive shows only its systems and games.
- **System health** in Settings: what needs attention, most serious first, each with what to do.
  It checks library folders and their drives, systems without an emulator, emulators that went
  away, firmware Fuse knows is missing, games pointing at an emulator that isn't installed, missing
  game files, every disc a playlist names and every track a cue or gdi sheet lists, rejected
  provider keys, a failed update download and a nearly full drive. A system's page leads with its
  own findings, and a game's page shows its own. Anything Fuse can't check is left out, never
  guessed.
- **Diagnostics report** for bug reports: versions, device, systems, emulators, drives, findings
  and recent launch problems, shown in full before you save or copy it. Personal folder names,
  keys, passwords and tokens are taken out, and nothing is sent anywhere.
- **Problems that explain themselves.** When a game can't start, a sheet says what happened, why,
  that nothing was changed or deleted, and offers the fix: choose an emulator, connect the drive,
  allow file access, open the folder, try again. Technical details are one press away.
- **Safe mode.** If Fuse fails to start three times in a row, it starts in safe mode: its own theme,
  reduced motion, no glass, CRT, video or music, and nothing running by itself. Your settings are
  untouched, and leaving safe mode turns everything back on. You can also ask for it: `--safe-mode`
  on a computer, or the Start in safe mode shortcut on Android.
- **Backup and restore.** Settings, Storage and backups makes one `.fusebackup` file with your
  settings, the look and Home, what you changed on each game (names, favourites, emulators,
  systems, details), collections, the art you chose (with its pictures) and play time. Restoring
  shows what the file holds and how many of its games are in this library, then brings back
  everything or just one part.
  - A restore merges and never deletes: what the backup sets wins, what it leaves unset never
    erases anything, and games on another card or folder are still found.
  - Settings, the look and Home can be put back as they were before the last restore.
  - A backup never holds games, firmware, keys or passwords.
- **Search that understands filters.** Type `platform:snes`, `year:1990s`, `favorite:yes`,
  `played:week`, `genre:rpg`, `developer:capcom`, `collection:"Couch games"`, `drive:sd`,
  `missing:yes` or `hidden:yes`, alone or with words. Each filter becomes a chip you tap to take
  off.
  - With nothing typed, search offers filters to start with; while you type one it lists its values
    from your library (your systems, genres, collections and drives), so a controller never has to
    spell them.
  - Names are found by their start, a word, their initials (`mgs`) or with a small typo (`zelad`).
  - Settings are searchable too, by their names and the words people use for them ("rumble" finds
    Vibration), and open on the right row.
- **An emulator's own page.** Settings, Systems and emulators opens each one: how and where it was found, the
  systems it runs and which it's chosen for, how Fuse starts it, its limits, and its website. **Try
  it with a game** starts your most recently played game on its systems in it, without changing
  anything.
- **Why an emulator can't run a game.** A game's emulator list checks each one against that game
  and says why one can't open it (the file type, a folder it can't take, not installed).
- **Play time.** A page with today, this week, this month and all time, the last 30 days as bars,
  your most played games this month and time per system. Open it from a game's Play history card
  or the play time widgets.
- **Text size and screen edges.** Settings, Accessibility has Text size (Default, Large, Extra
  large) for reading from the sofa, and Screen edges, which keeps everything clear of edges a TV
  cuts off while the background still fills the screen.
- **PlayStation disc details.** For PS1 and PS2 games in ISO or BIN images, the game page shows the
  serial read from the disc itself, and for PS2 discs the CRC PCSX2 files its patches under.
- **PCSX2 patches.** A PS2 game's options list the patches PCSX2 has for it (widescreen, 60 FPS and
  more), from its patch files and its bundled patches, with who turned each one on. Fuse turns
  patches on in PCSX2's own settings for that game and only ever turns off the ones it turned on.
  Patches you set in PCSX2 are shown and left exactly as they are. Fuse keeps a copy of PCSX2's
  file as it was the first time it changes it.
- **Installing PS3 and Vita packages.** A PS3 or Vita game's options install its `.pkg` files (the
  game, its updates and extra content kept beside it) with RPCS3 or Vita3K. Vita3K's zRIF key is
  asked for each time and passed only to Vita3K: Fuse never keeps, logs or sends it.
- **How a PS3 game runs in RPCS3**, from RPCS3's public compatibility list, with a link to the
  report. Fuse asks rpcs3.net only when you choose it, sending only the game's title id.

## Changed

- **Settings, reorganised.** Twelve sections instead of twenty, under Personalize, Games, This
  device, Connections and General: Accessibility gathers text size, screen edges, motion and focus;
  Systems and emulators, Screen and sound (with performance), Accounts (RetroAchievements, Cartridge,
  Phone Link), Storage and backups, and About (with updates, privacy and network) each bring related
  settings together. Rarely changed settings fold away under a line that says how they stand, tuning
  can be put back to its defaults, and the list marks findings, a waiting update or a rejected key.
  Nothing was removed, search opens folded settings, and older links land in the right place.
- Before an older database is upgraded, Fuse keeps a copy of it next to the original.
- On Android, Fuse's library starts when its screen first shows, so the system starting Fuse in the
  background never counts as a failed start.
- Start failures say what they mean, and on Android offer Android settings and the Home app
  chooser.
- Script and shortcut openers (the Linux shell script and `.desktop` runner, Windows programs, Mac
  apps) are no longer offered as emulators for cartridge and disc systems.
- Saving and opening files (backups, the diagnostics report) use the system's own dialogs on a
  computer and the document picker on Android.
- The top line shows a steady chip in safe mode and when something is broken; notes and things that
  only need attention stay in System health.
- 305 Lucide icons (from 167).
- Release builds can be shrunk with R8 (`-Pfuse.r8=true`): the Android APK goes from 78.0 MB to
  50.2 MB. It stays off by default until a shrunk build has been run on devices.
- CI checks that the version, release notes, RELEASE_NOTES.md and the changelog agree.

## Fixed

- A system with no emulator showed "Desktop shortcut" (and on Windows and macOS, the program and app
  openers) as its emulator, so System health and the Systems page thought it was ready.
- A Cartridge change announced while Fuse was reading Cartridge's status could be lost until the
  next one.
- Settings changed in quick succession could have an older value saved over a newer one; only the
  newest is saved now.
