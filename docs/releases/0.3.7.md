# Fuse 0.3.7 - The Motion Update

Fuseline 3: Fuse's animation engine, rebuilt around two rules. Motion never breaks continuity, and
the change itself is animated, not only where it ends.

## New

- **Fuseline 3.** Anything moving in Fuse now carries on from the place it is shown at, at the speed
  it has, whatever interrupts it: a new target, a finger catching it, a reversal, a new kind of
  motion, the window changing size. Nothing jumps, and nothing stops dead unless you stop it.
- **Tabs that change like one motion.** Changing tab slides the new page in from its side. Change
  again before it lands and it carries on from where it is; go back and it reverses from there. Hold
  a direction or tap quickly through every tab, and the pages never jump.
- **Every input moves things the same way.** Touch, the mouse, a trackpad, the keyboard, a
  controller's D-pad and analog stick, and the scroll wheel all take a moving thing where it is, and
  let it go with the speed you gave it.
- **Layout that glides.** Things that change place because the layout changed (a window resized, a
  row reflowing) can glide to their new place, and keep chasing it if it moves again. An element can
  travel between two screens, its size, corners and speed carried across.
- **Motion that understands what it is for.** Fuse asks for focus, selection, navigation, entering,
  leaving, pressing and the rest by meaning, and your motion choice in Accessibility decides how each
  looks. Reduced motion removes overshoot and sliding entirely and fades in place instead. Saving
  battery trims decoration only; moving through Fuse keeps its motion.
- **Smooth at any refresh rate.** Motion is worked out from the real time of each frame, from 30 to
  240 Hz, so it looks the same on a TV, a handheld and a 240 Hz monitor. When a device falls behind,
  only decoration skips frames; navigation, focus and gestures keep every one.
- **Motion tools for developers.** A motion inspector shows every value in motion with its target,
  speed and history, a motion trace records what happened in order, and tests run on a clock that
  replays every motion exactly. See [docs/fuseline.md](../fuseline.md).

## Changed

- **Faster than Fuseline 2 and Compose.** Fuseline 3 was measured against Fuseline 2 and against
  Compose's own animation on every workload they share, in time and in memory, and comes first on
  every one. The method and the full tables are below and in [docs/fuseline.md](../fuseline.md).
- **Curves and springs are exact.** Every curve gives its exact slope and every spring is solved in
  one closed form for every bounce, so a motion handed from one kind to another keeps its speed to
  the decimal.
- **Fuseline is tested on every platform.** Its tests now run on Linux, Windows and macOS, and its
  Android build is checked too.

## Fixed

- **Changing tab quickly made the page jump.** Each new tab snapped back to its side and started
  its slide again. The page now carries on from where it is.
- **Motion could keep asking for frames after every value had stopped** in some windows and tests.
  Fuse now lets go of the display as soon as nothing moves.

## Benchmarks

Fuseline 3 against Fuseline 2 (the 0.3.6 engine, kept unchanged in the tests) and Compose's own
animation, on every workload they share. One thread and a manual frame clock; the same values,
motions, curves, counts and frame times for every engine; each warmed up, then measured in rounds
that rotate their order, the median round counting; the harness's own cost measured with an empty
workload and taken off. Lower is better. Measured on OpenJDK 64-Bit Server VM 21.0.12.1, 4
processors, Linux amd64. The full method is in [docs/fuseline.md](../fuseline.md), and this runs it
again:

```
./gradlew :ui:fuseline:desktopTest --tests '*MotionBenchmark' -Pfuse.bench=true
```

| Case | Fuseline 3 | Fuseline 2 | Compose | Fuseline 3 memory | Fuseline 2 memory | Compose memory | First |
|---|---|---|---|---|---|---|---|
| 1 tweens | 0.82 µs | 0.89 µs | 1.25 µs | 353 B | 433 B | 441 B | Fuseline 3 |
| 1 springs | 0.87 µs | 0.91 µs | 1.21 µs | 353 B | 433 B | 441 B | Fuseline 3 |
| 1 vector springs | 0.66 µs | 0.67 µs | 1.37 µs | 235 B | 299 B | 449 B | Fuseline 3 |
| 1 colours | 0.87 µs | 0.91 µs | 1.31 µs | 353 B | 441 B | 449 B | Fuseline 3 |
| 100 tweens | 8.55 µs | 11.32 µs | 167.24 µs | 379 B | 4419 B | 50363 B | Fuseline 3 |
| 100 springs | 9.30 µs | 14.19 µs | 147.03 µs | 379 B | 4419 B | 50363 B | Fuseline 3 |
| 100 vector springs | 12.48 µs | 18.06 µs | 158.03 µs | 379 B | 5219 B | 51163 B | Fuseline 3 |
| 100 colours | 10.36 µs | 16.47 µs | 169.49 µs | 379 B | 5219 B | 51163 B | Fuseline 3 |
| 1000 tweens | 78.74 µs | 102.63 µs | 1646.94 µs | 379 B | 40419 B | 503963 B | Fuseline 3 |
| 1000 springs | 87.00 µs | 125.26 µs | 1569.32 µs | 379 B | 40419 B | 503963 B | Fuseline 3 |
| 1000 vector springs | 118.54 µs | 183.69 µs | 1785.26 µs | 379 B | 48419 B | 511963 B | Fuseline 3 |
| 100 springs retargeted once | 3.76 µs | 5.35 µs | 59.34 µs | 192 B | 1781 B | 20198 B | Fuseline 3 |
| 100 springs retargeted every frame | 0.31 µs | 22.24 µs | 974.85 µs | 32 B | 24664 B | 556611 B | Fuseline 3 |
| 1000 springs retargeted every frame | 4.48 µs | 224.01 µs | 10851.55 µs | 320 B | 242522 B | 5566082 B | Fuseline 3 |
| 100 vector springs retargeted every frame | 0.23 µs | 34.42 µs | 1002.72 µs | 32 B | 44661 B | 560605 B | Fuseline 3 |
| 1000 vector springs retargeted every frame | 3.73 µs | 368.23 µs | 11837.34 µs | 454 B | 442842 B | 5925496 B | Fuseline 3 |
| tween velocity, 1000 readings | 47.40 µs | 80.01 µs | 174.13 µs | 0 B | 0 B | 0 B | Fuseline 3 |
| 1 decays | 0.74 µs | n/a | 1.50 µs | 405 B | n/a | 488 B | Fuseline 3 |
| 100 decays | 7.26 µs | n/a | 120.63 µs | 459 B | n/a | 58363 B | Fuseline 3 |
| 100 values tracking a gesture | 38.31 µs | n/a | 147.14 µs | 61 B | n/a | 88080 B | Fuseline 3 |
| tab transitions | 2.21 µs | 2.48 µs | 7.37 µs | 589 B | 856 B | 2347 B | Fuseline 3 |
| tab changes every 3 frames | 2.38 µs | 4.57 µs | 41.70 µs | 512 B | 1844 B | 22006 B | Fuseline 3 |
| tab reversal every 4 frames | 1.45 µs | 4.20 µs | 26.32 µs | 499 B | 1965 B | 15241 B | Fuseline 3 |
| timeline, 20 tracks | 0.28 µs | 0.35 µs | 4.07 µs | 0 B | 14 B | 913 B | Fuseline 3 |
| timeline seek, 20 tracks | 0.40 µs | 0.82 µs | n/a | 0 B | 0 B | n/a | Fuseline 3 |
| timeline reverse, 20 tracks | 0.50 µs | n/a | n/a | 0 B | n/a | n/a | Fuseline 3 only |
| driver churn, 10 | 4.35 µs | 4.43 µs | 12.61 µs | 2407 B | 2646 B | 6718 B | Fuseline 3 |
| driver churn, 100 | 31.52 µs | 33.48 µs | 121.17 µs | 19592 B | 21618 B | 67182 B | Fuseline 3 |
| driver churn, 1000 | 327.41 µs | 341.35 µs | 1294.44 µs | 191443 B | 211343 B | 671833 B | Fuseline 3 |
| idle, 1000 settled values | 0.00 µs | 0.00 µs | 0.00 µs | 0 B | 0 B | 0 B | Fuseline 3 |
| 1000 springs at 30 Hz | 77.40 µs | 110.95 µs | 1442.68 µs | 472 B | 40512 B | 583976 B | Fuseline 3 |
| 1000 springs at 60 Hz | 77.60 µs | 130.93 µs | 1478.21 µs | 472 B | 40512 B | 583976 B | Fuseline 3 |
| 1000 springs at 90 Hz | 92.38 µs | 127.20 µs | 1561.55 µs | 467 B | 40507 B | 583971 B | Fuseline 3 |
| 1000 springs at 120 Hz | 79.31 µs | 117.63 µs | 1585.73 µs | 465 B | 40505 B | 583969 B | Fuseline 3 |
| 1000 springs at 144 Hz | 77.63 µs | 109.79 µs | 1458.70 µs | 463 B | 40503 B | 583967 B | Fuseline 3 |
| 1000 springs at 165 Hz | 83.89 µs | 118.60 µs | 1620.88 µs | 462 B | 40502 B | 583966 B | Fuseline 3 |
| 1000 springs at 240 Hz | 82.19 µs | 130.54 µs | 1445.02 µs | 460 B | 40500 B | 583964 B | Fuseline 3 |

Whole interface paths, composition, layout and drawing included:

| Interface path | Fuseline 3 | Fuseline 2 | Compose | Fuseline 3 memory | Fuseline 2 memory | Compose memory | First |
|---|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 592 µs | n/a | 1994 µs | 21.4 KB | n/a | 204.1 KB | Fuseline 3 |
| shared element, moving destination | 615 µs | n/a | 894 µs | 11.5 KB | n/a | 14.0 KB | Fuseline 3 |
| rapid tab switching | 2271 µs | 2582 µs | 2721 µs | 36.6 KB | 50.3 KB | 129.1 KB | Fuseline 3 |
