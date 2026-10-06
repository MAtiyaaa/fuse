# Fuse 0.3.6.1 - The Unity Update

A quick fix for Fuse RomM with large libraries, PS4 and PS5 games above all: the library comes in
quickly, carries on where it stopped, and is kept for next time. Fuse's first four-number version.

## New

- **Test Connection on the Fuse RomM tab.** It asks your home and outside addresses whether RomM
  answers, says what it found, and connects again when one does.
- **PS4 and PS5 games find their folders.** RomM keeps them as zips, but they are played from the
  folder they unpack to: Fuse now matches that folder by the zip's name or by the game's own id
  (CUSA12345, PPSA12345), as Cartridge does.

## Changed

- **The library comes in quickly.** Fuse reads your RomM library without every game's file list
  (a PS4 or PS5 game kept as a folder can list tens of thousands of files) and brings a game's
  files the first time you open or download it, then keeps them.
- **Kept for next time.** A first read of the library that stops part way (Fuse closed, the
  server went away) carries on from the page it reached instead of starting again, and once it is
  done only what changed is asked for.
- **A slow server is slow, not gone.** A page RomM takes too long to put together is asked for
  again in smaller ones, and a slow or unusual answer no longer counts as the server not answering.
- Fuse's version can now have four numbers; installers on Windows and macOS upgrade in place.

## Fixed

- Fuse RomM said "The server isn't answering" and stopped at 250 games while the server was
  connected, on libraries with large PS4 and PS5 games.
- Every start of Fuse read the RomM library from the beginning again until a full read had once
  finished.
