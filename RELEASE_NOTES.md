# Fuse 0.4.3.12 - The Overdrive Update

A PS4 game from RomM no longer closes Fuse on Android.

## New

- Nothing new in this release.

## Changed

- A RomM game of tens of thousands of files downloads whole, as one archive, instead of file by file.

## Fixed

- Opening a PS4 game from RomM closed Fuse on Android. A game kept as a folder of tens of thousands
  of files (as PS4 games are) had a file list too big for Android to read back from Fuse's copy of
  the RomM library. Such a game now keeps no file list and downloads whole, and lists already kept
  that are too big are dropped the first time Fuse opens its RomM library.
