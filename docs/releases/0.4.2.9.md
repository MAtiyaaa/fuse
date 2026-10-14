# Fuse 0.4.2.9 - The Native Update

Jellyfin films that play smoothly, sound clear and look sharp on a 4K TV, gradients without bands,
an Emulators section on every computer, missing RetroArch cores installed for you, save states
through RomM, one Fuse at a time, and Fuse's icon back in Steam.

## New

- **Emulators, on Linux, Windows and macOS.** The Apps section, which only Android had, is
  Emulators on a computer: every emulator Fuse can start, Installed and Not Installed, never any
  other program. An installed one opens with Confirm; one that isn't leads to its page in the Store
  to install it, or to its website where the Store doesn't have it. Options shows its page.
- **Missing RetroArch cores are installed for you.** When a game's core isn't in RetroArch, Fuse
  offers to download it from libretro, where RetroArch's own Core Downloader gets it, into the cores
  folder RetroArch reads, and then starts the game.
- **Save states through RomM.** With RomM as this device's Save Sync, a game's save states go to
  RomM after you play and come down on your other devices before the game starts, slot by slot. A
  state you changed here is never replaced, and this device's earlier copy of a slot is taken off
  RomM, so they don't pile up.
- **Emulators you don't have can be installed from the emulator picker.** Choosing one leads to its
  page in the Store, or to its website.
- **One Fuse at a time.** Starting Fuse while it is already open brings the open one to the front
  instead of opening a second. Started from Steam, the new one takes the old one's place, so Steam
  and Game Mode follow the Fuse on screen. Restarting Fuse (after an update or a change that needs
  it) hands over cleanly too.
- **Settings, Display, Graphics says when a TV gets reduced colour.** On Linux, a 4K screen at 100 Hz
  or more over HDMI is sent YCbCr 4:2:0 (AMD's and Intel's open drivers can't use HDMI 2.1's faster
  link), which shows every soft gradient as bands whatever an app does. Fuse now says so, and that
  60 Hz, or a DisplayPort to HDMI 2.1 adapter, sends full colour.

## Changed

- **In Steam's Game Mode, gamescope composes Fuse itself.** Handed to the display as it is, Fuse's
  8-bit picture went through the display hardware's colour tables with nothing between its steps,
  and many TVs showed soft light as lines, at 60 Hz too (gamescope issue 2250). While Fuse is on
  screen, gamescope now composes it in 10 bits and the graphics card dithers what reaches the TV,
  as Steam's Force Composite switch does for everything; games keep direct scanout.
  `FUSE_COMPOSITE=off` (a launch option) leaves gamescope as it is.
- **Gradients are dithered before they are rounded.** Every gradient behind the interface gets a fine
  grain of noise while its colour is still exact, before the graphics card rounds it to 8 bits, so
  its steps become an even grain instead of lines. Skia's own dither, used until now, is an ordered
  half step that a TV's picture processing evens out, which brought the bands back. On a TV the
  grain is two steps each way, and the blurred room behind Home gets the same.
- **Jellyfin films sound clear.** Surround sound folds down with the centre channel, where films put
  the dialogue, at full level, and the evening out of loud and quiet moments lets the start of each
  sound through before it acts, so speech keeps its edges instead of sounding muffled.
- **Jellyfin films are sharp on a 4K TV.** Pictures are made at the screen's own size, up to 4K,
  instead of 1080p and then stretched, with a sharper filter when a film is smaller than the screen.
- **Video is converted on every core, off the interface's thread,** so 4K films keep up and the
  interface stays responsive while one plays.

## Fixed

- Jellyfin video and sound got choppy. The sound's clock lost a third of a millisecond with every
  block, so the picture fell further behind the sound every minute until the pictures waiting to be
  shown filled the read-ahead and the sound ran dry. The clock is now exact.
- Sound had small gaps and clicks: the filters that fold surround down and even it out were built
  again for every frame of sound, throwing away what they held each time.
- HD films' colours were off (dull reds, greens a little wrong): every film was read with SD's
  colours. Films now use the colours they say they have, and HD ones that say nothing are read as HD.
- Video could stop for good after a few seeks in a row.
- Opening Fuse once from Steam could start two of it.
- Fuse's icon could go missing in Steam. Fuse took its own Steam entry for one of the games it added
  to Steam (they start Fuse too), which could leave its icon pointing at a game's picture that was
  later removed. Games are now never taken for Fuse, and an icon whose picture is gone is put back
  the next time Fuse starts with Steam closed.
- Fuse no longer stays running without a window after it closes.
- RomM's save sync never sent a RetroArch game's save: RetroArch keeps a clock file beside the
  save, and only saves of exactly one file were sent. The save itself now goes; the clock stays here.
