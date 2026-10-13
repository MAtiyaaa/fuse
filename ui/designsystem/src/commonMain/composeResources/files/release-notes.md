# Fuse 0.4.2.8 - The Native Update

Smoother motion on a 4K TV, and a log that says where a slow frame's time went.

## New

- **Fuse's log says how frames went.** Each minute in which anything moved ends in one line in
  ~/.local/share/fuse/logs/fuse.log: how many frames were late, the longest gap, how long drawing
  took, and whether the time went to drawing or to the graphics card and the way to the screen.
- **Settings for trying how frames reach the screen,** set as a Steam game's Launch Options (for
  example `FUSE_VSYNC=off %command%`), with no new build:
  - `FUSE_VSYNC=off`: Fuse doesn't wait for the screen's refresh itself, where gamescope or Wayland
    already waits.
  - `FUSE_BUFFERING=double` or `triple`: how many frames are kept in flight.
  - `FUSE_WAIT_VSYNC=off`: a redraw asked for at once doesn't wait for the next refresh first.
  - `FUSE_GPU_CACHE_MB=1024`: the size of the picture cache on the graphics card.
  - `FUSE_CURSOR=keep`: the pointer is never swapped for a blank one while a controller is in use,
    for gamescope's stutter when a pointer hides.
  - `FUSE_STEAM_OVERLAY=off`: Steam's overlay library, which Steam loads into everything it starts
    to hook its drawing, stays out of Fuse. The games Fuse starts still get the overlay.

## Changed

- **Room for 4K on the graphics card.** Fuse keeps the pictures it draws with (art, the blurred room,
  glass and every layer drawn offscreen) in a cache on the graphics card that held only 96 MB, about
  three full-screen layers at 4K. Moving between screens then uploaded the same pictures again on
  every frame. The cache is now sized for the screen: 760 MB at 4K, 256 MB at 1080p and below.
- **Settings, Display, Graphics** lists the picture cache's size and every setting asked for.

## Fixed

- On a 4K screen, pictures no longer go up to the graphics card again on every frame of a
  transition, which could make motion stutter while art, glass or the background changed.
