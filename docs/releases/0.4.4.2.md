# Fuse 0.4.4.2 - The Tune-Up Update

Fuseline 4.3: Fuse's animation engine stops doing work nobody sees, so rows following the selection while they are scrolled away, values held by a finger off screen and pages waiting behind another cost a fraction of what they did. Phone Link answers every press, and Fuse's website counts its releases and themes in.

## New

- **Phone Link answers your touch.** A press ripples out from your finger on buttons, game tiles,
  rows and menus, and adding a game to your favourites pops its heart with a small burst. Neither
  happens when your phone asks for reduced motion.

## Changed

- **Fuseline 4.3.** Every animation in Fuse moves exactly as it did, with less work behind it:
  - A value nobody is drawing that is given a new target every frame (a row following the
    selection while it is scrolled away) no longer comes up every frame only to be found unseen:
    two to three times less work.
  - A value nobody has drawn yet, or whose readers were already told it moved, no longer writes
    changes nobody will read: a value following a finger off screen costs about an eighth of what
    it did, and ten thousand values nobody draws cost about as much as one.
  - Moving colours come from a table instead of three powers each, the very same colour, and
    tweens on one curve, decays on one friction and springs at one moment share one answer.
- The website's counts of releases and themes tick up as they come into view, and simply appear
  when your device asks for reduced motion.

## Fixed

- The website's screenshots showed an empty tile where the quick menu's picture used to be. It is
  gone.
- The website's release notes counted "Nothing new in this release." as something new. A section
  with nothing in it now shows none.

## Fuseline

Fuseline 4.3 measured against Fuseline 4.2, 4, 3.1, 3, 2 and 1 (kept in the tests exactly as they
shipped) and Compose, on 111 cases with OpenJDK on Linux: clearly first in 19, level with the
best within run-to-run noise in 89, and in the other 3 another engine came out ahead by a hair (1
colour read, 100 vector springs drawn, 1000 springs at 90 Hz), which measured again over 31 rounds
are level too. Some of the clear wins:

| Each frame | Fuseline 4.3 | Fuseline 4.2 | Fuseline 3.1 | Compose |
|---|--:|--:|--:|--:|
| 10,000 springs nobody draws | 1.2 µs | 43.1 µs | 2,488 µs | 36,056 µs |
| 100 values following a finger, nobody drawing them | 2.4 µs | 19.5 µs | 51.9 µs | 210 µs |
| 1,000 springs given a new target every frame, nobody drawing them | 74 µs | 214 µs | 651 µs | 15,559 µs |
| 1,000 tweens nobody draws | 0.95 µs | 6.1 µs | 102 µs | 2,132 µs |

Where every value visibly moves every frame and is drawn, most of a frame is Compose redrawing what
reads it, the same for every engine, so there Fuseline 4.3 is level with 4.2. Every row is in
[docs/fuseline.md](https://github.com/MAtiyaaa/fuse/blob/main/docs/fuseline.md#results).
