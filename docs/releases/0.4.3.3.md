# Fuse 0.4.3.3 - The Overdrive Update

A question for Switch saves of a game Fuse can't tell, recomps offered only for their own ROM,
Decky's Steam port left alone, and recomp folders that can't be reached past.

## New

- **"What game is this?" for Switch saves.** When a JKSV or Checkpoint backup is of a game Fuse
  can't match to any of yours (another title id, and a name in its folder that isn't your
  library's), the import no longer passes it over. It asks which game it is first, showing what the
  backup says (its folder's name, its title id, whose it is and when it was made) above your Switch
  games, the likeliest first, with Show Every Game for the rest. Choose one and its saves go where
  the emulator keeps that game's; Leave It Out skips them.

## Changed

- **Recomps are offered for their own ROM.** Play With offers an N64 recomp only for the very ROM it
  is made from, known by its hash as the recomp checks it, now read from inside a zip too. A ROM
  that is another revision, another region or a hack is offered none. When Fuse can't read the ROM
  (a 7z, a disk image), the recomp of the same game is offered marked Unverified, naming the
  cartridge it is made from (Zelda 64: Recompiled is made from the North American 1.0 Majora's
  Mask). A PlayStation port offered by the game's name, with no title id to check, is marked the
  same way.

## Fixed

- **Turning off Change Steam While It Runs closed Decky's port.** Fuse took away the file that
  opens Steam's debug port even when Decky Loader's installer had put it there, and Decky uses that
  port too. Fuse now marks the file it writes, takes away only that one, and none while Decky
  Loader is installed. Off still means Fuse stops using the port; when the port stays open for
  Decky or another program, Fuse says so, with the file to remove if you want it closed.
- **A recomp's folder can't be used to reach past it.** Fuse reads and writes a recomp's settings,
  mods and controls only in that recomp's own folder, but a symbolic link inside the folder that
  pointed elsewhere could take a read or a write past it. Folders are now compared where they
  really are, every link followed, and a recomp folder that is itself a link (as dotfiles managers
  make) still works.
