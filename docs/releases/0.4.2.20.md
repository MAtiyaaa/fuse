# Fuse 0.4.2.20 - The Native Update

A fix for importing saves from a Switch.

## New

- Nothing new in this release.

## Changed

- Nothing changed in this release.

## Fixed

- A Switch save was left out of the import when Fuse knew the game by another title id than the
  backup's: another region's copy of the game, or an id taken from an update's or DLC's file name.
  Tomodachi Life: Living the Dream was one of them. A backup of a game with exactly the same name is
  now offered for it, and its save goes where the emulator keeps this copy's.
