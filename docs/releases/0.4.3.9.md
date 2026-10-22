# Fuse 0.4.3.9 - The Overdrive Update

Ports shows every port in the list, all 146 on a computer and 116 on Android, as box art you can
browse. 0.4.3.8 could show only the 5 ports of an older list.

## New

- Filters on Ports: All, Installed, Ready to Play, Updates and Not Installed, each with how many
  ports it holds.
- A port finds your game by itself: when your library has exactly one copy of the files it needs,
  installing it (or Choose Files) uses that copy without asking. With several, it still asks.
- docs/heat.md: everything Fuse runs that costs power, asleep, opening, on a first start and while
  a game runs, and what holds each back.

## Changed

- Ports on a computer is a grid of box art, like the library. The port you're on is told above it:
  its game, the port and its version, its licence, how it stands here and whether the nightly check
  verified it. Under each box is the port's name, so the five ports of Doom tell apart. Installed ports
  come first, and each box marks an installed port, an update, files it still needs
  and a download under way.
- A port without art found yet is drawn with the cover the library draws for a game without art.

## Fixed

- Fuse kept the second screen of a two-screen handheld (the AYN Thor) drawing at its full rate under
  every game. Fusi took every frame of the display through a loop the pause for games didn't reach,
  and every paused decorative loop still asked for every frame only to skip it. Both now take no
  frames at all while a game runs, Fuse is behind another window or the device sleeps, on every
  system.
- A game played with its port from Ports named its system's emulator (RetroArch, Zendroid) on its
  page's emulator button. The button now names the port when Play starts it.
- An update downloading in About grew and shrank as its numbers changed: the line now keeps its
  length, and figures keep one width in every menu's details and values.
- On a short screen (the Thor's lower one), the library's tabs and toolbar shrink and rise as you
  move down past the first row, and the tiles shrink up to a tenth, so three rows fit; moving back
  up brings them back. The Systems page's header folds to one line the same way past its first row.
- Arranging Systems or Home, the switches beside Add (whose layout, and which screen's look) could
  only be reached by touch. The controller now reaches them along the bar.

- Ports showed 5 ports in place of the 146 in Fuse: the list the nightly check published before
  0.4.3.8 counted as newer than the one inside Fuse, because a published list's revision was when it
  was signed. A list now ranks by its own revision first, then whether it is signed, then when it was
  checked, so a list published before the one inside your Fuse never stands in for it. The same went
  for the Android ports.
