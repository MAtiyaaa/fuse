# Fuse 0.4.7.4 - The Cool Running Update

Switch 2 games already in your folders show up after the update, without a full rescan.

## New

- Nothing new in this release; it fixes how 0.4.7.2 and 0.4.7.3 found Switch 2 games.

## Changed

- The first scan after updating reads every folder again, once, so files Fuse has newly learned to
  read are found. Your edits are kept.

## Fixed

- A Switch 2 game (.nup, .nsz or .xcz) in a folder Fuse had scanned before 0.4.7.2 wasn't found:
  the folder hadn't changed since, so the scan Fuse runs on its own passed it by. Only Full rescan
  found it.
