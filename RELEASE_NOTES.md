# Fuse 0.4.3.7 - The Overdrive Update

The Store works again with the Dual-Screen edition: the Obtainium Emulation Pack is now one pack
for every device, and Fuse follows it.

## New

- Nothing new in this release.

## Changed

- The Store's Edition setting says that the Obtainium Emulation Pack is one pack for every device
  since its version 8, its dual-screen builds in it.

## Fixed

- The Store couldn't load its catalogue with the Dual-Screen edition, as if the Obtainium Emulation
  Pack were down. The pack was fine: on 12 October its version 8 dropped its dual-screen file and
  ships one pack for every device, with the dual-screen builds (WatermelonDS, Cemu (DS), Mjolnir,
  ES-DE Companion, Jarngreipr) in it. Fuse still asked for the dual-screen file, which no longer
  exists in its releases or on its main branch. Now the Dual-Screen edition takes that file where a
  release still has one, and the one pack otherwise. Standard was never affected.
