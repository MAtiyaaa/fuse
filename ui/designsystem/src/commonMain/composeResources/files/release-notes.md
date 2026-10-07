# Fuse 0.3.7.5 - The Motion Update

0.3.7.4 made Fuse slower on Android. This release undoes the change that caused it, runs a
properly optimised build on your device, and brings Fuseline 3.1. Everything looks and moves
exactly as it did.

## New

- **Fuseline 3.1.** Fuse's animation engine keeps every curve, spring and motion of Fuseline 3, and
  takes work off the frames around them:
  - **Animated values without coroutines.** The values that follow a tile's focus, a button's
    colour or a panel's size no longer start a coroutine each. A page of tiles costs a few small
    objects per value, so tab switches and new Library rows compose faster.
  - **No invisible frames.** A frame that would move something less than anyone could see is
    worked out but not drawn again. The long, slow end of a spring stops redrawing the screen.
  - **Decoration waits for you.** While you press buttons, and while what you pressed is still
    moving, the theme's room and the background's slow drift hold still, so every frame goes to
    what you are doing. At rest they move exactly as before, on both screens of a two-screen
    handheld.
  - **Paced decoration.** A room that needs 30 updates a second wakes 30 times a second, not 120
    times on a 120 Hz screen.
- **Fuseline 3.1 measured against every Fuseline and Compose.** Fuseline 3, 2 and 1 are kept, as
  they shipped, in Fuse's tests, and the benchmarks compare all five. See
  [How Fuseline works](../fuseline.md#speed).

## Changed

- **An optimised Android build.**
  - The Android app is now built with R8, Android's optimiser. Compose, the toolkit Fuse's
    interface is built with, runs much faster optimised.
  - Fuse's interface code and Compose's own code are compiled ahead of time when Fuse is installed
    (a startup profile), instead of running interpreted until Android gets round to compiling them,
    which for an app installed from GitHub could take days.
  - Fuse's interface is compiled without per-screen debugging records it never used.
- **Moving the selection redraws two items, not the whole view.** In the Library (icons, covers
  and list), Addons, Apps, Collections and Search, moving the selection now rebuilds only the item
  it leaves and the one it reaches.

## Fixed

- **0.3.7.4 made Fuse slower on Android.**
  - **What went wrong:** 0.3.7.4 drew the room's shading, a resting room and the picture behind
    every game without art into images painted by the processor. That is quick on a computer
    drawing without a graphics card, which is where it was measured. On a phone it moved the
    graphics card's work onto the processor and made the Library's scrolling laggy.
  - **The fix:** on Android that drawing is now recorded once and kept by the graphics card as a
    finished picture. Nothing is painted by the processor, and nothing is drawn again until it
    changes. Computers keep the picture painted once.
