# Fuse 0.4.2.4 - The Native Update

A Linux release: Fuse says plainly whether it draws on the graphics card, the Fuse theme's room has
no bands or squares, and the startup animation and setup's opening no longer draw the whole
interface underneath them. None of it has been tried on a real Bazzite PC, Steam Deck or TV in Game
Mode yet; the drawing fixes are measured on rendered pictures and the timings in Fuse's own
software-drawn test harness.

## New

- **Settings says how Fuse draws.** A Graphics row in System health and in Performance (search
  finds it as "GPU" too) says graphics card or processor. Selecting it shows the driver, the display
  (Game Mode, X11 or Wayland), the window against the screen and whether gamescope enlarges it, and
  the refresh rate. On the processor it also says why: what Skia said when it gave up on the graphics
  driver, anything in Fuse's launch environment that forces software drawing, and what the
  computer's own OpenGL uses, so a broken driver is told apart from how Fuse was started. The
  diagnostics report carries the same.

## Changed

- **Steam's runtime libraries are set aside** when the AppImage starts. Steam puts them ahead of the
  system's for a non-Steam game in Game Mode, and an older library among them can stop the system's
  graphics driver from loading.
- **The startup animation and setup's opening** no longer draw the interface under them while they
  cover the whole screen; it is still built underneath, ready when they open out. In Fuse's
  software-drawn harness that took the startup animation from 596 ms to 113 ms a frame at the median
  and setup's opening from 419 ms to 114 ms (that harness is far slower than a real computer; the
  ratio is what counts).

## Fixed

- **No more rings and squares in the Fuse theme's room.** The blurred cover behind the interface was
  worked out in 8 bits at a quarter of the screen's size and stretched every frame, so its steps
  showed as rings and the stretch as squares. On a computer it is now blurred and stretched in full
  precision once per cover and rounded with a step of noise either way: on a dark test room the share
  of pixels equal to their neighbour went from 0.96 (flat bands) to 0.55.
- **Background gradients are dithered as they are drawn** on Linux, Windows and macOS, on the
  graphics card and on the processor alike: the longest flat band of a near-black ramp went from 61
  pixels to 10. The white noise that only lightened the room is gone where that happens, and the
  window-wide dither is left off where gamescope enlarges Fuse, since there its pixels show as squares.
