# Fuse 0.4.2.17 - The Native Update

A fix for importing saves from a Switch.

## New

- Nothing new in this release.

## Changed

- Nothing changed in this release.

## Fixed

- Importing saves from a Switch could stop with "Nothing imported: invalid CEN header (duplicate
  entry)". JKSV sometimes lists a file twice in a backup ZIP, and Fuse refused the whole ZIP. Fuse
  now reads these backups and imports the newest copy of each file.
