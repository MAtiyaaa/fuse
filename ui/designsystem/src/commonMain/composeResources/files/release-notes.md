# Fuse 0.3.7.4 - The Motion Update

Systems no longer blow up to TV size while you arrange them, each device keeps its own Systems
page, and Fuse draws far less on every frame.

## New

- **Where a slow frame went, on the device.** With the performance overlay on (Settings, Display),
  Android shows the slowest frame of each second next to the average, and what it spent its time
  on: waiting for the main thread, input, animation, layout, drawing, rendering and the graphics
  chip. A stutter no longer hides inside an average.

## Changed

- **Each device keeps its own Systems page.** A TV, a monitor, a handheld and each screen of a
  two-screen handheld arrange and size their systems for themselves; Fuse Sync no longer carries
  one device's arrangement to another. The order of your systems still follows you everywhere.
- **Arranging on a short screen.** On a handheld, the system header folds away while you arrange,
  so the board has the room, and Undo, Reset, New page and the other screen's look are compact
  icon buttons that cover less of it.
- **Fuse draws far less on every frame, and looks exactly the same.** The room's shading over the
  art, a theme's room while it isn't moving, and the lit picture behind every game without art are
  drawn once and then shown as one image, instead of stacks of screen-sized gradients and patterns
  of dozens of shapes on every frame. A page changing its art or hints no longer rebuilds the whole
  interface around it. Measured without a graphics card (the slowest way to draw), Home at rest
  went from 459 ms a frame to 215 ms and Settings from 321 ms to 78 ms; on a device with a graphics
  card the same work is a small part of a frame.

## Fixed

- **Systems became huge after arranging on the AYN Thor.** A Systems page arranged in 0.3.7.3
  synced through Fuse Sync to a device on an older version, which lost the mark that it was
  already in the finer grid. It then came back and was made finer a second time, so every system
  became three times as wide and twice as tall, like a TV's. Each device now keeps its own page,
  a page that lost the mark is recognised and kept as it is, and one already made too large is put
  back to its sizes by itself.
- **Arranging could turn a handheld's board into a TV's.** The board keeps the kind of screen it
  was shown on while you arrange, whatever room the header leaves it.
- **The Add system space could reach past the screen's edge.** On a Systems page centred on a TV or
  a handheld, the space for adding a system now stays inside the board, on the next row when the
  current one is full.
- **Fuse's performance measurements opened on setup.** The measuring harness marks setup done after
  the store reads its saved settings, so it measures Home, Systems and the Library again.
