# Fuse 0.4.2.2 - The Native Update

Saves you can trust on a shared device, every emulator's saves where that emulator really keeps
them, a Home that never goes blank, smoother Linux, and a Decky plugin that can't take Steam's menu
down with it. Nothing here has been tried on a real Steam Deck, AYN Thor or TV yet; everything is
tested with fixtures and measured on a computer.

## New

- **Switch account and device saves travel as one save.** Eden's newer save layouts (by account and
  by device) are read beside the older one, for the Eden user actually signed in, never another
  user's. Ryujinx reads its own save records for the user it last opened.
- **Saves no one owned can be claimed.** On a device more than one person uses, a save that was
  there before Fuse is kept aside instead of being given to whoever launches first. Sync lists them
  under Saves to Claim, and the person it belongs to takes it.
- **Eden's own data folder on Android.** Choose it through Android's folder picker in Settings, Save
  folders, and Fuse reads and writes its saves through it, as one transaction that a provider
  without renames, a failure part way or a crash can't leave half done, and that never overwrites
  what the emulator wrote meanwhile.
- **Dolphin settings,** for every game and for one game, in Dolphin's own files: internal
  resolution, aspect ratio, widescreen hack, shader compilation, vertical sync and dual core, with
  the four profiles. A game is known by the id on its disc (ISO, GCM, RVZ, WIA, CISO).
- **Firmware, said plainly.** Verified (matched a known good dump) is told apart from found by name,
  and a file that isn't a known dump from none at all. Firmware installed inside Eden, Citron,
  Ryujinx, RPCS3 and Vita3K is checked to be there on a computer.
- **The emulator contract table,** docs/emulator-contracts.md: for every emulator in every catalog
  and every RetroArch core, whether its saves sync (or why not), and whether Fuse changes its
  settings and cheats and checks its firmware. It is written from Fuse's own code and a test keeps
  it so.
- **Frame pacing profiles on Linux** for X11, Wayland, gamescope and Game Mode, by the active
  display's refresh rate; Developer options shows when gamescope is upscaling Fuse and how to stop
  it. Not yet tried on a real TV or in Game Mode.

## Changed

- **Every emulator's saves where it keeps them:** DuckStation and PCSX2 by their memory card
  settings (shared, per game and folder cards), Dolphin's GCI folders per game, its configured cards
  and NAND, RetroArch by the configuration in effect for each game and each core's real save
  layout, and RPCS3, Vita3K, Cemu, Xenia and PPSSPP by the user and storage each really uses. Fuse
  never guesses between users. GBA.emu, GBC.emu, NES.emu and Snes9x EX+ saves sync too.
- **Malformed saves are refused,** such as an N64 or DS save of a size no game uses.
- **Linux draws more evenly:** Fuse runs on the ZGC collector, whose pauses stayed under a tenth of
  a millisecond where the default's reached 34.6 ms in the same run; every gradient is dithered, so
  dark backgrounds don't band; and Fuse draws at the desktop's own scale.
- **Fuse Intelligence looks again** for an emulator's settings folder once its "none found" answer is
  six hours old, since an emulator makes that folder the first time it runs.
- **Library shelves keep one rhythm,** quick settings scale to fit handhelds, and a game you settle
  leaves "Games that need you".

## Fixed

- **A failed save handover now stops the launch** instead of starting the game with someone else's
  save, and a swap between people can't half happen: it is one transaction, undone on the next
  start if Fuse is interrupted.
- **Home pages never stay blank** after moving between pages quickly.
- **The Sync tab shows each game's own name and art,** not file names.
- **Numbered file names find their game:** "1 - Mario Kart", "1. Mario Kart", "3- Mario Kart" and
  "2Pak Yellow".
- **DS saves are found where the emulator put them.**
- **The Decky plugin can't take Decky or Steam's menu down with it.** It patches only the menu's
  drawing and stops after repeated errors, Settings, Decky has a switch that keeps it out of Steam's
  game menu entirely, and installing it never restarts Decky under a running game.
- **Linux tells a software graphics driver from the graphics card,** so a slow driver is reported
  as one.
- **The HUD's tabs move in step,** and a tab only slides when the tabs are reordered.
