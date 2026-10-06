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
| 1 tweens | 0.82 µs | 0.90 µs | 1.25 µs | 353 B | 433 B | 441 B | Fuseline 3 |
| 1 springs | 0.85 µs | 0.87 µs | 1.19 µs | 353 B | 433 B | 441 B | Fuseline 3 |
| 1 vector springs | 0.59 µs | 0.60 µs | 1.31 µs | 235 B | 299 B | 449 B | Fuseline 3 |
| 1 colours | 0.82 µs | 0.90 µs | 1.28 µs | 353 B | 441 B | 449 B | Fuseline 3 |
| 100 tweens | 8.75 µs | 11.20 µs | 161.25 µs | 379 B | 4419 B | 50363 B | Fuseline 3 |
| 100 springs | 9.57 µs | 12.31 µs | 140.21 µs | 379 B | 4419 B | 50363 B | Fuseline 3 |
| 100 vector springs | 14.80 µs | 18.35 µs | 159.80 µs | 379 B | 5219 B | 51163 B | Fuseline 3 |
| 100 colours | 9.94 µs | 18.11 µs | 165.49 µs | 379 B | 5219 B | 51163 B | Fuseline 3 |
| 1000 tweens | 81.13 µs | 109.30 µs | 1680.99 µs | 379 B | 40419 B | 503963 B | Fuseline 3 |
| 1000 springs | 85.49 µs | 114.36 µs | 1590.47 µs | 379 B | 40419 B | 503963 B | Fuseline 3 |
| 1000 vector springs | 154.58 µs | 182.94 µs | 1731.23 µs | 379 B | 48419 B | 511963 B | Fuseline 3 |
| 100 springs retargeted once | 3.77 µs | 4.84 µs | 58.31 µs | 192 B | 1781 B | 20251 B | Fuseline 3 |
| 100 springs retargeted every frame | 0.29 µs | 22.46 µs | 966.62 µs | 32 B | 24664 B | 556611 B | Fuseline 3 |
| 1000 springs retargeted every frame | 3.86 µs | 226.36 µs | 11422.56 µs | 320 B | 242522 B | 5566082 B | Fuseline 3 |
| 100 vector springs retargeted every frame | 0.26 µs | 35.28 µs | 1019.22 µs | 32 B | 44661 B | 560605 B | Fuseline 3 |
| 1000 vector springs retargeted every frame | 4.11 µs | 365.17 µs | 11576.44 µs | 454 B | 442842 B | 5925496 B | Fuseline 3 |
| tween velocity, 1000 readings | 49.66 µs | 53.88 µs | 185.37 µs | 0 B | 0 B | 0 B | Fuseline 3 |
| 1 decays | 0.79 µs | n/a | 1.03 µs | 405 B | n/a | 488 B | Fuseline 3 |
| 100 decays | 7.78 µs | n/a | 131.06 µs | 459 B | n/a | 58363 B | Fuseline 3 |
| 100 values tracking a gesture | 38.77 µs | n/a | 153.41 µs | 1661 B | n/a | 88080 B | Fuseline 3 |
| tab transitions | 1.44 µs | 1.62 µs | 4.79 µs | 589 B | 856 B | 2347 B | Fuseline 3 |
| tab changes every 3 frames | 2.45 µs | 4.83 µs | 39.39 µs | 512 B | 1844 B | 22006 B | Fuseline 3 |
| tab reversal every 4 frames | 1.58 µs | 4.30 µs | 28.99 µs | 499 B | 1965 B | 15241 B | Fuseline 3 |
| timeline, 20 tracks | 0.24 µs | 0.35 µs | 3.98 µs | 0 B | 14 B | 913 B | Fuseline 3 |
| timeline seek, 20 tracks | 0.51 µs | 1.01 µs | n/a | 0 B | 456 B | n/a | Fuseline 3 |
| timeline reverse, 20 tracks | 0.46 µs | n/a | n/a | 0 B | n/a | n/a | Fuseline 3 only |
| driver churn, 10 | 4.33 µs | 4.56 µs | 12.29 µs | 2407 B | 2646 B | 6718 B | Fuseline 3 |
| driver churn, 100 | 33.75 µs | 37.16 µs | 139.71 µs | 19592 B | 21618 B | 67182 B | Fuseline 3 |
| driver churn, 1000 | 355.97 µs | 383.99 µs | 1380.14 µs | 191443 B | 211343 B | 671833 B | Fuseline 3 |
| idle, 1000 settled values | 0.00 µs | 0.00 µs | 0.00 µs | 0 B | 0 B | 0 B | Fuseline 3 |
| 1000 springs at 30 Hz | 82.24 µs | 126.81 µs | 1667.51 µs | 472 B | 40512 B | 583976 B | Fuseline 3 |
| 1000 springs at 60 Hz | 82.14 µs | 118.20 µs | 1681.77 µs | 472 B | 40512 B | 583976 B | Fuseline 3 |
| 1000 springs at 90 Hz | 83.90 µs | 118.30 µs | 1760.27 µs | 467 B | 40507 B | 583971 B | Fuseline 3 |
| 1000 springs at 120 Hz | 83.02 µs | 118.33 µs | 1643.46 µs | 465 B | 40505 B | 583969 B | Fuseline 3 |
| 1000 springs at 144 Hz | 83.13 µs | 115.69 µs | 1613.11 µs | 463 B | 40503 B | 583967 B | Fuseline 3 |
| 1000 springs at 165 Hz | 88.08 µs | 117.56 µs | 1543.99 µs | 462 B | 40502 B | 583966 B | Fuseline 3 |
| 1000 springs at 240 Hz | 81.87 µs | 116.89 µs | 1564.28 µs | 460 B | 40500 B | 583964 B | Fuseline 3 |

Whole interface paths, composition, layout and drawing included:

| Interface path | Fuseline 3 | Fuseline 2 | Compose | Fuseline 3 memory | Fuseline 2 memory | Compose memory | First |
|---|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 620 µs | n/a | 2285 µs | 21.4 KB | n/a | 198.3 KB | Fuseline 3 |
| shared element, moving destination | 763 µs | n/a | 976 µs | 11.5 KB | n/a | 14.0 KB | Fuseline 3 |
| rapid tab switching | 2565 µs | 2725 µs | 2838 µs | 36.6 KB | 50.4 KB | 129.3 KB | Fuseline 3 |
