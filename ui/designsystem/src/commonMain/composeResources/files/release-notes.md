# Fuse 0.4.3.9 - The Overdrive Update

Ports shows every port in the list, all 146 on a computer and 116 on Android, as box art you can
browse. 0.4.3.8 could show only the 5 ports of an older list.

## New

- Filters on Ports: All, Installed, Ready to Play, Updates and Not Installed, each with how many
  ports it holds.

## Changed

- Ports on a computer is a grid of box art, like the library. The port you're on is told above it:
  its game, the port and its version, its licence, how it stands here and whether the nightly check
  verified it. Under each box is the port's name, so the five ports of Doom tell apart. Installed ports
  come first, and each box marks an installed port, an update, files it still needs
  and a download under way.
- A port without art found yet is drawn with the cover the library draws for a game without art.

## Fixed

- Ports showed 5 ports in place of the 146 in Fuse: the list the nightly check published before
  0.4.3.8 counted as newer than the one inside Fuse, because a published list's revision was when it
  was signed. A list now ranks by its own revision first, then whether it is signed, then when it was
  checked, so a list published before the one inside your Fuse never stands in for it. The same went
  for the Android ports.
