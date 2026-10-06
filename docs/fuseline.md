# Fuseline by Fuse

Fuseline by Fuse is Fuse's animation engine. It is named after the line of a fuse, the wire in Fuse's
logo that carries the spark. Every animation in Fuse runs on it, from a focus lift to the setup's
opening sequence.

It lives in `ui/fuseline` (package `io.github.matiyaaa.fuse.ui.fuseline`). Fuse 0.3.7 brings
**Fuseline 3**, built around two rules:

- **Motion never breaks continuity.** Whatever happens to a moving thing (a new target, a finger
  catching it, a reversal, a seek, a new motion, the window changing size), it carries on from the
  place it is shown at, at the speed it has. It never jumps, and never stops dead unless it is told to.
- **Animate the transition, not only the destination.** A change of state is a motion with a
  direction, that can be interrupted, reversed, shared between several things, and scrubbed by hand.

## What it owns

- **Curves** (`Curve`, `CubicCurve`, `LinearCurve`, `Curves`). A cubic Bézier curve is solved in
  double precision to a billionth: the first guess comes straight from a precomputed inverse table,
  then Newton's method finishes it, with a bisection fallback where the curve flattens. Every curve
  gives its exact slope (`derivative`): dy/dx = y'(t) / x'(t), and its limit where x'(t) vanishes.
- **Motions** (`Motion`): `Tween`, `Spring`, `Decay`, `Snap`, `Keyframes`, `Repeating`, `Delayed`,
  `Sequence` and `Parallel` (one motion per component). Builders with Compose's names (`tween`,
  `spring`, `keyframes`, `repeatable`, `infiniteRepeatable`, `delayed`...) make moving code over easy.
- **Animated values** (`FuselineValue`). The continuity core, described below.
- **Converters** for floats, lengths, positions, sizes, rectangles, colours (blended through Oklab,
  so a fade stays even instead of dipping grey), corner radii and whole transforms (`MotionTransform`).
- **Gestures** (`dragMotion`, `nudge`, `StickDrive`, `ScrollDrive`): touch, mouse, trackpad,
  keyboard, a controller's D-pad and analog stick, and the scroll wheel all drive a value the same way.
- **State transitions** (`MotionTransition`, `rememberMotionTransition`, `MotionTransitionLayout`).
- **Layout motion and shared elements** (`motionBounds`, `sharedMotion`, `SharedMotion`).
- **Entrances, exits and swaps** (`Appear`, `Swap`, `Crossfade`), now running on Fuseline 3 values.
- **Choreography** (`Timeline`, `TimelinePlayer`, `rememberTimelinePlayer`).
- **Loops** (`rememberLoopClock`), **scrolling** (`fuselineScrollBy`, `fuselineScrollTo`) and
  **gliding indicators** (`rememberGlide`).
- **Fuse's motion profile** (`FuselineMotion`, `MotionLevel`, `MotionEnvironment`, `Durations`).
- **Tools** (`MotionTrace`, `MotionInspector`, `MotionInspectorPanel`, `FramePacing`).

From Compose, Fuseline takes only:
- the frame tick (`withFrameNanos`);
- the system's animation speed (`MotionDurationScale`);
- the policy for endless animation (`InfiniteAnimationPolicy`);
- layout, pointer input and the graphics layer to draw the result.

## The continuity core: `FuselineValue`

A `FuselineValue<T>` is a value with a position and a velocity for each of its components. Whoever
owns it right now is its `owner`: `IDLE`, `ANIMATION` or `GESTURE`. Every operation hands over from
what it is doing, at the place it is shown and the speed it has:

| Operation | What it does |
|---|---|
| `animateTo(target, motion)` | A new move from here, starting with the current velocity. |
| `retarget(target, motion?)` | The move under way takes a new target in place: no new coroutine, no pause, same position and speed. `retargetFloat` and `retargetXY` do it without boxing. |
| `snapTo(value)` | Jumps, on purpose, and comes to rest. |
| `stop()` | Comes to rest where it is. |
| `seek(playNanos)`, `seekProgress(f)` | Puts the move at any moment of its motion. |
| `dragBy`, `dragTo` | A gesture takes over (mid-move too: it is caught where it is). |
| `release(target, motion)`, `fling(decay)`, `flingTo(...)` | The gesture lets go with its measured velocity, into a spring, a coast, or a coast that lands on a snap point. |
| `animateDecay(velocity, decay)` | Coasts to rest. `projectDecay` says where it will stop. |

The values, targets and velocities live in float arrays behind versioned state, so a frame reads and
writes without allocating, and Compose only recomposes or redraws what read the value.

## The mathematics

**Springs** are solved in one closed form for every damping ratio ζ (under, critical and over),
with a = ζω, q = ω²(ζ−1)(ζ+1) and B = v₀ + a·x₀:

- x(t) = e^(−at) (x₀·C + B·S)
- v(t) = e^(−at) (v₀·C + (q·x₀ − a·B)·S)

where C and S are cos and sin(√−q·t)/√−q under critical damping, their hyperbolic forms over it,
and 1 and t at it; S uses its series near zero so nothing divides by a vanishing frequency. The
moment a spring is done comes from Newton's method on its decaying envelope, so a bouncy spring's
last overshoot is never cut short.

**Tweens** inherit velocity. A tween started while the value moves keeps that speed: on a curve that
starts gently (slope at most 3) the velocity is carried exactly by a correction term
g(τ) = τ(1 − τ/L)², which is zero at both ends and has slope one at the start; on a steep curve the
velocity is added. A value at rest starts the curve exactly as drawn.

**Decay** slows by friction k: v(t) = v₀·e^(−kt) and x(t) = x₀ + v₀/k·(1 − e^(−kt)). It ends when the
speed left is under the threshold.

**Velocity from a gesture** (`DragVelocity`) is the slope of a least-squares line through the last
100 ms of samples, the most recent weighted most (20 ms recency). A finger held still for 40 ms
lets go at zero.

**Repeats** play any finite motion again; an iteration played backwards has its velocity negated.

## State transitions

```kotlin
val tabs = rememberMotionTransition(selected, Spring(1f, 900f), order = { tabsInOrder.indexOf(it) })
MotionTransitionLayout(tabs, distance = 24.dp) { tab -> Page(tab) }
```

Each state on screen is a part with a `presence` (0 to 1) and a `position` (−1 to 1 along the
axis). The direction comes from the order (and wraps, for carousels). Changing state mid-flight
retargets every part in place: A to B to C carries on, A to B to A reverses from where it is, and a
burst of rapid changes never jumps. `scrub(toward, fraction)` moves it by hand, `release()` decides
with the velocity, and a part that has left is no longer composed. Fuse's tabs use it.

## Layout motion and shared elements

`Modifier.motionBounds(motion)` moves an element from where it was laid out to where it is now
whenever layout moves it (a reflow, a window resized, an item inserted), and keeps chasing if the
destination moves again. In `MotionSpace.PARENT` a scrolling parent carries it at once; in `ROOT`
everything is animated.

`Modifier.sharedMotion(shared, key)` hands an element between two screens: bounds, corner radius and
velocity travel from the one leaving to the one arriving, and a destination that keeps moving is
chased, never restarted.

## Gestures and every input

```kotlin
val offset = remember { FuselineValue(0f) }
Box(Modifier.dragMotion(offset, Orientation.Horizontal, onRelease = { t -> flingTo({ snapPoint(it) }, Decay(), Spring(), t) }))
```

`dragMotion` reads touch, mouse and trackpad the same way; a cancelled pointer lets go where it is.
`nudge` adds a step from the keyboard or the D-pad, adding up quick presses from the target so they
never stutter. `StickDrive` turns analog-stick deflection into a drag with its own velocity, and
`ScrollDrive` turns a scroll-wheel or trackpad stream into a drag that flings when it goes quiet.

## Choreography

```kotlin
val intro = Timeline(3_200) {
    track("trace") { at(0, 0f); at(1_100, 1f, Curves.Standard) }
    stagger(listOf("a", "b", "c"), everyMs = 40) { at(1_600, 0f); at(1_900, 1f, Curves.Enter) }
    include(sparkle, atMs = 2_000, speed = 1.5f, prefix = "spark.")
}
val play = rememberTimelinePlayer(intro, reduced = Fuse.motion.reduced)
```

Timelines nest (`include`, `Timeline.sequence`, `Timeline.parallel`), stagger, and repeat (straight
again or back and forth). A player plays at any `speed`, `reverse()`s, `seek`s and `skip`s without a
jump, and every track gives its exact `velocity`.

## Semantic motion, accessibility and the environment

Fuse code asks for the meaning, not the numbers: `focus()`, `selection()`, `follow()`, `settle()`,
`enter()`, `exit()`, `dismiss()`, `reveal()`, `navigation()`, `press()`, `hover()`,
`sharedElement()`, `overscroll()`. `FuselineMotion` answers for the person's motion level:

- **Reduced**: no overshoot, no travel (`travel()` is zero), short fades instead of slides;
  ambient motion, parallax, drift and sweeps are off.
- **Minimal**: overshoot gentled, times shortened.
- **Standard** and **Enhanced**: the full motion.

`adapt(motion)` applies the level to any motion. A `MotionEnvironment` (low power, thermal pressure)
trims decoration only: interaction, focus, navigation and gestures keep their motion. A level
changed mid-motion is a retarget, so it never jumps. The tuning of every intent at every level is
pinned in `SemanticMotionTest`.

## The frame driver

Every move on a frame clock shares one frame callback (`FrameDriver`): a move hands the driver its
step and waits once, until it ends; the driver waits for each frame once and steps them all, from
1 value to 1,000 and beyond. A move retargeted keeps its place, and the driver lets go of the frame
clock when nothing moves (true idle: no frame is asked for). Steady frames allocate nothing per
value.

`FramePacing` measures the real frame interval (30 to 240 Hz and anything between, never assumed to
be 60) and whether frames run late. Under load only decoration thins out (`shouldDrawDecoration`);
physics is always worked out from the real frame time, so motion looks the same however busy the
device is.

## Debugging

- `MotionTrace.enabled = true` records starts, retargets, seeks, gesture takeovers, releases,
  destinations, settles, transitions and reversals in a ring buffer (`MotionTrace.events()`).
- `MotionInspector.enabled = true` watches every value in motion: its target, motion, progress,
  velocity and a short history, and the frame's cost. `MotionInspectorPanel()` draws it.
- Off, both cost nothing and record nothing.
- Tests drive time with `MotionClock` (a deterministic clock in the tests), so every motion replays
  exactly.

## The one bridge

Lazy lists move their items into new places themselves, and they only accept a Compose spec for
it. `FuselineBridge.placement(motion)` hands them a spec whose every number comes from Fuseline's
own tracks. It is the only file in Fuse allowed to import `androidx.compose.animation`;
`OwnMotionTest` fails the build if anything else does.

## Speed

Fuseline 3 must beat both Fuseline 2 and Compose on every comparable benchmark, in time and in
memory. `MotionBenchmark` (values, retargeting, velocity, decay, gestures, transitions, timelines,
scheduling, refresh rates) and `UiMotionBenchmark` (whole interface paths, composition, layout and
drawing included) measure all three and fail if Fuseline 3 is not first on any row. Fuseline 2 is
kept, unchanged, in the tests (package `...fuseline.v2`) as the baseline. Run them with:

```
./gradlew :ui:fuseline:desktopTest --tests '*MotionBenchmark' -Pfuse.bench=true
```

They write `ui/fuseline/build/motion-bench.md` and `ui/fuseline/build/ui-motion-bench.md`.

### Method

- One thread, a manual frame clock, and a scope that runs every move inline as its frame arrives,
  so only the engines' own work is timed.
- Every engine gets the same values, motions, curves, counts and frame times.
- Each engine is warmed up first (4 rounds), then measured in 9 rounds that rotate the order; the
  median round counts.
- Rounds are long enough to be milliseconds of work: 600 frames, 3,000 where a frame's work is
  small (one value, a few readings).
- Time and bytes allocated are per frame, less the harness's own cost (an empty workload measured
  the same way). Below 50 ns or half a byte a cost is lost in the measuring itself and counts as a tie.
- Interface paths are timed whole through Compose's test clock: 2 warm-up runs, then 5 rounds in
  rotating order, median.

### Results

Measured on OpenJDK 64-Bit Server VM 21.0.12.1, 4 processors, Linux amd64. Every row is Fuseline 3 first, in time and in memory; who is
first is decided on the unrounded numbers.

Values, retargeting, velocity, decay, gestures, transitions, timelines, scheduling and refresh
rates (per frame):

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

Whole interface paths (per frame, composition, layout and drawing included; memory is what every
thread allocated):

| Interface path | Fuseline 3 | Fuseline 2 | Compose | Fuseline 3 memory | Fuseline 2 memory | Compose memory | First |
|---|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 620 µs | n/a | 2285 µs | 21.4 KB | n/a | 198.3 KB | Fuseline 3 |
| shared element, moving destination | 763 µs | n/a | 976 µs | 11.5 KB | n/a | 14.0 KB | Fuseline 3 |
| rapid tab switching | 2565 µs | 2725 µs | 2838 µs | 36.6 KB | 50.4 KB | 129.3 KB | Fuseline 3 |

The Fuseline 3 memory column is a few hundred bytes whatever the number of values: it is the frame
clock's own wait, which every engine pays once a frame, while Fuseline 2 and Compose allocate per
value.

## Tests

`ui/fuseline/src/desktopTest`, run on Linux, Windows and macOS in CI, and Fuseline's Android build
checked by `FuselineAndroidTest` in `app/android`:

- **Math**: curves and their slopes against finite differences, springs against their closed forms
  at every damping ratio, velocity handoffs between every pair of motions (`CurveMathTest`,
  `SpringSolverTest`, `HandoffTest`, `MotionMathTest`).
- **Values**: every operation, ownership, gestures, decay, seek, retarget, fuzzed sequences of all
  of them never jumping (`FuselineValueTest`, `RetargetTest`, `ConverterTest`).
- **Driver**: 1 to 1,000 values, true idle, no per-value allocation, refresh rates from 30 to 240 Hz
  (`FrameDriverTest`).
- **Input**: touch, mouse, trackpad, cancel, keyboard steps, stick and scroll (`MotionInputTest`).
- **Transitions, layout motion, shared elements and timelines** (`TransitionTest`,
  `LayoutMotionTest`, `TimelineTest`).
- **Real interaction sequences**: rapid tab switching from the keyboard, a controller, the mouse
  and touch mixed, with the window resized mid-transition, and two screens of different density
  moving together (`InteractionTest`).
- **Semantics, accessibility, trace and inspector** (`SemanticMotionTest`).
- **The import guard** (`OwnMotionTest`) and Compose interoperation (`FuselineComposeTest`).
- **Benchmarks** (`MotionBenchmark`, `UiMotionBenchmark`, and Fuseline 2's own
  `FuselineBenchmark` and `TransitionBenchmark`, kept).
