# Fuse 0.4.2.25 - The Native Update

Lighter on handhelds while games move between devices, and clearer words when the other devices'
games aren't there.

## New

- Nothing new in this release.

## Changed

- The Fuse Library no longer gathers every game of every device again each time the list of
  transfers changes (every few seconds while anything moves): it follows only which transfer is
  which and how far along it is, so a handheld downloading stays cooler.
- A game page finds RomM's copy of another version by name once a minute, instead of reading the
  server's whole list every time a transfer or a device changes.
- Without the other devices' games, the Fuse Library says it is asking the host for them and
  where to see why it can't, instead of always asking for the host to be updated.

## Fixed

- A request another device turned down named that device twice in Downloads ("Atiya HTPC
  couldn't: Atiya HTPC can't see this game").
