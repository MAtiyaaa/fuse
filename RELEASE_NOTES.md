# Fuse 0.4.2.21 - The Native Update

Fixes for starting games and Cartridge in Steam's Game Mode.

## New

- Nothing new in this release.

## Changed

- In Game Mode, Fuse hides while a game it started runs, and comes back when the game ends. It no
  longer draws behind the game while you play.

## Fixed

- In Game Mode, a game or emulator Fuse started could stay hidden behind Fuse, which went on
  drawing and lagged while it waited for the game. Fuse used to minimise itself, which gamescope
  doesn't do, so its window stayed in front.
- In Game Mode, Cartridge didn't come to the screen: Fuse stayed in front and took the controller
  back on the next press. Fuse now steps aside while Cartridge runs, as for a game.
- Steam's Exit Game could stay on "Exiting game" forever. When Fuse quits, the games and Cartridge
  it started now end with it, and Fuse is gone within a few seconds even when something in its
  shutdown hangs.
