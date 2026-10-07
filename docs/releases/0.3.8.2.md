# Fuse 0.3.8.2 - The Reach Update

Games folders named for anything, a Systems page that starts at the left, and a host that keeps up
with Fuse.

## New

- **Library list in the developer options.** Settings, About, five taps on the version, then Library
  list: every game Fuse found, by system, with its folder, file, title id, size, and the updates and
  DLC found with it, as text to copy or save (to share in a bug report about a game Fuse missed or
  read wrongly). It names your folders and files, and is shown before anything is copied or saved.

## Changed

- **A games folder can be called anything.** A folder Fuse doesn't know by name (`Games`, `My Games`,
  `Downloads`) is read by its files: one full of `.nsp`, `.xci` and `.nsz` files, in folders of their
  own or not, is a Switch folder. File types many systems share (`.zip`, `.iso`, `.bin`) never decide,
  and a folder mixing systems is left as it is.
- **One system, or a few, start at the left of the Systems page.** They used to sit alone in the
  middle of the screen; the board is centred only when its rows are full but for a sliver.

## Fixed

- **The Remote Library said the host needs 0.3.8 on the host itself.** A host that keeps running with
  Fuse closed ran the Fuse it was set up with, even after an update (on Linux, the old AppImage).
  Opening a newer Fuse now starts the host again on it, and on Linux and Windows setting the host up
  again restarts one that is already running.
- **The Remote Library's settings named the wrong computer.** On the host itself they now say its own
  host is starting again, instead of asking to update Fuse on the host computer.
