# Fuse 0.4.2.19 - The Native Update

A fix for sending Switch saves from your phone.

## New

- Nothing new in this release.

## Changed

- Nothing changed in this release.

## Fixed

- Switch saves sent from your phone as a ZIP of the JKSV folder found nothing to import. The ZIP's
  own name was counted on top of the JKSV folder inside it, and the backups JKSV keeps as ZIPs
  inside each game's folder weren't opened. A ZIP of the JKSV folder now imports just like the
  folder itself, and what a Mac adds to a ZIP (`__MACOSX`, `._` files) is left out.
- A Switch save kept in a hidden folder, as Balatro keeps its save in `.lovegame`, was taken for an
  empty backup and left out. It now imports, and it syncs with the rest of the game's save.
- Sending a JKSV folder from a computer's browser left out JKSV's `.nx_save_meta.bin` files, so
  games were matched only by name. They are now sent too.
