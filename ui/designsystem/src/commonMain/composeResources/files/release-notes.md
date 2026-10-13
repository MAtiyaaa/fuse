# Fuse 0.4.2.6 - The Native Update

RomM's save sync, one clear choice for how each game's saves sync, a game's options in four short
tabs, and RomM covers that load on computers. RomM was tested against a real RomM 5.3 server (sign
in, every system recognised, covers, a game brought down, and saves moving between two devices);
nothing here has been tried on a real handheld or TV yet.

## New

- **RomM save sync.** On RomM 5, games from RomM (or matched to it) keep their save in step with
  RomM's: RomM's newer save is in place before a game starts, and this device's goes up after it
  closes. Fuse keeps its own record of what it last agreed with RomM, so a device that started a game
  without the save can never put its new save over the real one (RomM alone would have); when both
  changed, you choose, and the one you don't pick is kept. A blank save is never sent, each upload is
  a new save on RomM with the earlier ones kept, and the device a save came from is named. Saves that
  are one file (cartridge saves, memory cards) take part, since RomM keeps one file per save. Turn it
  on in Settings, Addons, RomM, or when connecting RomM (in setup too).
- **Save Sync for each game,** in its options and its Save History: Everywhere, Receive Only Here
  (this device gets the newest save but never sends its own), Off on This Device, or Off Everywhere
  (for all your devices, through your profile). Fuse One and RomM both follow it.

## Changed

- **A game's options are four short tabs** (Game, Saves, Setup, Details) instead of one long list,
  switched with the shoulder buttons or a tap, as in the quick menu.
- **A two-finger swipe on a Mac trackpad no longer turns Home's pages,** nor does a sideways mouse
  wheel; touch, dragging, the controller and the page dots still do.
- **Pairing with RomM also asks for its save permissions,** so save sync can be turned on later without
  pairing again.

## Fixed

- **RomM covers load on computers.** RomM gives its pictures' addresses with a space in them, which
  the server refused (400) as Fuse sent them; they are now encoded.
- **A RomM download no longer shows as failed when only a picture couldn't be had.** The game was in
  place, but the whole download said the connection kept dropping.
