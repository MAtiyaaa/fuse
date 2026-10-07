# Fuseline by Fuse

Fuseline by Fuse is Fuse's animation engine. It is named after the line of a fuse, the wire in Fuse's
logo that carries the spark. Every animation in Fuse runs on it, from a focus lift to the setup's
opening sequence.

It lives in `ui/fuseline` (package `io.github.matiyaaa.fuse.ui.fuseline`). Fuse 0.3.7 brought
**Fuseline 3**, Fuse 0.3.7.5 **Fuseline 3.1** ([what 3.1 adds](#fuseline-31)), and Fuse 0.4.0
brings **Fuseline 4** ([what 4 changes](#fuseline-4)). All are built around two rules:

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
value and waits once, until it ends; the driver waits for each frame once and brings up only the
moves that need that frame (see [Fuseline 4](#fuseline-4)), in the order they started. A move
retargeted keeps its place, and the driver lets go of the frame clock when nothing moves (true idle:
no frame is asked for). Steady frames allocate nothing per value.

`FramePacing` measures the real frame interval (30 to 240 Hz and anything between, never assumed to
be 60) and whether frames run late. Under load only decoration thins out (`shouldDrawDecoration`);
physics is always worked out from the real frame time, so motion looks the same however busy the
device is.

## Fuseline 3.1

Fuseline 3.1 keeps every curve, spring and motion of Fuseline 3 exactly as they were, and takes work
off the frames around them:

- **Values that follow without a coroutine.** `fuselineFloat`, `fuselineColor`, `fuselineDp` and the
  others (everything built on `rememberFollowing`) used to start a channel and an effect coroutine for
  every value. In 3.1 a new target is handed to the value as composition is applied, and the frame
  driver moves it directly (`FuselineValue.follow`, `FrameDriver.start`); when the value leaves
  composition its move ends with it. Composing a page of tiles costs a few small objects per value
  instead of a coroutine each.
- **No invisible frames.** A frame that moves a value less than an eighth of its threshold (a
  little over a thousandth for an opacity) is worked out but not shown: what reads the value is not
  redrawn or laid out again for a change nobody could see. Where the motion lands is always shown,
  and a seek always shows the moment it asked for.
- **Decoration yields to the person.** `FramePacing.input()` is called by the input router for every
  button, key and stick movement. For a moment after it, and for as long as what it set moving is
  still moving, decoration (a theme's room, the background's slow drift, a shimmer) holds its frame
  (`FramePacing.decorationHeld`) and carries on from there afterwards, never jumping. Motion that
  runs on its own, with nobody touching anything, never holds decoration, so at rest everything
  moves exactly as before.
- **Paced decoration.** `decorationFrames(fps)` gives a decorative loop its time only when an update
  is due, and waits between them instead of taking every frame of the display: a room that needs 30
  updates a second wakes about 31 times a second on a 125 Hz display instead of 125, and still makes
  every update (`DecorationTest`, on a virtual clock where frames and waits are exact).
- **Cheaper publishing.** A value's change is published to Compose from a counter of its own, without
  reading the state it writes, and the visibility check is folded into the same loop that solves the
  frame.

## Fuseline 4

Fuseline 4 keeps every curve, spring and motion of Fuseline 3.1 and changes what the engine does
with time. Every motion in Fuseline is solved in closed form: a spring's displacement is
e^(−at)(x₀·C(t) + B·S(t)), a tween is its curve at the share of its time played, a decay is
x₀ + v₀/k·(1 − e^(−kt)). Where a value is at any moment follows from when its motion started and
where from. Fuseline 3.1 still stepped every value through every frame; Fuseline 4 asks, for each
value and each frame, whether that frame needs the value at all.

- **Worked out when read.** `FuselineValue` keeps the state it was last worked out at and the frame
  it is as of. Reading it (`value`, `floatValue`, `component`, a retarget, a takeover, a gesture
  catching it) works it out from its motion's formula for the frame being shown: exactly what
  stepping it through every frame would have left. A read during a frame, of a value the frame
  hasn't reached yet, gets the frame before, as it always did.
- **The event horizon.** After stepping a value, the engine proves from its motion's own formula
  the first moment it could move far enough from what readers last saw to be seen (an eighth of its
  threshold, the same rule Fuseline 3.1 published by), and leaves it alone until then (`Track.calmUntil`):
  - a spring: its speed never exceeds e^(−rt)(|v₀| + |k|·min(t, cap)) from here on, and its
    displacement e^(−rt)(|x₀| + |B|·min(t, cap)) (the same bounds that say when it comes to rest), so
    it cannot cover the room left before room ÷ that speed; and where the displacement alone stays
    within the room, never again before it arrives. A spring's last jump to its target, at rest, is
    a moment of its own: the horizon never reaches past it.
  - a tween: no faster than its distance times the curve's steepest slope from here on (a proven
    bound for each of 256 stretches of the curve, from the extremes of x′(t) and y′(t)), plus the
    speed it is blending away.
  - a decay: exactly, −ln(1 − room·k / |v|) / k.
  - a snap or a delay: until its moment.
  A horizon only a few frames away isn't worth proving: the value is stepped instead. Every bound is
  checked against the motion itself every 10 µs in `Fuseline4Test`.
- **Resting unread.** A value's readers subscribe to it like any Compose state. When it has told
  them of a change and none of them has read it since, every reader it had is already redrawing or
  gone: another frame of it would tell nobody anything. So the engine leaves it alone until it
  arrives (an arrival is known in advance, and lands on exactly the frame it always did). The first
  read brings it back, worked out exactly where its motion has reached. This is what makes an
  offscreen tile, a value nobody draws or a page kept in the background cost nothing per frame.
- **Shared solutions.** A spring's e^(−at)·C(t) and e^(−at)·S(t) depend on the spring and the moment,
  not on the value: a kernel per stiffness and damping (`SpringKernel`) solves them once a moment for
  every value and component on that spring, and carries a value on from its own solution a frame ago
  with a rotation and a scale (a few multiplications, in double precision, solved afresh every 64
  steps). A spring only runs its Newton solve for when it comes to rest once a one-logarithm bound
  says it could be near it. A tween's curve is solved once per value and moment however many
  components it has, and once per moment for every tween on the same curve.
- **The driver.** Moves due every frame are kept in a list in join order; the rest wait in an
  indexed heap ordered by when they are due. A frame brings up, in join order, the listed moves, the
  heap's moves whose time has come and the moves that joined. A move brought into a frame by an
  earlier move's work (a retarget from another value's block) is put in its place in that frame. A
  move finishing is checked after its frame, as before, so one that gives itself a shorter motion
  from its own block still arrives that frame.
- **Exactly the same motion.** Fuseline 3.1 is kept in the tests (package `v31`).
  `Fuseline4EquivalenceTest` runs both through the same random histories (every kind of motion,
  retargets, seeks, gestures, takeovers, cancellations, motion-speed settings, moves made from inside
  other moves' frames, values read and unread, and frames from 30 to 240 Hz with jitter, drops and
  stalls) and compares, after every operation and every frame: position, velocity, target, owner,
  progress, time played and left, which moves finished and in what order, and what every reader on
  screen shows. Positions and velocities agree to within one step of a float (a spring carried on
  from the frame before can round its last bit the other way); everything else agrees exactly, with
  one exception, stated: a value brought back from resting unread can tell its readers of its next
  change a frame sooner or later than frame-by-frame publishing would have (which would have gone on
  telling nobody while it rested), so a reader can show a place up to two eighths of a threshold
  from what 3.1 would show (for a position, an eighth of a pixel at most: two eighths of its 0.5 px
  threshold). Thousands of histories run in every test run; 60,000 were run before release.
- **Why values don't stop asking for frames.** The driver keeps asking for frames while any move is
  under way, because a frame time is the only exact clock: a value read while the engine slept
  would have no exact frame time to be worked out at. An idle frame (every value resting or before
  its horizon) costs the driver a look at its heap and nothing per value. Decoration, whose updates
  are worked out from the real time whenever they come, does wait between updates
  (`decorationFrames`), and updates less often still when the device is hot or saving power
  (`FramePacing.devicePressure`, `FramePacing.powerSaving`, set from Android's thermal status and
  battery saver).
- **Seen from inside.** `MotionInspector` shows, every frame, how many values are moving, how many
  were stepped, how many wait for their horizon and how many rest unread, springs solved, shared and
  stepped, curves shared, values woken by a read, and when the next value is due; for each value,
  how the engine treats it. Fuse shows it in Developer options ("Motion inspector").

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

Every Fuseline is measured against every Fuseline before it and against Compose, in time and in
memory. Fuseline 3, 2 and 1 are kept in the tests exactly as they shipped (packages
`...fuseline.v3`, `...fuseline.v2` and `...fuseline.v1`). `MotionBenchmark` (values, retargeting,
velocity, decay, gestures, transitions, timelines, scheduling, refresh rates) and `UiMotionBenchmark`
(whole interface paths, composition, layout and drawing included, pages of tiles, a decorative room)
measure all five, and fail if Fuseline 3.1 loses any row.

Where Fuseline 3.1 changes nothing (a decay, a gesture, a timeline), it runs Fuseline 3's own code,
and the two land on either side of each other from one run to the next on the same machine. Those
rows are reported as a tie with Fuseline 3, never as a win; Fuseline 3.1 must still be ahead of
Fuseline 2, Fuseline 1 and Compose there. Where Fuseline 3.1 changes something (tweens and colours,
whose long slow frames are no longer shown; values that follow targets; decoration), it is measured
ahead. Run them with:

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

Measured on OpenJDK 64-Bit Server VM 21.0.12.1, 4 processors, Linux amd64. Of 43 rows, Fuseline 3.1
is first on 27, tied with the quickest within this machine's run-to-run noise on 14 (where it runs
Fuseline 3's own paths), and behind Fuseline 3 on 2: a hundred two-number springs given a new target
every frame (0.2 µs a frame) and the shared element's memory (1.2 KB a frame). On a tied row the
quickest may be any of the Fuselines; Fuseline 3.1 is ahead of Compose on every row.

Values, retargeting, velocity, decay, gestures, transitions, timelines, scheduling and refresh
rates (per frame):

| Case | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Fuseline 3.1 memory | Fuseline 3 memory | Fuseline 2 memory | Fuseline 1 memory | Compose memory | First |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 tweens | 0.74 µs | 0.81 µs | 0.80 µs | 0.85 µs | 1.17 µs | 357 B | 357 B | 437 B | 437 B | 445 B | Fuseline 3.1 |
| 1 springs | 0.82 µs | 0.76 µs | 0.81 µs | 0.80 µs | 1.06 µs | 357 B | 357 B | 437 B | 437 B | 445 B | Tie (within run-to-run noise) |
| 1 vector springs | 0.55 µs | 0.54 µs | 0.59 µs | 0.63 µs | 1.32 µs | 239 B | 239 B | 303 B | 303 B | 453 B | Tie (within run-to-run noise) |
| 1 colours | 0.85 µs | 0.79 µs | 0.91 µs | 0.87 µs | 1.24 µs | 357 B | 357 B | 445 B | 445 B | 453 B | Tie (within run-to-run noise) |
| 100 tweens | 6.08 µs | 8.93 µs | 15.88 µs | 14.21 µs | 176.43 µs | 379 B | 379 B | 4419 B | 4419 B | 50363 B | Fuseline 3.1 |
| 100 springs | 12.78 µs | 13.71 µs | 16.80 µs | 16.74 µs | 184.82 µs | 379 B | 379 B | 4419 B | 4419 B | 50363 B | Fuseline 3.1 |
| 100 vector springs | 12.52 µs | 13.98 µs | 18.47 µs | 17.62 µs | 164.07 µs | 379 B | 379 B | 5219 B | 5219 B | 51163 B | Fuseline 3.1 |
| 100 colours | 7.65 µs | 9.33 µs | 15.23 µs | 14.73 µs | 147.28 µs | 379 B | 379 B | 5219 B | 5219 B | 51163 B | Fuseline 3.1 |
| 1000 tweens | 51.28 µs | 73.45 µs | 103.99 µs | 141.40 µs | 1692.95 µs | 379 B | 379 B | 40419 B | 40419 B | 503963 B | Fuseline 3.1 |
| 1000 springs | 72.66 µs | 99.16 µs | 119.26 µs | 117.97 µs | 1670.20 µs | 379 B | 379 B | 40419 B | 40419 B | 503963 B | Fuseline 3.1 |
| 1000 vector springs | 138.21 µs | 149.17 µs | 203.84 µs | 211.99 µs | 2066.52 µs | 379 B | 379 B | 48419 B | 48419 B | 511963 B | Fuseline 3.1 |
| 100 springs retargeted once | 3.26 µs | 3.60 µs | 4.53 µs | n/a | 54.43 µs | 192 B | 192 B | 1781 B | n/a | 20264 B | Fuseline 3.1 |
| 100 springs retargeted every frame | 0.37 µs | 0.52 µs | 25.21 µs | n/a | 1182.02 µs | 32 B | 32 B | 24664 B | n/a | 559003 B | Fuseline 3.1 |
| 1000 springs retargeted every frame | 5.54 µs | 5.78 µs | 255.28 µs | n/a | 11829.74 µs | 320 B | 320 B | 242522 B | n/a | 5590002 B | Fuseline 3.1 |
| 100 vector springs retargeted every frame | 0.46 µs | 0.26 µs | 43.71 µs | n/a | 1117.23 µs | 32 B | 32 B | 44661 B | n/a | 562997 B | NOT FIRST |
| 1000 vector springs retargeted every frame | 3.52 µs | 3.65 µs | 348.82 µs | n/a | 11640.58 µs | 454 B | 454 B | 442842 B | n/a | 5949416 B | Fuseline 3.1 |
| tween velocity, 1000 readings | 47.15 µs | 47.13 µs | 52.56 µs | n/a | 179.81 µs | 0 B | 0 B | 0 B | n/a | 0 B | Tie (within run-to-run noise) |
| 1 decays | 0.70 µs | 0.73 µs | n/a | n/a | 1.53 µs | 409 B | 409 B | n/a | n/a | 492 B | Fuseline 3.1 |
| 100 decays | 7.14 µs | 7.29 µs | n/a | n/a | 122.68 µs | 459 B | 459 B | n/a | n/a | 58363 B | Fuseline 3.1 |
| 100 values tracking a gesture | 36.07 µs | 36.86 µs | n/a | n/a | 145.21 µs | 61 B | 61 B | n/a | n/a | 88080 B | Fuseline 3.1 |
| tab transitions | 1.36 µs | 1.36 µs | 1.58 µs | n/a | 4.58 µs | 595 B | 593 B | 860 B | n/a | 2351 B | Tie (within run-to-run noise) |
| tab changes every 3 frames | 2.46 µs | 3.18 µs | 5.27 µs | n/a | 40.56 µs | 516 B | 516 B | 1848 B | n/a | 22082 B | Fuseline 3.1 |
| tab reversal every 4 frames | 1.55 µs | 1.48 µs | 4.17 µs | n/a | 26.71 µs | 503 B | 503 B | 1969 B | n/a | 15287 B | Tie (within run-to-run noise) |
| timeline, 20 tracks | 0.05 µs | 0.04 µs | 0.10 µs | n/a | 2.74 µs | 0 B | 0 B | 0 B | n/a | 917 B | Fuseline 3.1 |
| timeline seek, 20 tracks | 0.39 µs | 0.37 µs | 0.79 µs | n/a | n/a | 0 B | 0 B | 0 B | n/a | n/a | Tie (within run-to-run noise) |
| timeline reverse, 20 tracks | 0.44 µs | 0.42 µs | n/a | n/a | n/a | 0 B | 0 B | n/a | n/a | n/a | Tie (within run-to-run noise) |
| driver churn, 10 | 7.34 µs | 7.18 µs | 6.18 µs | 7.53 µs | 20.77 µs | 2423 B | 2407 B | 2646 B | 2566 B | 6718 B | Tie (within run-to-run noise) |
| driver churn, 100 | 32.09 µs | 30.80 µs | 34.88 µs | 33.34 µs | 115.76 µs | 19749 B | 19592 B | 21618 B | 20818 B | 67182 B | Tie (within run-to-run noise) |
| driver churn, 1000 | 330.33 µs | 330.87 µs | 357.84 µs | 346.07 µs | 1499.80 µs | 192997 B | 191443 B | 211343 B | 203343 B | 671833 B | Tie (within run-to-run noise) |
| idle, 1000 settled values | 0.00 µs | 0.00 µs | 0.00 µs | 0.00 µs | 0.00 µs | 0 B | 0 B | 0 B | 0 B | 0 B | Fuseline 3.1 |
| 1000 springs at 30 Hz | 77.41 µs | 82.80 µs | 113.68 µs | 114.77 µs | 1589.12 µs | 472 B | 472 B | 40512 B | 40512 B | 583976 B | Fuseline 3.1 |
| 1000 springs at 60 Hz | 78.68 µs | 80.75 µs | 120.56 µs | 115.92 µs | 1584.97 µs | 472 B | 472 B | 40512 B | 40512 B | 583976 B | Fuseline 3.1 |
| 1000 springs at 90 Hz | 84.11 µs | 89.57 µs | 122.78 µs | 115.67 µs | 1638.31 µs | 467 B | 467 B | 40507 B | 40507 B | 583971 B | Fuseline 3.1 |
| 1000 springs at 120 Hz | 71.53 µs | 80.21 µs | 120.83 µs | 114.55 µs | 1626.83 µs | 465 B | 465 B | 40505 B | 40505 B | 583969 B | Fuseline 3.1 |
| 1000 springs at 144 Hz | 77.24 µs | 81.95 µs | 112.17 µs | 131.26 µs | 1427.83 µs | 463 B | 463 B | 40503 B | 40503 B | 583967 B | Fuseline 3.1 |
| 1000 springs at 165 Hz | 82.27 µs | 77.52 µs | 113.29 µs | 133.59 µs | 1552.09 µs | 462 B | 462 B | 40502 B | 40502 B | 583966 B | Tie (within run-to-run noise) |
| 1000 springs at 240 Hz | 70.01 µs | 77.38 µs | 112.51 µs | 110.39 µs | 1575.93 µs | 460 B | 460 B | 40500 B | 40500 B | 583964 B | Fuseline 3.1 |

Whole interface paths (per frame, composition, layout and drawing included; memory is what every
thread allocated):

| Interface path | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Fuseline 3.1 memory | Fuseline 3 memory | Fuseline 2 memory | Fuseline 1 memory | Compose memory | First |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 676 µs | 671 µs | n/a | n/a | 2249 µs | 21.4 KB | 21.5 KB | n/a | n/a | 197.4 KB | Tie (within run-to-run noise) |
| shared element, moving destination | 698 µs | 631 µs | n/a | n/a | 803 µs | 11.4 KB | 10.2 KB | n/a | n/a | 14.0 KB | NOT FIRST |
| rapid tab switching | 2658 µs | 2536 µs | 2721 µs | 2566 µs | 2754 µs | 35.5 KB | 35.6 KB | 49.2 KB | 49.3 KB | 125.9 KB | Tie (within run-to-run noise) |
| a page of 120 tiles, 3 values each, composed | 2156 µs | 2853 µs | n/a | n/a | 2999 µs | 215.0 KB | 708.7 KB | n/a | n/a | 742.0 KB | Fuseline 3.1 |
| selection moving across 120 tiles | 1917 µs | 1989 µs | n/a | n/a | 2221 µs | 5.1 KB | 7.0 KB | n/a | n/a | 22.4 KB | Fuseline 3.1 |

A theme's room needing 30 updates a second on a 120 Hz screen, redrawn per second while buttons are
pressed (Fuseline 3.1 holds it still and carries on afterwards):

| Room | Fuseline 3.1 | Fuseline 3 | Compose |
|---|---|---|---|
| while buttons are pressed | 3 | 44 | 130 |

At rest the same room wakes about 31 times a second instead of 125 on a 125 Hz display and still
makes every update (`DecorationTest`, measured on an exact virtual clock: Compose's test clock skips
the waits a paced room makes).

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
- **Decoration** (3.1): a paced loop makes every update it needs with about one wake each, and holds
  still while the person is doing something, carrying on without a jump (`DecorationTest`).
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
