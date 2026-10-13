# Fuse 0.4.2.8 - The Native Update

Smoother motion on a 4K TV and in Steam's Game Mode, video that no longer redraws the whole screen
for nothing, and a log that says where a slow frame's time went.

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

- **Moving from game to game is smooth on a 4K TV.** Box art and small screenshots become a blurred
  room behind the interface, and that room used to be made the moment you arrived: drawn on the
  graphics card, read back to the processor while everything waited, then stretched to 4K on one
  core. It is now drawn small by the processor, blurred and stretched on every core, away from
  the interface, and kept for the last few games, so going back costs nothing. The crossfade waits
  until the new room is ready and already on the graphics card, so it never fades in empty or
  stalls halfway. The crossfade, the settle and Enhanced's slow drift look as they did.
- **In Steam's Game Mode, gamescope paces Fuse's frames.** On Linux, Fuse draws on the same thread
  that runs every animation and button press, and waited there for each screen refresh. Under
  gamescope that wait came on top of gamescope's own, so frames could miss the refresh they waited
  for and motion stuttered. Fuse now paces itself to the screen's rate and leaves the wait to
  gamescope. Pictures never tear: gamescope shows whole frames. `FUSE_VSYNC=on` brings the old way
  back.
- **Fuse starts faster on Linux, and its first screens don't wait.** The AppImage now carries the
  Java classes Fuse uses, already read and checked, in an archive Java maps straight in: about
  half the work of starting is gone, and opening Home, the Library and Systems the first time no
  longer pauses to load their code.
- **Video redraws only when a new picture is due.** A film or a background preview used to have
  the whole window drawn again at every screen refresh, 60 times a second for a 24 fps film. Now
  the window is drawn when the next picture is due.
- **Video pictures are scaled with a cubic filter.** Each picture had mipmaps built for it on the
  processor before it went to the graphics card. It now goes straight there, and looks sharper
  enlarged to a 4K screen.
- **Room for 4K on the graphics card.** Fuse keeps the pictures it draws with (art, the blurred room,
  glass and every layer drawn offscreen) in a cache on the graphics card that held only 96 MB, about
  three full-screen layers at 4K. Moving between screens then uploaded the same pictures again on
  every frame. The cache is now sized for the screen: 760 MB at 4K, 256 MB at 1080p and below.
- **Settings, Display, Graphics** lists the picture cache's size and every setting asked for.

## Fixed

- Backgrounds that took a moment to load never appeared: the room's placeholder stayed, and the
  art stayed hidden behind it. It now fades in over the placeholder when it arrives.
- On a 4K screen, pictures no longer go up to the graphics card again on every frame of a
  transition, which could make motion stutter while art, glass or the background changed.
