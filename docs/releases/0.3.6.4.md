# Fuse 0.3.6.4 - The Unity Update

Box art on RomM games as soon as it is found, system panels from your games, RomM on the second
screen, Fuse's own art in Steam, and Linux updates that replace Fuse in place.

## New

- **System panels from your games' screenshots.** A system's artwork panel can be a screenshot of
  one of its own games, cut to the Art Book Next pack's slanted panel and drawn at the same place
  and size, so it sits with the rest of the set. It shows the same wherever the system does: tiles,
  Home and its widgets, the Systems page, Downloads, RomM and the second screen.
  - **By itself** for systems the pack has nothing for, like the PlayStation 5: the most recently
    played game here first, then your RomM server's, as soon as one has a screenshot (or, failing
    that, a background).
  - **For every system**, with System art style From your games in Settings, Systems, System art:
    the pack's logos and colours, with panels from each system's games.
  - **Your pick**, on a system's Media page: choose Cover, then From a game's screenshot, pick one
    of its games and then one of its screenshots, and it is kept. Automatic, from its games hands it
    back to Fuse. Restore Fuse Default Art still puts Fuse's own back.
- **RomM on the second screen.** The game or system chosen on the RomM tab, a system's RomM games
  or a RomM collection shows on the second screen as the Library's does, with menus on top or
  flipped below, including RomM games and systems you don't have on this device yet.

- **Fuse's own art in Steam.** Add Fuse to Steam (Game Mode) now gives the entry Fuse's library
  capsule, wide capsule, hero, logo and icon, so it looks like any other game in Steam instead of a
  grey tile. Art you already chose for it stays.

## Changed

- **Linux updates replace Fuse in place.** An update now takes the place of the AppImage Fuse runs
  from, at the same path, instead of adding another file beside it each time. Shortcuts, start at
  login and Steam's entry keep working, the version before is kept as a hidden `.previous` file for
  one step back, and the copies earlier updates left beside it are removed.

- A RomM game's options show its box art, like a game on this device, instead of RomM's poster.

## Fixed

- RomM games kept showing RomM's posters until Fuse was closed and opened again, even after Fuse
  had found their box art. Art found while the RomM lists were being drawn again was missed; it now
  shows on the lists already open.
