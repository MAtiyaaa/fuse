# Fuse 0.4.2.24 - The Native Update

Fixes for a device that saw none of the other devices' games and couldn't be sent any.

## New

- Nothing new in this release.

## Changed

- Fuse One counts each device's calls on its own. Every device at home reaching the host through
  one address (a tunnel, one public address) shared one allowance, and a busy one could leave the
  others turned away.
- When the other devices' games can't be read, the Sync activity says why, and says so again
  once they are back.

## Fixed

- A device could show none of the other devices' games, only RomM's, and refuse every game sent
  to it with "can't see this game on any device":
  - If the host didn't answer the first moment after Fuse started (a television in Game Mode
    starting before its network), the Fuse Library stayed off for as long as Fuse ran. It now
    tries again every half minute.
  - A list of another device's games kept from before that had gone wrong was never sent again,
    because it carried the version the host had. Every list is now read in full each time the
    device connects.
  - A game sent to a device that hadn't read the lists yet was refused. The device now reads
    them again and waits a moment first.
- A computer that stopped being the Fuse One host but still had the host's service started it
  again every few seconds. It now stops.
