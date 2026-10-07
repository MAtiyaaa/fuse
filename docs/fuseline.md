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
memory. Fuseline 3.1, 3, 2 and 1 are kept in the tests exactly as they shipped (packages
`...fuseline.v31`, `...fuseline.v3`, `...fuseline.v2` and `...fuseline.v1`). `MotionBenchmark`
(values, scale, retargeting, gestures, decay, transitions, timelines, scheduling, refresh rates and
irregular frames, sustained work) and `UiMotionBenchmark` (whole interface paths, composition,
layout and drawing included) measure all six engines on the same work.

Every workload is measured three ways, because what Fuseline 4 saves depends on who is looking:

- **drawn**: a value's readers run again only when what they read changed, as Compose redraws.
  This is how Fuse uses its values.
- **read**: every value is read every frame, whether it changed or not.
- **unread**: nothing reads the values (offscreen, or not drawn).

Where nobody looks, or a value moves too little to be seen, Fuseline 4 does almost nothing, and it
is many times quicker than every other engine. Where every value visibly moves every frame and is
read every frame, every engine has the same essential work left to do (solve each value, tell its
readers, and let them read it), and most of each frame is Compose's own snapshot work, which no
engine can skip: there Fuseline 4 and 3.1 are within this machine's run-to-run noise of each other,
and those rows are reported as unresolved, never as wins. Run them with:

```
./gradlew :ui:fuseline:desktopTest --tests '*MotionBenchmark' -Pfuse.bench=true
```

They write `ui/fuseline/build/motion-bench.md` and `ui/fuseline/build/ui-motion-bench.md`, with
allocations, the spread of frame times (p50 to worst) and how often each engine asked for another
frame alongside the times below. `-Pfuse.bench.rounds=N` sets the rounds and
`-Pfuse.bench.only=text` runs only the cases whose name contains the text.

### Method

- One thread; a fresh manual frame clock and an inline scope for every round, so only the engines'
  own work is timed.
- Every engine gets the same values, motions, targets, frame times and reads: each case is written
  once, against one interface every engine implements.
- Each engine is warmed up (3 rounds per case), then measured in 15 rounds that rotate the order, so no engine always goes first or last.
- A round's cost is its mean time per frame less the harness's own cost with no engine, measured
  the same way; the median round counts.
- A row has a winner only when the winner's whole 95% bootstrap interval (2,000 resamples of the
  rounds) is below every other engine's. Otherwise it is "unresolved", whoever is ahead. Below
  0.05 µs a frame is below what this harness can measure.
- Interface paths are timed whole through Compose's test clock, with a warm-up and rounds in
  rotating order; the median counts.
- Measured on a computer only: OpenJDK 64-Bit Server VM 21.0.12.1, 4 processors, Linux amd64. Fuseline 4 has not yet been measured on an Android device or
  on Windows or macOS.

### Results

Of 111 rows, Fuseline 4 is clearly first on 52; 57 are unresolved (the engines' intervals overlap: within this machine's run-to-run noise, whoever is ahead); and another engine is clearly first on 2: 1000 colours, drawn: Fuseline 2, 100 decays, read: Fuseline 3.1.

Those two, plainly:

- **1000 colours, drawn.** Fuseline 4 and 3.1 take the same time (288.8 and 288.6 µs a frame);
  Fuseline 2 is quicker than both. The gap is in work Fuseline 4 inherits unchanged from 3.1
  (the two take the same time to the tenth of a microsecond), not in anything Fuseline 4 adds; it
  is open, and measured here as it stands.
- **100 decays, read every frame.** 11.8 µs a frame against 3.1's 10.4. A decay shares nothing
  with another (each starts from its own speed), so nothing is solved once for many, and every
  value pays Fuseline 4's own bookkeeping (when it is next due, whether anyone reads it) without a
  saving to set against it. Drawn, unread or retargeted, decays are level with 3.1 or many times
  quicker.

Fuseline 4's largest gains are where most of Fuse's motion is: values nobody is looking at (a
list scrolled away, a page behind another), values given a new target every frame (a selection
following input, a scroll), values following a finger, and many values on one spring.

#### Values in motion: time per frame
| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| 1 tweens, drawn | 1.09 µs | 1.05 µs | 1.34 µs | 1.40 µs | 1.39 µs | 1.96 µs | -3.3% | 0.97× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 tweens, read | 0.97 µs | 0.93 µs | 0.97 µs | 1.05 µs | 1.05 µs | 1.45 µs | -4.2% | 0.96× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 tweens, unread | 0.87 µs | 0.95 µs | 1.13 µs | 1.22 µs | 1.24 µs | 1.78 µs | 8.8% | 1.10× | Fuseline 4 |
| 1 springs, drawn | 1.42 µs | 1.38 µs | 1.49 µs | 1.44 µs | 1.46 µs | 1.99 µs | -3.0% | 0.97× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 springs, read | 1.10 µs | 1.07 µs | 1.09 µs | 1.13 µs | 1.12 µs | 1.51 µs | -2.2% | 0.98× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 springs, unread | 0.96 µs | 1.22 µs | 1.24 µs | 1.32 µs | 1.30 µs | 1.85 µs | 21.7% | 1.28× | Fuseline 4 |
| 1 vector springs, drawn | 1.06 µs | 1.05 µs | 1.12 µs | 1.18 µs | 1.20 µs | 2.31 µs | -0.8% | 0.99× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 vector springs, read | 0.86 µs | 0.81 µs | 0.83 µs | 0.86 µs | 0.90 µs | 1.70 µs | -5.6% | 0.95× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 vector springs, unread | 0.69 µs | 0.89 µs | 0.91 µs | 0.98 µs | 0.99 µs | 2.06 µs | 22.3% | 1.29× | Fuseline 4 |
| 1 colours, drawn | 1.34 µs | 1.30 µs | 1.51 µs | 1.22 µs | 1.25 µs | 1.79 µs | -3.1% | 0.97× | unresolved (Fuseline 2 ahead, intervals overlap) |
| 1 colours, read | 1.26 µs | 1.16 µs | 1.25 µs | 1.23 µs | 1.26 µs | 1.73 µs | -8.0% | 0.93× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 colours, unread | 0.96 µs | 1.10 µs | 1.26 µs | 1.28 µs | 1.23 µs | 1.79 µs | 12.8% | 1.15× | Fuseline 4 |
| 100 tweens, drawn | 12.88 µs | 13.39 µs | 27.98 µs | 30.87 µs | 30.66 µs | 200.66 µs | 3.8% | 1.04× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 tweens, read | 8.76 µs | 9.20 µs | 13.11 µs | 16.10 µs | 15.21 µs | 156.65 µs | 4.7% | 1.05× | Fuseline 4 |
| 100 tweens, unread | 1.44 µs | 8.96 µs | 15.59 µs | 19.27 µs | 18.75 µs | 183.69 µs | 83.9% | 6.23× | Fuseline 4 |
| 100 springs, drawn | 25.69 µs | 26.65 µs | 27.90 µs | 31.88 µs | 31.36 µs | 195.19 µs | 3.6% | 1.04× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 springs, read | 9.84 µs | 11.72 µs | 13.42 µs | 15.82 µs | 15.59 µs | 152.78 µs | 16.0% | 1.19× | Fuseline 4 |
| 100 springs, unread | 1.00 µs | 15.05 µs | 16.27 µs | 19.20 µs | 19.83 µs | 181.40 µs | 93.3% | 15.01× | Fuseline 4 |
| 100 vector springs, drawn | 27.33 µs | 32.43 µs | 33.50 µs | 39.95 µs | 39.47 µs | 220.59 µs | 15.7% | 1.19× | Fuseline 4 |
| 100 vector springs, read | 12.42 µs | 17.76 µs | 18.85 µs | 22.77 µs | 22.43 µs | 176.32 µs | 30.0% | 1.43× | Fuseline 4 |
| 100 vector springs, unread | 1.07 µs | 20.15 µs | 21.26 µs | 26.23 µs | 26.44 µs | 207.38 µs | 94.7% | 18.84× | Fuseline 4 |
| 100 colours, drawn | 25.27 µs | 23.14 µs | 39.37 µs | 22.50 µs | 23.83 µs | 186.47 µs | -9.2% | 0.92× | unresolved (Fuseline 2 ahead, intervals overlap) |
| 100 colours, read | 17.05 µs | 16.08 µs | 23.80 µs | 19.73 µs | 21.47 µs | 177.59 µs | -6.0% | 0.94× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 100 colours, unread | 1.05 µs | 12.56 µs | 18.88 µs | 19.84 µs | 21.38 µs | 191.84 µs | 91.7% | 12.01× | Fuseline 4 |
| 1000 tweens, drawn | 144.16 µs | 142.57 µs | 324.75 µs | 366.53 µs | 359.05 µs | 2391.52 µs | -1.1% | 0.99× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1000 tweens, read | 88.64 µs | 86.51 µs | 129.02 µs | 165.36 µs | 155.82 µs | 1827.70 µs | -2.5% | 0.98× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1000 tweens, unread | 5.85 µs | 89.68 µs | 175.89 µs | 211.89 µs | 206.46 µs | 2054.35 µs | 93.5% | 15.33× | Fuseline 4 |
| 1000 springs, drawn | 326.24 µs | 317.21 µs | 338.51 µs | 371.64 µs | 390.09 µs | 2445.19 µs | -2.8% | 0.97× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1000 springs, read | 105.17 µs | 130.36 µs | 132.51 µs | 164.20 µs | 163.01 µs | 1762.19 µs | 19.3% | 1.24× | Fuseline 4 |
| 1000 springs, unread | 1.33 µs | 173.36 µs | 183.38 µs | 214.38 µs | 217.47 µs | 2057.55 µs | 99.2% | 130.28× | Fuseline 4 |
| 1000 vector springs, drawn | 347.86 µs | 412.21 µs | 405.06 µs | 492.43 µs | 473.75 µs | 2753.99 µs | 15.6% | 1.18× | Fuseline 4 |
| 1000 vector springs, read | 135.94 µs | 194.70 µs | 199.13 µs | 251.51 µs | 256.86 µs | 2022.47 µs | 30.2% | 1.43× | Fuseline 4 |
| 1000 vector springs, unread | 1.39 µs | 235.71 µs | 233.38 µs | 330.53 µs | 324.75 µs | 2424.67 µs | 99.4% | 169.84× | Fuseline 4 |
| 1000 colours, drawn | 288.83 µs | 288.62 µs | 504.56 µs | 253.10 µs | 280.14 µs | 2222.31 µs | -0.1% | 1.00× | Fuseline 2 |
| 1000 colours, read | 181.36 µs | 174.95 µs | 263.26 µs | 206.30 µs | 218.86 µs | 1994.46 µs | -3.7% | 0.96× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1000 colours, unread | 1.64 µs | 140.50 µs | 215.19 µs | 205.47 µs | 216.52 µs | 2126.11 µs | 98.8% | 85.58× | Fuseline 4 |

#### Scale and kinds of motion: time per frame
| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| 10000 springs, drawn | 6896.57 µs | 7361.26 µs | 6865.54 µs | 7972.38 µs | 8052.06 µs | 43865.86 µs | 6.3% | 1.07× | unresolved (Fuseline 3 ahead, intervals overlap) |
| 10000 springs, read | 1529.31 µs | 1653.25 µs | 1943.75 µs | 2523.19 µs | 2410.94 µs | 26231.01 µs | 7.5% | 1.08× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 10000 springs, unread | 40.90 µs | 2474.22 µs | 2321.04 µs | 3587.64 µs | 3247.27 µs | 35210.67 µs | 98.3% | 60.49× | Fuseline 4 |
| 1000 unrelated springs, staggered, drawn | 33.91 µs | 37.68 µs | 39.67 µs | 47.47 µs | 46.14 µs | 251.83 µs | 10.0% | 1.11× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 unrelated springs, staggered, read | 35.05 µs | 34.54 µs | 35.87 µs | 40.31 µs | 39.93 µs | 181.75 µs | -1.5% | 0.99× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1000 unrelated springs, staggered, unread | 5.97 µs | 20.51 µs | 19.92 µs | 22.87 µs | 22.57 µs | 168.81 µs | 70.9% | 3.44× | Fuseline 4 |
| 1000 springs in their tails, drawn | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | both below floor | n/a | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs in their tails, read | 23.08 µs | 21.87 µs | 21.84 µs | 23.18 µs | 21.81 µs | 23.13 µs | -5.5% | 0.95× | unresolved (Fuseline 1 ahead, intervals overlap) |
| 1000 springs in their tails, unread | 0.15 µs | 0.17 µs | 0.17 µs | 0.18 µs | 0.16 µs | 0.24 µs | 8.9% | 1.10× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 values, mixed motions, drawn | 242.93 µs | 243.37 µs | 249.59 µs | 287.96 µs | 262.84 µs | 1756.53 µs | 0.2% | 1.00× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 values, mixed motions, read | 94.32 µs | 93.05 µs | 100.50 µs | 121.85 µs | 111.55 µs | 1358.63 µs | -1.4% | 0.99× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1000 values, mixed motions, unread | 2.55 µs | 120.06 µs | 125.74 µs | 143.91 µs | 131.77 µs | 1536.77 µs | 97.9% | 47.09× | Fuseline 4 |
| 1000 springs seen again after 5 s unseen | 318.14 µs | 339.63 µs | 354.05 µs | 378.17 µs | 364.46 µs | 2438.27 µs | 6.3% | 1.07× | unresolved (Fuseline 4 ahead, intervals overlap) |

#### Retargeting, gestures, decay: time per frame
| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| 100 springs retargeted once, drawn | 10.31 µs | 11.24 µs | 11.34 µs | 12.79 µs | 14.04 µs | 82.84 µs | 8.2% | 1.09× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 springs retargeted once, read | 5.57 µs | 6.49 µs | 6.78 µs | 7.76 µs | 8.80 µs | 68.35 µs | 14.1% | 1.16× | Fuseline 4 |
| 100 springs retargeted once, unread | 0.81 µs | 6.16 µs | 6.46 µs | 7.66 µs | 8.55 µs | 76.04 µs | 86.9% | 7.62× | Fuseline 4 |
| 100 springs retargeted every frame, drawn | 33.88 µs | 65.32 µs | 66.64 µs | 64.88 µs | 479.53 µs | 1254.36 µs | 48.1% | 1.93× | Fuseline 4 |
| 100 springs retargeted every frame, read | 18.31 µs | 42.86 µs | 45.28 µs | 29.70 µs | 494.93 µs | 1232.39 µs | 57.3% | 2.34× | Fuseline 4 |
| 100 springs retargeted every frame, unread | 18.31 µs | 50.86 µs | 51.33 µs | 38.21 µs | 498.10 µs | 1244.44 µs | 64.0% | 2.78× | Fuseline 4 |
| 1000 springs retargeted every frame, drawn | 444.43 µs | 826.75 µs | 844.07 µs | 688.97 µs | 5960.13 µs | 16388.75 µs | 46.2% | 1.86× | Fuseline 4 |
| 1000 springs retargeted every frame, read | 184.15 µs | 450.98 µs | 457.98 µs | 320.50 µs | 5470.33 µs | 14160.00 µs | 59.2% | 2.45× | Fuseline 4 |
| 1000 springs retargeted every frame, unread | 217.44 µs | 562.22 µs | 583.00 µs | 446.55 µs | 5720.61 µs | 15189.67 µs | 61.3% | 2.59× | Fuseline 4 |
| 100 vector springs retargeted every frame, drawn | 39.41 µs | 88.00 µs | 90.11 µs | 65.61 µs | 486.90 µs | 1386.26 µs | 55.2% | 2.23× | Fuseline 4 |
| 100 vector springs retargeted every frame, read | 23.28 µs | 66.40 µs | 69.33 µs | 41.21 µs | 470.77 µs | 1223.87 µs | 64.9% | 2.85× | Fuseline 4 |
| 100 vector springs retargeted every frame, unread | 24.24 µs | 73.95 µs | 74.94 µs | 50.10 µs | 498.46 µs | 1341.51 µs | 67.2% | 3.05× | Fuseline 4 |
| 1000 vector springs retargeted every frame, drawn | 489.09 µs | 1100.22 µs | 1133.56 µs | 882.78 µs | 5968.45 µs | 16287.75 µs | 55.5% | 2.25× | Fuseline 4 |
| 1000 vector springs retargeted every frame, read | 257.93 µs | 737.42 µs | 746.41 µs | 502.05 µs | 5756.44 µs | 14975.79 µs | 65.0% | 2.86× | Fuseline 4 |
| 1000 vector springs retargeted every frame, unread | 289.42 µs | 872.12 µs | 882.07 µs | 646.00 µs | 6053.20 µs | 16365.56 µs | 66.8% | 3.01× | Fuseline 4 |
| tween velocity, 1000 readings | 48.38 µs | 47.50 µs | 47.48 µs | 82.55 µs | n/a | 171.43 µs | -1.9% | 0.98× | unresolved (Fuseline 3 ahead, intervals overlap) |
| 1 decays, drawn | 1.45 µs | 1.39 µs | 1.43 µs | n/a | n/a | 1.82 µs | -4.1% | 0.96× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 decays, read | 1.55 µs | 1.24 µs | 1.39 µs | n/a | n/a | 1.36 µs | -25.3% | 0.80× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 decays, unread | 0.93 µs | 1.21 µs | 1.20 µs | n/a | n/a | 1.70 µs | 23.2% | 1.30× | Fuseline 4 |
| 100 decays, drawn | 27.01 µs | 25.23 µs | 26.94 µs | n/a | n/a | 189.59 µs | -7.1% | 0.93× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 100 decays, read | 11.77 µs | 10.38 µs | 11.46 µs | n/a | n/a | 145.77 µs | -13.3% | 0.88× | Fuseline 3.1 |
| 100 decays, unread | 1.02 µs | 13.49 µs | 14.46 µs | n/a | n/a | 178.72 µs | 92.5% | 13.25× | Fuseline 4 |
| 100 values tracking a gesture, drawn | 25.14 µs | 51.79 µs | 52.70 µs | n/a | n/a | 200.14 µs | 51.5% | 2.06× | Fuseline 4 |
| 100 values tracking a gesture, read | 10.23 µs | 38.02 µs | 38.36 µs | n/a | n/a | 185.09 µs | 73.1% | 3.72× | Fuseline 4 |
| 100 values tracking a gesture, unread | 12.89 µs | 42.37 µs | 41.01 µs | n/a | n/a | 192.92 µs | 69.6% | 3.29× | Fuseline 4 |

#### Transitions and timelines: time per frame
| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| tab transitions, drawn | 2.72 µs | 2.75 µs | 2.57 µs | 2.65 µs | 2.75 µs | 7.01 µs | 1.2% | 1.01× | unresolved (Fuseline 3 ahead, intervals overlap) |
| tab transitions, read | 1.89 µs | 1.68 µs | 1.73 µs | 2.09 µs | 2.11 µs | 5.44 µs | -12.1% | 0.89× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| tab transitions, unread | 1.56 µs | 1.81 µs | 1.93 µs | 2.15 µs | 2.09 µs | 6.14 µs | 14.0% | 1.16× | Fuseline 4 |
| tab changes every 3 frames, drawn | 4.07 µs | 4.87 µs | 5.08 µs | 7.14 µs | 21.25 µs | 50.80 µs | 16.5% | 1.20× | Fuseline 4 |
| tab changes every 3 frames, read | 2.60 µs | 3.19 µs | 3.19 µs | 5.41 µs | 20.18 µs | 44.32 µs | 18.5% | 1.23× | Fuseline 4 |
| tab changes every 3 frames, unread | 2.00 µs | 3.62 µs | 3.83 µs | 6.29 µs | 19.43 µs | 47.53 µs | 44.7% | 1.81× | Fuseline 4 |
| tab reversal every 4 frames, drawn | 2.71 µs | 3.04 µs | 3.10 µs | 7.04 µs | 14.58 µs | 35.76 µs | 11.1% | 1.12× | Fuseline 4 |
| tab reversal every 4 frames, read | 1.83 µs | 2.02 µs | 2.10 µs | 5.01 µs | 12.72 µs | 31.37 µs | 9.6% | 1.11× | unresolved (Fuseline 4 ahead, intervals overlap) |
| tab reversal every 4 frames, unread | 1.54 µs | 2.25 µs | 2.45 µs | 5.74 µs | 13.70 µs | 35.09 µs | 31.7% | 1.46× | Fuseline 4 |
| timeline, 20 tracks | 0.49 µs | 0.47 µs | 0.46 µs | 0.51 µs | 0.51 µs | 2.92 µs | -4.0% | 0.96× | unresolved (Fuseline 3 ahead, intervals overlap) |
| timeline seek, 20 tracks | 0.93 µs | 0.96 µs | 0.90 µs | 1.11 µs | 1.40 µs | n/a | 3.2% | 1.03× | unresolved (Fuseline 3 ahead, intervals overlap) |
| timeline reverse, 20 tracks | 1.03 µs | 1.02 µs | 0.94 µs | n/a | n/a | n/a | -1.7% | 0.98× | unresolved (Fuseline 3 ahead, intervals overlap) |

#### Scheduling, start and stop: time per frame
| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| driver churn, 10 | 4.61 µs | 4.60 µs | 4.83 µs | 4.95 µs | 4.80 µs | 13.33 µs | -0.4% | 1.00× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| driver churn, 100 | 34.82 µs | 36.03 µs | 38.17 µs | 40.26 µs | 37.57 µs | 154.33 µs | 3.4% | 1.03× | unresolved (Fuseline 4 ahead, intervals overlap) |
| driver churn, 1000 | 372.02 µs | 387.37 µs | 388.58 µs | 428.01 µs | 399.76 µs | 1547.18 µs | 4.0% | 1.04× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs started and cancelled | 582.92 µs | 659.78 µs | 644.66 µs | 751.03 µs | 760.29 µs | 1863.40 µs | 11.6% | 1.13× | Fuseline 4 |
| 1000 springs created, run and dropped | 81.34 µs | 135.48 µs | 142.06 µs | 177.32 µs | 155.85 µs | 829.08 µs | 40.0% | 1.67× | Fuseline 4 |
| idle, 1000 settled values | 0.08 µs | 0.08 µs | 0.08 µs | 0.08 µs | 0.08 µs | 0.09 µs | -2.1% | 0.98× | unresolved (Fuseline 1 ahead, intervals overlap) |

#### Refresh rates and irregular frames: time per frame
| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| 1000 springs at 30 Hz | 317.17 µs | 341.98 µs | 349.49 µs | 413.49 µs | 434.58 µs | 2579.42 µs | 7.3% | 1.08× | Fuseline 4 |
| 1000 springs at 40 Hz | 346.83 µs | 381.79 µs | 368.52 µs | 398.55 µs | 383.14 µs | 2589.95 µs | 9.2% | 1.10× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 45 Hz | 333.40 µs | 344.75 µs | 380.00 µs | 383.39 µs | 394.71 µs | 2552.87 µs | 3.3% | 1.03× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 50 Hz | 331.13 µs | 348.13 µs | 346.59 µs | 394.76 µs | 384.56 µs | 2580.09 µs | 4.9% | 1.05× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 60 Hz | 327.32 µs | 347.24 µs | 349.17 µs | 387.41 µs | 377.73 µs | 2444.02 µs | 5.7% | 1.06× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 72 Hz | 330.17 µs | 357.35 µs | 339.78 µs | 380.24 µs | 376.36 µs | 2508.20 µs | 7.6% | 1.08× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 75 Hz | 332.10 µs | 346.61 µs | 353.92 µs | 395.63 µs | 378.28 µs | 2427.15 µs | 4.2% | 1.04× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 90 Hz | 325.26 µs | 342.39 µs | 348.37 µs | 381.58 µs | 373.76 µs | 2642.30 µs | 5.0% | 1.05× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 100 Hz | 333.33 µs | 341.87 µs | 366.04 µs | 396.09 µs | 376.44 µs | 2501.12 µs | 2.5% | 1.03× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 120 Hz | 323.12 µs | 342.72 µs | 344.72 µs | 382.52 µs | 371.46 µs | 2507.58 µs | 5.7% | 1.06× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 125 Hz | 318.51 µs | 353.46 µs | 349.30 µs | 385.83 µs | 380.15 µs | 2665.63 µs | 9.9% | 1.11× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 144 Hz | 331.17 µs | 347.47 µs | 358.89 µs | 384.38 µs | 403.95 µs | 2516.59 µs | 4.7% | 1.05× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 165 Hz | 322.26 µs | 349.78 µs | 361.61 µs | 394.19 µs | 385.04 µs | 2573.57 µs | 7.9% | 1.09× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs at 240 Hz | 339.28 µs | 352.68 µs | 354.59 µs | 392.97 µs | 422.82 µs | 2534.25 µs | 3.8% | 1.04× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs, jittered 120 Hz | 342.01 µs | 364.34 µs | 347.42 µs | 396.00 µs | 405.19 µs | 2538.10 µs | 6.1% | 1.07× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs, dropped frames | 355.56 µs | 374.79 µs | 364.01 µs | 429.35 µs | 391.56 µs | 2539.22 µs | 5.1% | 1.05× | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs, a 500 ms stall every 2 s | 348.09 µs | 342.52 µs | 390.45 µs | 405.81 µs | 393.56 µs | 2571.17 µs | -1.6% | 0.98× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1000 springs, 60, 120 and 30 Hz in turn | 334.06 µs | 373.32 µs | 353.99 µs | 419.87 µs | 440.47 µs | 2631.40 µs | 10.5% | 1.12× | Fuseline 4 |

#### Sustained work: time per frame
| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| a busy screen for 10 s | 85.80 µs | 95.02 µs | 98.92 µs | 105.90 µs | 146.57 µs | 714.77 µs | 9.7% | 1.11× | Fuseline 4 |

<details><summary>Allocations per frame, every row</summary>

| Test | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Least |
|---|---|---|---|---|---|---|---|
| 1 tweens, drawn | 705 B | 673 B | 952 B | 1032 B | 1032 B | 1040 B | Fuseline 3.1 |
| 1 tweens, read | 527 B | 495 B | 495 B | 575 B | 575 B | 583 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 tweens, unread | 511 B | 531 B | 728 B | 808 B | 808 B | 816 B | Fuseline 4 |
| 1 springs, drawn | 952 B | 920 B | 952 B | 1031 B | 1031 B | 1039 B | Fuseline 3.1 |
| 1 springs, read | 527 B | 495 B | 495 B | 575 B | 575 B | 583 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 springs, unread | 511 B | 705 B | 728 B | 807 B | 807 B | 815 B | Fuseline 4 |
| 1 vector springs, drawn | 700 B | 677 B | 735 B | 798 B | 798 B | 1047 B | Fuseline 3.1 |
| 1 vector springs, read | 401 B | 378 B | 378 B | 441 B | 441 B | 591 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 vector springs, unread | 385 B | 499 B | 540 B | 603 B | 603 B | 823 B | Fuseline 4 |
| 1 colours, drawn | 820 B | 788 B | 976 B | 745 B | 745 B | 761 B | tie: Fuseline 2, Fuseline 1 |
| 1 colours, read | 539 B | 507 B | 519 B | 583 B | 583 B | 591 B | Fuseline 3.1 |
| 1 colours, unread | 511 B | 603 B | 728 B | 608 B | 608 B | 621 B | Fuseline 4 |
| 100 tweens, drawn | 5860 B | 5828 B | 14978 B | 19026 B | 19026 B | 68897 B | Fuseline 3.1 |
| 100 tweens, read | 2116 B | 2084 B | 2077 B | 6124 B | 6124 B | 52076 B | Fuseline 3 |
| 100 tweens, unread | 517 B | 1066 B | 2082 B | 6130 B | 6130 B | 56001 B | Fuseline 4 |
| 100 springs, drawn | 15018 B | 14986 B | 14978 B | 19026 B | 19026 B | 68897 B | Fuseline 3 |
| 100 springs, read | 2116 B | 2084 B | 2077 B | 6124 B | 6124 B | 52076 B | Fuseline 3 |
| 100 springs, unread | 517 B | 2090 B | 2082 B | 6130 B | 6130 B | 56001 B | Fuseline 4 |
| 100 vector springs, drawn | 14898 B | 14866 B | 14978 B | 19826 B | 19826 B | 69697 B | Fuseline 3.1 |
| 100 vector springs, read | 2116 B | 2084 B | 2077 B | 6924 B | 6924 B | 52876 B | Fuseline 3 |
| 100 vector springs, unread | 517 B | 2077 B | 2082 B | 6930 B | 6930 B | 56801 B | Fuseline 4 |
| 100 colours, drawn | 9026 B | 8994 B | 17378 B | 9494 B | 9494 B | 56731 B | Fuseline 3.1 |
| 100 colours, read | 3320 B | 3288 B | 4477 B | 6924 B | 6924 B | 52876 B | Fuseline 3.1 |
| 100 colours, unread | 517 B | 1286 B | 2082 B | 5775 B | 5775 B | 52885 B | Fuseline 4 |
| 1000 tweens, drawn | 64937 B | 64905 B | 177410 B | 217538 B | 217538 B | 701709 B | Fuseline 3.1 |
| 1000 tweens, read | 30549 B | 30517 B | 30429 B | 70557 B | 70557 B | 534188 B | Fuseline 3 |
| 1000 tweens, unread | 610 B | 8148 B | 21410 B | 61538 B | 61538 B | 545709 B | Fuseline 4 |
| 1000 springs, drawn | 177530 B | 177498 B | 177410 B | 217538 B | 217538 B | 701709 B | Fuseline 3 |
| 1000 springs, read | 30549 B | 30517 B | 30429 B | 70557 B | 70557 B | 534188 B | Fuseline 3 |
| 1000 springs, unread | 610 B | 21498 B | 21410 B | 61538 B | 61538 B | 545709 B | Fuseline 4 |
| 1000 vector springs, drawn | 176057 B | 176025 B | 177410 B | 225538 B | 225538 B | 709709 B | Fuseline 3.1 |
| 1000 vector springs, read | 30549 B | 30517 B | 30429 B | 78557 B | 78557 B | 542188 B | Fuseline 3 |
| 1000 vector springs, unread | 610 B | 21324 B | 21410 B | 69538 B | 69538 B | 553709 B | Fuseline 4 |
| 1000 colours, drawn | 101106 B | 101074 B | 201410 B | 98502 B | 98502 B | 569907 B | tie: Fuseline 2, Fuseline 1 |
| 1000 colours, read | 42589 B | 42557 B | 54429 B | 78557 B | 78557 B | 542188 B | Fuseline 3.1 |
| 1000 colours, unread | 610 B | 11014 B | 21410 B | 54476 B | 54476 B | 524321 B | Fuseline 4 |
| 10000 springs, drawn | 1763611 B | 1763579 B | 1759543 B | 2163619 B | 2163619 B | 7297365 B | Fuseline 3 |
| 10000 springs, read | 322484 B | 322452 B | 318416 B | 722492 B | 722492 B | 5366068 B | Fuseline 3 |
| 10000 springs, unread | 5622 B | 167579 B | 163543 B | 567619 B | 567619 B | 5701365 B | Fuseline 4 |
| 1000 unrelated springs, staggered, drawn | 16373 B | 16394 B | 16917 B | 20381 B | 20309 B | 64729 B | Fuseline 4 |
| 1000 unrelated springs, staggered, read | 31938 B | 31959 B | 31959 B | 35600 B | 35528 B | 76318 B | Fuseline 4 |
| 1000 unrelated springs, staggered, unread | 2187 B | 3462 B | 3464 B | 7104 B | 7032 B | 51455 B | Fuseline 4 |
| 1000 springs in their tails, drawn | 184 B | 184 B | 184 B | 184 B | 184 B | 184 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 springs in their tails, read | 30016 B | 30016 B | 30016 B | 30016 B | 30016 B | 30016 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 springs in their tails, unread | 64 B | 64 B | 64 B | 64 B | 64 B | 64 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 values, mixed motions, drawn | 132675 B | 132643 B | 133488 B | 164181 B | 164181 B | 546047 B | Fuseline 3.1 |
| 1000 values, mixed motions, read | 30673 B | 30641 B | 30553 B | 61246 B | 61246 B | 415205 B | Fuseline 3 |
| 1000 values, mixed motions, unread | 759 B | 14187 B | 14253 B | 44946 B | 44946 B | 426812 B | Fuseline 4 |
| 1000 springs seen again after 5 s unseen | 177480 B | 177448 B | 177448 B | 217488 B | 217488 B | 701552 B | tie: Fuseline 3.1, Fuseline 3 |
| 100 springs retargeted once, drawn | 5781 B | 5776 B | 5960 B | 7556 B | 7878 B | 27555 B | Fuseline 3.1 |
| 100 springs retargeted once, read | 1917 B | 1914 B | 1906 B | 3503 B | 3794 B | 21947 B | Fuseline 3 |
| 100 springs retargeted once, unread | 325 B | 907 B | 921 B | 2517 B | 2811 B | 22497 B | Fuseline 4 |
| 100 springs retargeted every frame, drawn | 15273 B | 16571 B | 16589 B | 40582 B | 194936 B | 578664 B | Fuseline 4 |
| 100 springs retargeted every frame, read | 2475 B | 2455 B | 2448 B | 26493 B | 210875 B | 590647 B | Fuseline 3 |
| 100 springs retargeted every frame, unread | 881 B | 3779 B | 3776 B | 27812 B | 210883 B | 597747 B | Fuseline 4 |
| 1000 springs retargeted every frame, drawn | 179805 B | 200342 B | 200513 B | 440095 B | 2108130 B | 6159197 B | Fuseline 4 |
| 1000 springs retargeted every frame, read | 33084 B | 33134 B | 33046 B | 273147 B | 2117018 B | 5919492 B | Fuseline 3 |
| 1000 springs retargeted every frame, unread | 3205 B | 44601 B | 44513 B | 284615 B | 2108034 B | 6003716 B | Fuseline 4 |
| 100 vector springs retargeted every frame, drawn | 15357 B | 16655 B | 16673 B | 60663 B | 230974 B | 616995 B | Fuseline 4 |
| 100 vector springs retargeted every frame, read | 2477 B | 2457 B | 2449 B | 46490 B | 230870 B | 597042 B | Fuseline 3 |
| 100 vector springs retargeted every frame, unread | 883 B | 3780 B | 3777 B | 47809 B | 230878 B | 604142 B | Fuseline 4 |
| 1000 vector springs retargeted every frame, drawn | 179819 B | 200355 B | 200527 B | 640068 B | 2308077 B | 6223143 B | Fuseline 4 |
| 1000 vector springs retargeted every frame, read | 33097 B | 33147 B | 33059 B | 473121 B | 2316965 B | 5983439 B | Fuseline 3 |
| 1000 vector springs retargeted every frame, unread | 3218 B | 44615 B | 44527 B | 484588 B | 2307981 B | 6067663 B | Fuseline 4 |
| tween velocity, 1000 readings | 119 B | 119 B | 119 B | 119 B | n/a | 119 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Compose |
| 1 decays, drawn | 1013 B | 983 B | 984 B | n/a | n/a | 1067 B | Fuseline 3.1 |
| 1 decays, read | 577 B | 547 B | 547 B | n/a | n/a | 630 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 decays, unread | 562 B | 765 B | 765 B | n/a | n/a | 848 B | Fuseline 4 |
| 100 decays, drawn | 15098 B | 15066 B | 15058 B | n/a | n/a | 76897 B | Fuseline 3 |
| 100 decays, read | 2196 B | 2164 B | 2157 B | n/a | n/a | 60076 B | Fuseline 3 |
| 100 decays, unread | 597 B | 2170 B | 2162 B | n/a | n/a | 64001 B | Fuseline 4 |
| 100 values tracking a gesture, drawn | 14696 B | 14696 B | 14696 B | n/a | n/a | 102479 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3 |
| 100 values tracking a gesture, read | 1781 B | 1781 B | 1781 B | n/a | n/a | 88259 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3 |
| 100 values tracking a gesture, unread | 1787 B | 1787 B | 1787 B | n/a | n/a | 89583 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3 |
| tab transitions, drawn | 1123 B | 1092 B | 1091 B | 1317 B | 1288 B | 2905 B | Fuseline 3 |
| tab transitions, read | 770 B | 732 B | 731 B | 999 B | 970 B | 2483 B | Fuseline 3 |
| tab transitions, unread | 790 B | 896 B | 895 B | 1147 B | 1119 B | 2735 B | Fuseline 4 |
| tab changes every 3 frames, drawn | 1245 B | 1279 B | 1279 B | 2597 B | 8074 B | 23325 B | Fuseline 4 |
| tab changes every 3 frames, read | 668 B | 636 B | 636 B | 1987 B | 7498 B | 22141 B | tie: Fuseline 3.1, Fuseline 3 |
| tab changes every 3 frames, unread | 736 B | 1055 B | 1055 B | 2373 B | 7850 B | 23101 B | Fuseline 4 |
| tab reversal every 4 frames, drawn | 1138 B | 1106 B | 1106 B | 2734 B | 5901 B | 16400 B | tie: Fuseline 3.1, Fuseline 3 |
| tab reversal every 4 frames, read | 652 B | 620 B | 620 B | 2108 B | 5276 B | 15366 B | tie: Fuseline 3.1, Fuseline 3 |
| tab reversal every 4 frames, unread | 698 B | 882 B | 882 B | 2510 B | 5677 B | 16176 B | Fuseline 4 |
| timeline, 20 tracks | 119 B | 119 B | 119 B | 119 B | 157 B | 1053 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2 |
| timeline seek, 20 tracks | 119 B | 119 B | 119 B | 119 B | 599 B | n/a | tie: Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2 |
| timeline reverse, 20 tracks | 119 B | 119 B | 119 B | n/a | n/a | n/a | tie: Fuseline 4, Fuseline 3.1, Fuseline 3 |
| driver churn, 10 | 2537 B | 2520 B | 2504 B | 2751 B | 2671 B | 6799 B | Fuseline 3 |
| driver churn, 100 | 19724 B | 19849 B | 19689 B | 21795 B | 20995 B | 67120 B | Fuseline 3 |
| driver churn, 1000 | 191592 B | 193140 B | 191540 B | 212240 B | 204240 B | 670330 B | Fuseline 3 |
| 1000 springs started and cancelled | 281036 B | 290508 B | 289707 B | 310212 B | 306211 B | 795178 B | Fuseline 4 |
| 1000 springs created, run and dropped | 36171 B | 41489 B | 41222 B | 54968 B | 53635 B | 255952 B | Fuseline 4 |
| idle, 1000 settled values | 181 B | 181 B | 181 B | 181 B | 181 B | 181 B | tie: Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 springs at 30 Hz | 178078 B | 178045 B | 177165 B | 218085 B | 218086 B | 783136 B | Fuseline 3 |
| 1000 springs at 40 Hz | 177945 B | 177912 B | 177252 B | 217953 B | 217953 B | 782737 B | Fuseline 3 |
| 1000 springs at 45 Hz | 177900 B | 177868 B | 177281 B | 217908 B | 217908 B | 782603 B | Fuseline 3 |
| 1000 springs at 50 Hz | 177864 B | 177832 B | 177304 B | 217872 B | 217872 B | 782496 B | Fuseline 3 |
| 1000 springs at 60 Hz | 177811 B | 177779 B | 177339 B | 217819 B | 217819 B | 782336 B | Fuseline 3 |
| 1000 springs at 72 Hz | 177768 B | 177736 B | 177369 B | 217776 B | 217776 B | 782205 B | Fuseline 3 |
| 1000 springs at 75 Hz | 177760 B | 177728 B | 177376 B | 217768 B | 217768 B | 782179 B | Fuseline 3 |
| 1000 springs at 90 Hz | 177727 B | 177695 B | 177401 B | 217735 B | 217735 B | 782074 B | Fuseline 3 |
| 1000 springs at 100 Hz | 177710 B | 177678 B | 177414 B | 217718 B | 217718 B | 782022 B | Fuseline 3 |
| 1000 springs at 120 Hz | 177685 B | 177653 B | 177433 B | 217693 B | 217693 B | 781944 B | Fuseline 3 |
| 1000 springs at 125 Hz | 177680 B | 177648 B | 177437 B | 217688 B | 217688 B | 781928 B | Fuseline 3 |
| 1000 springs at 144 Hz | 177664 B | 177632 B | 177449 B | 217672 B | 217672 B | 781879 B | Fuseline 3 |
| 1000 springs at 165 Hz | 177651 B | 177619 B | 177459 B | 217659 B | 217659 B | 781837 B | Fuseline 3 |
| 1000 springs at 240 Hz | 177623 B | 177591 B | 177481 B | 217631 B | 217631 B | 781748 B | Fuseline 3 |
| 1000 springs, jittered 120 Hz | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 3 |
| 1000 springs, dropped frames | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 3 |
| 1000 springs, a 500 ms stall every 2 s | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 3 |
| 1000 springs, 60, 120 and 30 Hz in turn | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 3 |
| a busy screen for 10 s | 42176 B | 42443 B | 43021 B | 52780 B | 69110 B | 241231 B | Fuseline 4 |

</details>

Whole interface paths (per frame, composition, layout and drawing included; memory is what every
thread allocated):

| Interface path | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 3.1 | Speedup vs 3.1 | Winner |
|---|---|---|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 604 µs | 572 µs | 587 µs | n/a | n/a | 1965 µs | -5.5% | 0.95× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| shared element, moving destination | 573 µs | 583 µs | 468 µs | n/a | n/a | 769 µs | 1.7% | 1.02× | unresolved (Fuseline 3 ahead, intervals overlap) |
| rapid tab switching | 2791 µs | 2611 µs | 2748 µs | 3027 µs | 2938 µs | 3122 µs | -6.9% | 0.94× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| a page of 120 tiles, 3 values each, composed | 2240 µs | 2237 µs | 3482 µs | n/a | n/a | 3952 µs | -0.1% | 1.00× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| selection moving across 120 tiles | 1718 µs | 1672 µs | 1844 µs | n/a | n/a | 1912 µs | -2.7% | 0.97× | unresolved (Fuseline 3.1 ahead, intervals overlap) |

| Interface path, memory per frame | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose |
|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 20.9 KB | 20.9 KB | 21.0 KB | n/a | n/a | 188.0 KB |
| shared element, moving destination | 11.0 KB | 9.8 KB | 9.8 KB | n/a | n/a | 13.6 KB |
| rapid tab switching | 35.3 KB | 35.3 KB | 35.4 KB | 48.6 KB | 48.5 KB | 125.2 KB |
| a page of 120 tiles, 3 values each, composed | 218.1 KB | 214.8 KB | 680.2 KB | n/a | n/a | 713.4 KB |
| selection moving across 120 tiles | 4.9 KB | 4.8 KB | 6.4 KB | n/a | n/a | 20.5 KB |

Per frame, composition, layout and drawing included (Compose's test clock), after a settled start; memory is what every thread allocated; 3 warm-up runs, then 9 rounds in rotating order, median; the winner's whole 95% bootstrap interval must lie below every other engine's, otherwise the row is unresolved.

A room needing 30 updates a second on a 120 Hz screen, redrawn per second:

| Room | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Compose |
|---|---|---|---|---|
| while buttons are pressed | 3 | 3 | 44 | 130 |

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
