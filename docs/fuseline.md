# Fuseline by Fuse

Fuseline by Fuse is Fuse's animation engine. It is named after the line of a fuse, the wire in Fuse's
logo that carries the spark. Every animation in Fuse runs on it, from a focus lift to the setup's
opening sequence.

It lives in `ui/fuseline` (package `io.github.matiyaaa.fuse.ui.fuseline`). Fuse 0.3.7 brought
**Fuseline 3**, Fuse 0.3.7.5 **Fuseline 3.1** ([what 3.1 adds](#fuseline-31)), Fuse 0.4.0
**Fuseline 4** ([what 4 changes](#fuseline-4)), and Fuse 0.4.4.2 brings **Fuseline 4.3** ([what
4.3 takes away](#fuseline-43)). All are built around two rules:

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

## Fuseline 4.1

Fuseline 4.1 keeps every motion of Fuseline 4, to the frame, and changes how a moving value tells
Compose it changed. Fuseline 4 gave every value a version of its own and bumped it whenever the
value moved far enough to be seen: a thousand springs in flight meant a thousand snapshot writes a
frame, and a thousand snapshot observers for Compose to look up.

- **The pulse.** A value that moves visibly frame after frame (two visible changes in a row) stops
  writing its own version and joins its driver's pulse instead: one integer state per driver, bumped
  once a frame however many values ride it (`FrameDriver.pulse`). Its readers read the pulse in
  place of the value's own version, so they still run again exactly on the frames it moved, and
  Compose has one write to apply instead of one per value.
- **Leaving it.** A value leaves the pulse after 4 calm frames in a row (`CALM_FRAMES`, so a slow
  value doesn't thrash in and out), on arriving, when it is frozen, and on any change made from
  outside its motion (a retarget, a seek, a gesture taking it), which writes its own version again
  so a reader is never a frame late.
- **Exactly the same motion.** Fuseline 4 is kept in the tests exactly as it shipped (package `v4`).
  `Fuseline4EquivalenceTest` now runs Fuseline 4.1 against 3.1 as before, and counts each value's
  pulsed runs: a value that has ridden the pulse gets the same room as one woken from resting
  unread, a reader showing a place up to two eighths of a threshold from what 3.1 would show (for
  a position, an eighth of a pixel), never more; positions, velocities, targets and finishes agree
  as they did.

## Fuseline 4.3

Fuseline 4.3 keeps every motion of Fuseline 4.2, to the frame, and takes away work nobody sees:
writes that tell no reader anything, frames that only find a value still unread, and arithmetic
that many values share.

- **Retargeted while nobody reads it, it stays at rest.** A value resting unread (Fuseline 4's
  parking) that is given a new target used to be brought into the next frame, out of the frame
  driver's heap and back in, only for that frame to find it still unread. Now it stays at rest,
  and only has to come up no later than its new motion could end (`FrameDriver.Move.retime`). A
  list of values following the selection while it is scrolled away costs its retargets and
  nothing more.
- **No write for nobody.** A value nobody has ever read, or one whose readers were told by a
  write that is always applied (made outside any snapshot of its own) and haven't read it since,
  isn't written again: dragged, snapped, landing or moved again while nobody draws it, only its
  reading moves on, and its next reader catches up with it as a parked value's does. A new value
  starts that way, so one moved before anything draws it rests until its first read.
- **Who moves it, without a write.** `owner` is kept in a plain field that Fuseline's own checks
  read (every retarget and every drag event asks), and its readers watch a state that is only
  written once something has read it: a value nobody asks who moves it (most of them) makes no
  write when a move starts or ends. A new value's target is the same: nothing is written for it
  until something reads it.
- **Shared answers.** A moving colour's 8-bit channels come from a table of where each code
  begins instead of sRGB's transfer curve (three powers for every colour read), the very same
  colour to the bit (`SrgbCodes`, checked at every code and over a million values). A cubic curve
  keeps its whole answer for a moment (the share of time, the height and the slope), so the
  tweens on it at that moment do one division and one solve between them. Decays on the same
  friction at the same moment share one exponential. A spring kernel's shared solution is kept by
  play time, so a value reading another's skips a division, and the earliest moment a spring
  could be at rest is bounded without a logarithm (`lnBelow`: always early, by at most 0.06 of
  the logarithm).
- **A cancelled move doesn't solve its end.** A move cancelled from outside asks whether it had
  finished; short of its cheap lower bound it can't have, so the Newton solve for its exact end
  is never made for it.
- **Exactly the same motion.** Fuseline 4.2 is kept in the tests exactly as it shipped (package
  `v42`). `Fuseline4EquivalenceTest` runs 4.3 against 3.1 as before (20,000 random histories pass):
  a value that rested unread or had a write left out gets the room a woken value always had (a
  reader within a quarter of a threshold of what 3.1 would show), and positions, velocities,
  targets, owners, progress and finishes agree as they did.

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
memory. Fuseline 4.2, 4, 3.1, 3, 2 and 1 are kept in the tests exactly as they shipped (packages
`...fuseline.v42`, `...fuseline.v4`, `...fuseline.v31`, `...fuseline.v3`, `...fuseline.v2` and
`...fuseline.v1`). `MotionBenchmark` (values, scale, retargeting, gestures, decay, transitions,
timelines, scheduling, refresh rates and irregular frames, sustained work) measures all eight
engines on the same work, and `UiMotionBenchmark` (whole interface paths, composition, layout and
drawing included) every one that has the parts a path needs.

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

### Fuseline 4.1 against Fuseline 4

Measured the same way (below), 11 rounds, every row run on both, with each value's share of the
frame in nanoseconds where a row moves many values. Of 111 rows, Fuseline 4.1 is clearly first on
45, 65 are unresolved (within this machine's run-to-run noise) and Fuseline 4 is clearly first on 1.

| Where it shows | Fuseline 4.1 | Fuseline 4 | Speedup | Per value |
|---|---|---|---|---|
| 10000 springs, drawn | 2276 µs | 5926 µs | 2.60× | 228 ns |
| 10000 springs, read | 950 µs | 1275 µs | 1.34× | 95 ns |
| 1000 springs, drawn | 160 µs | 336 µs | 2.10× | 160 ns |
| 1000 vector springs, drawn | 229 µs | 446 µs | 1.95× | 229 ns |
| 1000 values, mixed motions, drawn | 125 µs | 251 µs | 2.01× | 125 ns |
| 1000 springs seen again after 5 s unseen | 143 µs | 316 µs | 2.20× | 143 ns |
| 1000 springs retargeted every frame, drawn | 220 µs | 417 µs | 1.90× | 220 ns |
| 1000 springs, 30 to 240 Hz and irregular frames | 137 to 177 µs | 300 to 344 µs | 1.80 to 2.36× | 137 to 177 ns |
| a busy screen for 10 s | 50 µs | 78 µs | 1.55× | |
| 1000 springs, unread | 1.5 µs | 1.5 µs | level | 1.5 ns |

Plainly:

- **The thousands.** Every Fuseline 4 row in the thousands of microseconds was 10000 springs moving
  at once; drawn, it falls from 5.9 ms to 2.3 ms a frame. It stays above a millisecond because
  every one of the ten thousand values visibly moves every frame and each still has to be solved
  and read (about 228 ns each, most of it Compose reading the value back). Nothing in Fuse moves
  that many at once; a page of tiles moves hundreds.
- **The one row Fuseline 4 wins.** 100 vector springs given a new target every frame and never
  read: 23.3 µs against 20.6 µs. A retarget from outside the motion leaves the pulse and writes the
  value's own version, so a value retargeted every frame pays for both; read or drawn, the same
  work is level or quicker on 4.1.
- **Memory** is the same to the byte on almost every row; starting and stopping many values costs up
  to 1% more (the pulse's bookkeeping).
- **Whole interface paths** (`UiMotionBenchmark`: 120 tiles composed about 2.2 ms, rapid tab
  switching about 2.8 ms) were not re-run for 4.1. Most of those frames are Compose composing and
  laying out, which no motion engine changes; their numbers below are Fuseline 4's.

<details>
<summary>Every row, Fuseline 4.1 against Fuseline 4</summary>

#### Values in motion: time per frame

| Test | Fuseline 4.1 | Fuseline 4 | Reduction vs 4 | Speedup vs 4 | 4.1 per value | Winner |
|---|---|---|---|---|---|---|
| 1 tweens, drawn | 1.24 µs | 1.18 µs | -5.2% | 0.95× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 tweens, read | 1.34 µs | 1.25 µs | -7.5% | 0.93× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 tweens, unread | 0.94 µs | 0.92 µs | -3.0% | 0.97× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 springs, drawn | 1.61 µs | 1.63 µs | 1.0% | 1.01× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1 springs, read | 1.29 µs | 1.31 µs | 1.9% | 1.02× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1 springs, unread | 1.87 µs | 1.91 µs | 1.7% | 1.02× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1 vector springs, drawn | 1.33 µs | 1.15 µs | -16.1% | 0.86× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 vector springs, read | 1.42 µs | 1.33 µs | -6.5% | 0.94× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 vector springs, unread | 0.69 µs | 0.70 µs | 1.6% | 1.02× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1 colours, drawn | 1.34 µs | 1.40 µs | 3.9% | 1.04× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1 colours, read | 1.25 µs | 1.25 µs | -0.4% | 1.00× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 colours, unread | 1.67 µs | 1.80 µs | 7.1% | 1.08× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 tweens, drawn | 16.08 µs | 14.20 µs | -13.2% | 0.88× | 160.8 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 tweens, read | 8.96 µs | 8.92 µs | -0.5% | 1.00× | 89.6 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 tweens, unread | 1.64 µs | 1.61 µs | -1.5% | 0.98× | 16.4 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 springs, drawn | 15.54 µs | 26.50 µs | 41.4% | 1.71× | 155.4 ns | Fuseline 4.1 |
| 100 springs, read | 7.36 µs | 9.37 µs | 21.5% | 1.27× | 73.6 ns | Fuseline 4.1 |
| 100 springs, unread | 1.25 µs | 1.18 µs | -6.2% | 0.94× | 12.5 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 vector springs, drawn | 34.34 µs | 52.21 µs | 34.2% | 1.52× | 343.4 ns | Fuseline 4.1 |
| 100 vector springs, read | 19.68 µs | 22.49 µs | 12.5% | 1.14× | 196.8 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 vector springs, unread | 1.23 µs | 1.16 µs | -6.3% | 0.94× | 12.3 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 colours, drawn | 22.45 µs | 24.47 µs | 8.3% | 1.09× | 224.5 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 colours, read | 15.11 µs | 16.66 µs | 9.3% | 1.10× | 151.1 ns | Fuseline 4.1 |
| 100 colours, unread | 1.28 µs | 1.28 µs | 0.0% | 1.00× | 12.8 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 tweens, drawn | 179.68 µs | 171.95 µs | -4.5% | 0.96× | 179.7 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 tweens, read | 81.45 µs | 82.57 µs | 1.4% | 1.01× | 81.4 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 tweens, unread | 6.29 µs | 5.99 µs | -5.0% | 0.95× | 6.3 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs, drawn | 160.17 µs | 335.80 µs | 52.3% | 2.10× | 160.2 ns | Fuseline 4.1 |
| 1000 springs, read | 67.25 µs | 94.38 µs | 28.7% | 1.40× | 67.3 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 springs, unread | 1.53 µs | 1.49 µs | -2.3% | 0.98× | 1.5 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 vector springs, drawn | 228.97 µs | 445.78 µs | 48.6% | 1.95× | 229.0 ns | Fuseline 4.1 |
| 1000 vector springs, read | 126.20 µs | 190.94 µs | 33.9% | 1.51× | 126.2 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 vector springs, unread | 2.96 µs | 3.02 µs | 2.1% | 1.02× | 3.0 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 colours, drawn | 295.93 µs | 308.57 µs | 4.1% | 1.04× | 295.9 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 colours, read | 157.73 µs | 170.70 µs | 7.6% | 1.08× | 157.7 ns | Fuseline 4.1 |
| 1000 colours, unread | 1.88 µs | 1.87 µs | -0.7% | 0.99× | 1.9 ns | unresolved (Fuseline 4 ahead, intervals overlap) |

#### Scale and kinds of motion: time per frame

| Test | Fuseline 4.1 | Fuseline 4 | Reduction vs 4 | Speedup vs 4 | 4.1 per value | Winner |
|---|---|---|---|---|---|---|
| 10000 springs, drawn | 2276.13 µs | 5925.76 µs | 61.6% | 2.60× | 227.6 ns | Fuseline 4.1 |
| 10000 springs, read | 950.26 µs | 1275.20 µs | 25.5% | 1.34× | 95.0 ns | Fuseline 4.1 |
| 10000 springs, unread | 34.51 µs | 33.59 µs | -2.7% | 0.97× | 3.5 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 unrelated springs, staggered, drawn | 24.38 µs | 35.71 µs | 31.7% | 1.46× | 24.4 ns | Fuseline 4.1 |
| 1000 unrelated springs, staggered, read | 43.69 µs | 40.86 µs | -6.9% | 0.94× | 43.7 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 unrelated springs, staggered, unread | 6.45 µs | 6.59 µs | 2.1% | 1.02× | 6.5 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 springs in their tails, drawn | 0.15 µs | 0.10 µs | -48.7% | 0.67× | 0.1 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs in their tails, read | 20.61 µs | 23.13 µs | 10.9% | 1.12× | 20.6 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 springs in their tails, unread | 0.39 µs | 0.39 µs | -1.2% | 0.99× | 0.4 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 values, mixed motions, drawn | 125.28 µs | 251.28 µs | 50.1% | 2.01× | 125.3 ns | Fuseline 4.1 |
| 1000 values, mixed motions, read | 61.42 µs | 89.94 µs | 31.7% | 1.46× | 61.4 ns | Fuseline 4.1 |
| 1000 values, mixed motions, unread | 2.73 µs | 4.53 µs | 39.8% | 1.66× | 2.7 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 springs seen again after 5 s unseen | 143.32 µs | 315.86 µs | 54.6% | 2.20× | 143.3 ns | Fuseline 4.1 |

#### Retargeting, gestures, decay: time per frame

| Test | Fuseline 4.1 | Fuseline 4 | Reduction vs 4 | Speedup vs 4 | 4.1 per value | Winner |
|---|---|---|---|---|---|---|
| 100 springs retargeted once, drawn | 6.84 µs | 11.13 µs | 38.5% | 1.63× | 68.4 ns | Fuseline 4.1 |
| 100 springs retargeted once, read | 5.98 µs | 7.23 µs | 17.3% | 1.21× | 59.8 ns | Fuseline 4.1 |
| 100 springs retargeted once, unread | 0.81 µs | 0.88 µs | 7.5% | 1.08× | 8.1 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 springs retargeted every frame, drawn | 21.94 µs | 33.70 µs | 34.9% | 1.54× | 219.4 ns | Fuseline 4.1 |
| 100 springs retargeted every frame, read | 14.87 µs | 18.22 µs | 18.4% | 1.23× | 148.7 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 springs retargeted every frame, unread | 17.52 µs | 17.27 µs | -1.5% | 0.99× | 175.2 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 springs retargeted every frame, drawn | 220.21 µs | 417.42 µs | 47.2% | 1.90× | 220.2 ns | Fuseline 4.1 |
| 1000 springs retargeted every frame, read | 131.88 µs | 175.03 µs | 24.6% | 1.33× | 131.9 ns | Fuseline 4.1 |
| 1000 springs retargeted every frame, unread | 206.17 µs | 220.58 µs | 6.5% | 1.07× | 206.2 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 vector springs retargeted every frame, drawn | 31.12 µs | 43.06 µs | 27.7% | 1.38× | 311.2 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 vector springs retargeted every frame, read | 20.50 µs | 22.80 µs | 10.1% | 1.11× | 205.0 ns | Fuseline 4.1 |
| 100 vector springs retargeted every frame, unread | 23.26 µs | 20.61 µs | -12.9% | 0.89× | 232.6 ns | Fuseline 4 |
| 1000 vector springs retargeted every frame, drawn | 332.52 µs | 478.00 µs | 30.4% | 1.44× | 332.5 ns | Fuseline 4.1 |
| 1000 vector springs retargeted every frame, read | 224.73 µs | 246.88 µs | 9.0% | 1.10× | 224.7 ns | Fuseline 4.1 |
| 1000 vector springs retargeted every frame, unread | 365.75 µs | 283.71 µs | -28.9% | 0.78× | 365.8 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| tween velocity, 1000 readings | 46.66 µs | 46.12 µs | -1.2% | 0.99× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 decays, drawn | 1.44 µs | 1.49 µs | 3.8% | 1.04× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1 decays, read | 1.12 µs | 1.12 µs | -0.3% | 1.00× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 decays, unread | 0.98 µs | 1.02 µs | 4.5% | 1.05× |  | Fuseline 4.1 |
| 100 decays, drawn | 15.44 µs | 27.04 µs | 42.9% | 1.75× | 154.4 ns | Fuseline 4.1 |
| 100 decays, read | 7.25 µs | 11.04 µs | 34.4% | 1.52× | 72.5 ns | Fuseline 4.1 |
| 100 decays, unread | 1.08 µs | 1.10 µs | 1.5% | 1.02× | 10.8 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 values tracking a gesture, drawn | 24.77 µs | 25.02 µs | 1.0% | 1.01× | 247.7 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 100 values tracking a gesture, read | 9.33 µs | 9.24 µs | -0.9% | 0.99× | 93.3 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 100 values tracking a gesture, unread | 12.86 µs | 12.98 µs | 1.0% | 1.01× | 128.6 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |

#### Transitions and timelines: time per frame

| Test | Fuseline 4.1 | Fuseline 4 | Reduction vs 4 | Speedup vs 4 | 4.1 per value | Winner |
|---|---|---|---|---|---|---|
| tab transitions, drawn | 2.64 µs | 2.90 µs | 9.0% | 1.10× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| tab transitions, read | 1.97 µs | 1.97 µs | -0.4% | 1.00× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| tab transitions, unread | 1.54 µs | 1.53 µs | -0.2% | 1.00× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| tab changes every 3 frames, drawn | 2.89 µs | 3.90 µs | 25.9% | 1.35× |  | Fuseline 4.1 |
| tab changes every 3 frames, read | 2.38 µs | 2.58 µs | 7.8% | 1.08× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| tab changes every 3 frames, unread | 1.96 µs | 1.97 µs | 0.4% | 1.00× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| tab reversal every 4 frames, drawn | 2.32 µs | 2.75 µs | 15.7% | 1.19× |  | Fuseline 4.1 |
| tab reversal every 4 frames, read | 1.81 µs | 1.99 µs | 8.8% | 1.10× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| tab reversal every 4 frames, unread | 1.50 µs | 1.56 µs | 3.8% | 1.04× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| timeline, 20 tracks | 0.51 µs | 0.57 µs | 11.0% | 1.12× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| timeline seek, 20 tracks | 0.85 µs | 0.83 µs | -2.6% | 0.97× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| timeline reverse, 20 tracks | 0.85 µs | 0.82 µs | -3.3% | 0.97× |  | unresolved (Fuseline 4 ahead, intervals overlap) |

#### Scheduling, start and stop: time per frame

| Test | Fuseline 4.1 | Fuseline 4 | Reduction vs 4 | Speedup vs 4 | 4.1 per value | Winner |
|---|---|---|---|---|---|---|
| driver churn, 10 | 4.59 µs | 4.65 µs | 1.3% | 1.01× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| driver churn, 100 | 34.67 µs | 34.61 µs | -0.2% | 1.00× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| driver churn, 1000 | 357.81 µs | 373.91 µs | 4.3% | 1.04× |  | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 springs started and cancelled | 573.48 µs | 597.93 µs | 4.1% | 1.04× | 573.5 ns | unresolved (Fuseline 4.1 ahead, intervals overlap) |
| 1000 springs created, run and dropped | 80.36 µs | 80.17 µs | -0.2% | 1.00× | 80.4 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| idle, 1000 settled values | 0.07 µs | 0.07 µs | -0.9% | 0.99× |  | unresolved (Fuseline 4 ahead, intervals overlap) |

#### Refresh rates and irregular frames: time per frame

| Test | Fuseline 4.1 | Fuseline 4 | Reduction vs 4 | Speedup vs 4 | 4.1 per value | Winner |
|---|---|---|---|---|---|---|
| 1000 springs at 30 Hz | 163.07 µs | 300.54 µs | 45.7% | 1.84× | 163.1 ns | Fuseline 4.1 |
| 1000 springs at 40 Hz | 160.81 µs | 340.90 µs | 52.8% | 2.12× | 160.8 ns | Fuseline 4.1 |
| 1000 springs at 45 Hz | 145.66 µs | 343.83 µs | 57.6% | 2.36× | 145.7 ns | Fuseline 4.1 |
| 1000 springs at 50 Hz | 150.39 µs | 301.62 µs | 50.1% | 2.01× | 150.4 ns | Fuseline 4.1 |
| 1000 springs at 60 Hz | 145.88 µs | 304.94 µs | 52.2% | 2.09× | 145.9 ns | Fuseline 4.1 |
| 1000 springs at 72 Hz | 153.62 µs | 305.09 µs | 49.6% | 1.99× | 153.6 ns | Fuseline 4.1 |
| 1000 springs at 75 Hz | 142.98 µs | 312.27 µs | 54.2% | 2.18× | 143.0 ns | Fuseline 4.1 |
| 1000 springs at 90 Hz | 177.08 µs | 318.10 µs | 44.3% | 1.80× | 177.1 ns | Fuseline 4.1 |
| 1000 springs at 100 Hz | 143.01 µs | 302.34 µs | 52.7% | 2.11× | 143.0 ns | Fuseline 4.1 |
| 1000 springs at 120 Hz | 144.80 µs | 303.64 µs | 52.3% | 2.10× | 144.8 ns | Fuseline 4.1 |
| 1000 springs at 125 Hz | 137.18 µs | 304.29 µs | 54.9% | 2.22× | 137.2 ns | Fuseline 4.1 |
| 1000 springs at 144 Hz | 149.69 µs | 317.05 µs | 52.8% | 2.12× | 149.7 ns | Fuseline 4.1 |
| 1000 springs at 165 Hz | 149.72 µs | 326.55 µs | 54.2% | 2.18× | 149.7 ns | Fuseline 4.1 |
| 1000 springs at 240 Hz | 140.17 µs | 321.12 µs | 56.3% | 2.29× | 140.2 ns | Fuseline 4.1 |
| 1000 springs, jittered 120 Hz | 147.06 µs | 315.56 µs | 53.4% | 2.15× | 147.1 ns | Fuseline 4.1 |
| 1000 springs, dropped frames | 150.92 µs | 327.56 µs | 53.9% | 2.17× | 150.9 ns | Fuseline 4.1 |
| 1000 springs, a 500 ms stall every 2 s | 141.59 µs | 322.96 µs | 56.2% | 2.28× | 141.6 ns | Fuseline 4.1 |
| 1000 springs, 60, 120 and 30 Hz in turn | 150.76 µs | 313.08 µs | 51.8% | 2.08× | 150.8 ns | Fuseline 4.1 |

#### Sustained work: time per frame

| Test | Fuseline 4.1 | Fuseline 4 | Reduction vs 4 | Speedup vs 4 | 4.1 per value | Winner |
|---|---|---|---|---|---|---|
| a busy screen for 10 s | 50.46 µs | 78.04 µs | 35.3% | 1.55× |  | Fuseline 4.1 |

</details>

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
- Measured on a computer only: OpenJDK 64-Bit Server VM 21.0.12.1, 4 processors, Linux amd64.
  Fuseline 4.3 has not yet been measured on an Android device or on Windows or macOS.

### Results

Fuseline 4.3 against Fuseline 4.2, 4, 3.1, 3, 2 and 1 and Compose, 15 rounds each. Of 111 rows,
Fuseline 4.3 is clearly first on 19; 89 are unresolved (the engines' intervals overlap: within
this machine's run-to-run noise, whoever is ahead); and another engine came out first on 3 (1
colours, read: Fuseline 3.1; 100 vector springs, drawn: Fuseline 4.2; 1000 springs at 90 Hz:
Fuseline 4.2). Those three were measured again over 31 rounds, with 10000 springs, read (the one
unresolved row where 4.2 was ahead by more than a tenth), and all four are level:

| Measured again, 31 rounds | Fuseline 4.3 | Fuseline 4.2 | Fuseline 3.1 | Winner |
|---|---|---|---|---|
| 1 colours, read | 1.20 µs | 1.15 µs | 1.16 µs | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 100 vector springs, drawn | 17.00 µs | 18.03 µs | 37.79 µs | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 90 Hz | 172.57 µs | 194.60 µs | 362.76 µs | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 10000 springs, read | 1070.64 µs | 1162.23 µs | 1689.36 µs | unresolved (Fuseline 4.3 ahead, intervals overlap) |

Where Fuseline 4.3 is first, it is first by a long way where nobody is looking or a value is
given a new target every frame, the work Fuseline 4.3 takes away:

| Clearly first | Fuseline 4.3 | Fuseline 4.2 | Next best | Compose |
|---|---|---|---|---|
| 10000 springs, unread | 1.16 µs | 43.08 µs | 40.67 µs (Fuseline 4), 35× | 36055.54 µs |
| 100 values tracking a gesture, unread | 2.38 µs | 19.52 µs | 15.32 µs (Fuseline 4), 6.4× | 209.96 µs |
| 1000 tweens, unread | 0.95 µs | 6.07 µs | 5.70 µs (Fuseline 4), 6.0× | 2131.60 µs |
| 1000 springs retargeted every frame, unread | 74.41 µs | 213.82 µs | 213.51 µs (Fuseline 4), 2.9× | 15559.23 µs |
| 1000 vector springs retargeted every frame, unread | 137.64 µs | 316.74 µs | 315.96 µs (Fuseline 4), 2.3× | 17540.62 µs |
| 100 springs retargeted every frame, unread | 7.89 µs | 17.94 µs | 17.74 µs (Fuseline 4), 2.2× | 1178.76 µs |
| 1000 values, mixed motions, unread | 1.93 µs | 2.77 µs | 2.72 µs (Fuseline 4), 1.4× | 1639.13 µs |
| 1000 springs in their tails, read | 16.73 µs | 21.48 µs | 19.48 µs (Compose), 1.2× | 19.48 µs |
| 100 springs retargeted every frame, read | 12.08 µs | 13.88 µs | 13.88 µs (Fuseline 4.2), 1.15× | 1213.71 µs |
| 100 vector springs retargeted every frame, drawn | 25.07 µs | 28.24 µs | 28.24 µs (Fuseline 4.2), 1.13× | 1352.11 µs |
| 100 colours, drawn | 19.99 µs | 25.66 µs | 22.66 µs (Fuseline 4), 1.13× | 194.00 µs |

Plainly:

- **Where it is unresolved, the frame is Compose's.** A value that visibly moves every frame and
  is drawn has its reader run again, and that (Compose's snapshot read, its observer, the reader's
  bookkeeping) is about three quarters of the frame, the same for every engine; a single value's
  frame is mostly the frame clock waking the frame loop. Fuseline 4.2 already did nothing more
  there than solve the value and tell its readers, so 4.3 can only be level, or a little ahead
  where it no longer writes for nobody: 1000 springs retargeted every frame, read (115.73 against
  140.07 µs), 1000 vector springs retargeted every frame, read (195.60 against 268.52 µs) and 1000
  colours, read (169.64 against 245.40 µs) are ahead of every engine without clearing this
  machine's noise.
- **Against Compose**, Fuseline 4.3 is 1.2 to 2.4 times quicker on a single value and 8 to 216
  times on most rows of many values (springs in their long, still tails, and values started and
  cancelled together, are level to 5 times), and up to 31,000 times where nobody draws them.
- **Memory** is the same as Fuseline 4.2's to within a few bytes a frame on almost every row, and
  less where a value no longer writes for nobody.
- **Whole interface paths** are all unresolved: most of those frames are Compose composing, laying
  out and drawing, which no motion engine changes. Fuseline 4.3 is ahead on the selection moving
  across 120 tiles (1842 against 2000 µs) and within the noise elsewhere.
- **The decorative room** redraws 65 times a second while buttons are pressed on Fuseline 4.3 and
  4.2, against 3 on Fuseline 3.1: since Fuse 0.4.2.7 decoration keeps moving under input on a
  healthy display, where 3.1 held it still, and Compose's test clock skips the waits a paced room
  makes, so this harness counts more than a display would. `DecorationTest` measures the pacing on
  an exact virtual clock: a 30 a second room wakes about 30 times a second.

#### Values in motion: time per frame

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | 4.3 per value | Winner |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 tweens, drawn | 1.18 µs | 1.16 µs | 1.17 µs | 1.10 µs | 1.35 µs | 1.47 µs | 1.39 µs | 2.02 µs | -2.3% | 0.98× |  | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 tweens, read | 1.08 µs | 1.08 µs | 1.02 µs | 0.98 µs | 1.00 µs | 1.04 µs | 1.07 µs | 1.48 µs | 0.1% | 1.00× |  | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 tweens, unread | 0.80 µs | 0.87 µs | 0.81 µs | 0.90 µs | 1.15 µs | 1.13 µs | 1.22 µs | 1.75 µs | 7.8% | 1.09× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1 springs, drawn | 1.50 µs | 1.47 µs | 1.53 µs | 1.47 µs | 1.47 µs | 1.54 µs | 1.46 µs | 2.07 µs | -2.0% | 0.98× |  | unresolved (Fuseline 1 ahead, intervals overlap) |
| 1 springs, read | 1.17 µs | 1.21 µs | 1.16 µs | 1.13 µs | 1.16 µs | 1.17 µs | 1.20 µs | 1.56 µs | 3.7% | 1.04× |  | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 springs, unread | 1.48 µs | 1.14 µs | 1.03 µs | 1.39 µs | 1.42 µs | 1.37 µs | 2.08 µs | 3.13 µs | -29.7% | 0.77× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1 vector springs, drawn | 1.96 µs | 1.34 µs | 1.50 µs | 1.28 µs | 1.24 µs | 1.24 µs | 1.66 µs | 3.63 µs | -45.9% | 0.69× |  | unresolved (Fuseline 3 ahead, intervals overlap) |
| 1 vector springs, read | 0.94 µs | 0.95 µs | 0.95 µs | 1.28 µs | 1.28 µs | 1.18 µs | 0.99 µs | 2.07 µs | 0.5% | 1.00× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1 vector springs, unread | 0.89 µs | 1.31 µs | 1.13 µs | 1.29 µs | 1.12 µs | 1.02 µs | 1.57 µs | 2.18 µs | 32.2% | 1.48× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1 colours, drawn | 1.43 µs | 1.49 µs | 1.44 µs | 1.44 µs | 1.66 µs | 1.39 µs | 1.39 µs | 2.30 µs | 4.4% | 1.05× |  | unresolved (Fuseline 2 ahead, intervals overlap) |
| 1 colours, read | 1.19 µs | 1.26 µs | 1.19 µs | 1.15 µs | 1.21 µs | 1.24 µs | 1.22 µs | 1.78 µs | 5.0% | 1.05× |  | Fuseline 3.1 |
| 1 colours, unread | 0.96 µs | 0.99 µs | 0.97 µs | 1.17 µs | 1.34 µs | 1.25 µs | 1.43 µs | 2.16 µs | 3.3% | 1.03× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 tweens, drawn | 14.65 µs | 14.03 µs | 14.19 µs | 14.24 µs | 30.07 µs | 33.67 µs | 29.89 µs | 210.01 µs | -4.4% | 0.96× | 146.5 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 100 tweens, read | 9.34 µs | 9.88 µs | 9.31 µs | 9.28 µs | 13.19 µs | 15.43 µs | 14.65 µs | 163.23 µs | 5.5% | 1.06× | 93.4 ns | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 100 tweens, unread | 1.09 µs | 1.49 µs | 1.43 µs | 10.00 µs | 17.20 µs | 19.51 µs | 17.85 µs | 192.34 µs | 27.0% | 1.37× | 10.9 ns | Fuseline 4.3 |
| 100 springs, drawn | 16.16 µs | 15.53 µs | 27.45 µs | 29.81 µs | 29.53 µs | 33.95 µs | 35.66 µs | 210.42 µs | -4.1% | 0.96× | 161.6 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 100 springs, read | 7.76 µs | 7.48 µs | 10.81 µs | 13.57 µs | 14.31 µs | 16.54 µs | 16.40 µs | 163.34 µs | -3.7% | 0.96× | 77.6 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 100 springs, unread | 0.92 µs | 1.04 µs | 0.95 µs | 16.19 µs | 16.66 µs | 20.72 µs | 20.24 µs | 177.68 µs | 11.5% | 1.13× | 9.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 vector springs, drawn | 19.03 µs | 18.10 µs | 29.75 µs | 34.52 µs | 35.04 µs | 39.43 µs | 40.77 µs | 218.44 µs | -5.1% | 0.95× | 190.3 ns | Fuseline 4.2 |
| 100 vector springs, read | 10.67 µs | 10.41 µs | 13.31 µs | 18.56 µs | 18.97 µs | 23.62 µs | 23.64 µs | 186.63 µs | -2.5% | 0.98× | 106.7 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 100 vector springs, unread | 0.96 µs | 1.08 µs | 0.99 µs | 20.25 µs | 21.05 µs | 27.20 µs | 27.12 µs | 207.15 µs | 10.8% | 1.12× | 9.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 colours, drawn | 19.99 µs | 25.66 µs | 22.66 µs | 23.70 µs | 43.73 µs | 26.49 µs | 23.18 µs | 194.00 µs | 22.1% | 1.28× | 199.9 ns | Fuseline 4.3 |
| 100 colours, read | 13.31 µs | 17.74 µs | 15.40 µs | 16.19 µs | 24.77 µs | 22.04 µs | 22.00 µs | 176.08 µs | 25.0% | 1.33× | 133.1 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 colours, unread | 0.96 µs | 1.09 µs | 1.02 µs | 11.98 µs | 18.05 µs | 21.21 µs | 18.62 µs | 184.71 µs | 12.0% | 1.14× | 9.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 tweens, drawn | 144.21 µs | 149.70 µs | 150.35 µs | 151.06 µs | 338.94 µs | 358.54 µs | 351.27 µs | 2308.24 µs | 3.7% | 1.04× | 144.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 tweens, read | 89.24 µs | 91.07 µs | 89.11 µs | 89.42 µs | 129.30 µs | 153.86 µs | 145.36 µs | 1795.74 µs | 2.0% | 1.02× | 89.2 ns | unresolved (Fuseline 4 ahead, intervals overlap) |
| 1000 tweens, unread | 0.95 µs | 6.07 µs | 5.70 µs | 102.09 µs | 192.08 µs | 211.41 µs | 201.20 µs | 2131.60 µs | 84.3% | 6.39× | 0.9 ns | Fuseline 4.3 |
| 1000 springs, drawn | 184.36 µs | 184.07 µs | 336.83 µs | 376.88 µs | 359.02 µs | 402.43 µs | 390.19 µs | 2492.13 µs | -0.2% | 1.00× | 184.4 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 springs, read | 72.55 µs | 70.77 µs | 107.34 µs | 134.07 µs | 138.62 µs | 163.83 µs | 181.89 µs | 2061.01 µs | -2.5% | 0.98× | 72.5 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 springs, unread | 1.00 µs | 1.48 µs | 1.36 µs | 186.92 µs | 212.57 µs | 281.70 µs | 255.77 µs | 2465.65 µs | 32.6% | 1.48× | 1.0 ns | Fuseline 4.3 |
| 1000 vector springs, drawn | 224.74 µs | 222.36 µs | 396.58 µs | 468.89 µs | 465.14 µs | 561.34 µs | 506.11 µs | 2958.08 µs | -1.1% | 0.99× | 224.7 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 vector springs, read | 105.19 µs | 105.33 µs | 135.64 µs | 200.55 µs | 229.88 µs | 281.09 µs | 298.35 µs | 2244.05 µs | 0.1% | 1.00× | 105.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 vector springs, unread | 0.97 µs | 1.41 µs | 1.38 µs | 239.81 µs | 252.39 µs | 333.10 µs | 330.41 µs | 2612.05 µs | 31.1% | 1.45× | 1.0 ns | Fuseline 4.3 |
| 1000 colours, drawn | 272.87 µs | 362.54 µs | 289.78 µs | 306.10 µs | 613.22 µs | 308.22 µs | 289.90 µs | 2442.86 µs | 24.7% | 1.33× | 272.9 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 colours, read | 169.64 µs | 245.40 µs | 198.28 µs | 178.53 µs | 297.49 µs | 241.69 µs | 243.68 µs | 2243.12 µs | 30.9% | 1.45× | 169.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 colours, unread | 0.95 µs | 1.71 µs | 1.59 µs | 144.08 µs | 222.66 µs | 255.97 µs | 225.51 µs | 2401.20 µs | 44.3% | 1.80× | 1.0 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |

#### Scale and kinds of motion: time per frame

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | 4.3 per value | Winner |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10000 springs, drawn | 3250.55 µs | 3209.95 µs | 7198.16 µs | 7316.62 µs | 8236.73 µs | 9371.26 µs | 8547.89 µs | 49030.45 µs | -1.3% | 0.99× | 325.1 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 10000 springs, read | 1373.99 µs | 1228.20 µs | 1721.57 µs | 1762.52 µs | 2130.13 µs | 2602.09 µs | 2782.53 µs | 26860.89 µs | -11.9% | 0.89× | 137.4 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 10000 springs, unread | 1.16 µs | 43.08 µs | 40.67 µs | 2487.61 µs | 2929.10 µs | 3602.38 µs | 3978.85 µs | 36055.54 µs | 97.3% | 37.08× | 0.1 ns | Fuseline 4.3 |
| 1000 unrelated springs, staggered, drawn | 23.94 µs | 25.66 µs | 35.38 µs | 35.47 µs | 36.75 µs | 39.89 µs | 39.91 µs | 201.72 µs | 6.7% | 1.07× | 23.9 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 unrelated springs, staggered, read | 36.52 µs | 34.12 µs | 36.28 µs | 35.19 µs | 37.61 µs | 38.91 µs | 38.03 µs | 174.11 µs | -7.0% | 0.93× | 36.5 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 unrelated springs, staggered, unread | 4.74 µs | 5.98 µs | 6.31 µs | 20.69 µs | 20.67 µs | 23.14 µs | 23.29 µs | 169.90 µs | 20.7% | 1.26× | 4.7 ns | Fuseline 4.3 |
| 1000 springs in their tails, drawn | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | < 0.05 µs | both below floor | n/a | 0.0 ns | unresolved (Fuseline 2 ahead, intervals overlap) |
| 1000 springs in their tails, read | 16.73 µs | 21.48 µs | 20.40 µs | 20.80 µs | 21.11 µs | 19.80 µs | 20.15 µs | 19.48 µs | 22.1% | 1.28× | 16.7 ns | Fuseline 4.3 |
| 1000 springs in their tails, unread | < 0.05 µs | 0.17 µs | 0.16 µs | 0.16 µs | 0.18 µs | 0.16 µs | 0.19 µs | 0.28 µs | > 70.2% | > 3× | < 0.05 ns | Fuseline 4.3 |
| 1000 values, mixed motions, drawn | 126.55 µs | 139.64 µs | 256.50 µs | 254.35 µs | 262.47 µs | 293.96 µs | 275.09 µs | 1852.87 µs | 9.4% | 1.10× | 126.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 values, mixed motions, read | 63.67 µs | 68.58 µs | 99.57 µs | 95.53 µs | 105.97 µs | 114.56 µs | 114.55 µs | 1487.58 µs | 7.2% | 1.08× | 63.7 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 values, mixed motions, unread | 1.93 µs | 2.77 µs | 2.72 µs | 136.31 µs | 130.83 µs | 152.83 µs | 142.09 µs | 1639.13 µs | 30.4% | 1.44× | 1.9 ns | Fuseline 4.3 |
| 1000 springs seen again after 5 s unseen | 164.01 µs | 167.99 µs | 358.46 µs | 389.59 µs | 367.90 µs | 406.26 µs | 375.34 µs | 2724.09 µs | 2.4% | 1.02× | 164.0 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |

#### Retargeting, gestures, decay: time per frame

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | 4.3 per value | Winner |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 springs retargeted once, drawn | 6.63 µs | 6.54 µs | 11.48 µs | 11.61 µs | 12.15 µs | 14.19 µs | 14.21 µs | 86.90 µs | -1.3% | 0.99× | 66.3 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 100 springs retargeted once, read | 4.56 µs | 4.60 µs | 5.87 µs | 6.74 µs | 7.01 µs | 7.82 µs | 8.67 µs | 67.94 µs | 0.8% | 1.01× | 45.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 springs retargeted once, unread | 1.16 µs | 1.37 µs | 1.38 µs | 10.78 µs | 11.74 µs | 13.54 µs | 14.96 µs | 122.47 µs | 15.1% | 1.18× | 11.6 ns | Fuseline 4.3 |
| 100 springs retargeted every frame, drawn | 21.78 µs | 22.95 µs | 35.69 µs | 68.52 µs | 71.52 µs | 54.84 µs | 539.73 µs | 1356.42 µs | 5.1% | 1.05× | 217.8 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 springs retargeted every frame, read | 12.08 µs | 13.88 µs | 18.17 µs | 42.91 µs | 44.34 µs | 29.28 µs | 517.53 µs | 1213.71 µs | 13.0% | 1.15× | 120.8 ns | Fuseline 4.3 |
| 100 springs retargeted every frame, unread | 7.89 µs | 17.94 µs | 17.74 µs | 51.51 µs | 53.72 µs | 39.03 µs | 488.06 µs | 1178.76 µs | 56.0% | 2.27× | 78.9 ns | Fuseline 4.3 |
| 1000 springs retargeted every frame, drawn | 256.33 µs | 285.98 µs | 476.27 µs | 933.56 µs | 1005.99 µs | 865.02 µs | 6673.95 µs | 17972.30 µs | 10.4% | 1.12× | 256.3 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs retargeted every frame, read | 115.73 µs | 140.07 µs | 177.97 µs | 475.43 µs | 477.12 µs | 330.39 µs | 5920.52 µs | 15394.52 µs | 17.4% | 1.21× | 115.7 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs retargeted every frame, unread | 74.41 µs | 213.82 µs | 213.51 µs | 651.43 µs | 628.88 µs | 479.83 µs | 5700.27 µs | 15559.23 µs | 65.2% | 2.87× | 74.4 ns | Fuseline 4.3 |
| 100 vector springs retargeted every frame, drawn | 25.07 µs | 28.24 µs | 39.79 µs | 91.80 µs | 95.77 µs | 74.32 µs | 491.86 µs | 1352.11 µs | 11.2% | 1.13× | 250.7 ns | Fuseline 4.3 |
| 100 vector springs retargeted every frame, read | 16.47 µs | 21.71 µs | 24.70 µs | 66.58 µs | 69.18 µs | 44.10 µs | 514.95 µs | 1213.04 µs | 24.2% | 1.32× | 164.7 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 vector springs retargeted every frame, unread | 10.47 µs | 22.25 µs | 21.11 µs | 74.48 µs | 75.13 µs | 53.91 µs | 502.00 µs | 1272.90 µs | 52.9% | 2.13× | 104.7 ns | Fuseline 4.3 |
| 1000 vector springs retargeted every frame, drawn | 301.43 µs | 359.14 µs | 539.11 µs | 1232.28 µs | 1168.76 µs | 970.54 µs | 6389.94 µs | 18311.34 µs | 16.1% | 1.19× | 301.4 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 vector springs retargeted every frame, read | 195.60 µs | 268.52 µs | 305.90 µs | 865.30 µs | 839.39 µs | 563.28 µs | 6639.60 µs | 17340.90 µs | 27.2% | 1.37× | 195.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 vector springs retargeted every frame, unread | 137.64 µs | 316.74 µs | 315.96 µs | 922.75 µs | 964.60 µs | 714.32 µs | 6839.39 µs | 17540.62 µs | 56.5% | 2.30× | 137.6 ns | Fuseline 4.3 |
| tween velocity, 1000 readings | 48.85 µs | 48.78 µs | 49.35 µs | 48.77 µs | 48.84 µs | 83.47 µs | n/a | 173.39 µs | -0.1% | 1.00× |  | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 decays, drawn | 1.53 µs | 1.53 µs | 1.65 µs | 1.74 µs | 1.61 µs | n/a | n/a | 2.12 µs | -0.0% | 1.00× |  | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1 decays, read | 1.08 µs | 1.09 µs | 1.07 µs | 1.04 µs | 1.04 µs | n/a | n/a | 1.29 µs | 1.1% | 1.01× |  | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| 1 decays, unread | 1.09 µs | 1.09 µs | 1.10 µs | 1.31 µs | 1.25 µs | n/a | n/a | 1.65 µs | -0.5% | 0.99× |  | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 100 decays, drawn | 28.01 µs | 30.05 µs | 36.57 µs | 35.94 µs | 45.29 µs | n/a | n/a | 234.85 µs | 6.8% | 1.07× | 280.1 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 decays, read | 7.34 µs | 7.84 µs | 11.85 µs | 10.85 µs | 11.25 µs | n/a | n/a | 164.51 µs | 6.3% | 1.07× | 73.4 ns | Fuseline 4.3 |
| 100 decays, unread | 1.12 µs | 1.24 µs | 1.15 µs | 14.19 µs | 14.63 µs | n/a | n/a | 188.78 µs | 9.7% | 1.11× | 11.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 values tracking a gesture, drawn | 26.93 µs | 30.85 µs | 29.44 µs | 58.91 µs | 65.72 µs | n/a | n/a | 234.78 µs | 12.7% | 1.15× | 269.3 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 values tracking a gesture, read | 12.28 µs | 13.24 µs | 12.41 µs | 42.32 µs | 45.59 µs | n/a | n/a | 212.19 µs | 7.3% | 1.08× | 122.8 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 100 values tracking a gesture, unread | 2.38 µs | 19.52 µs | 15.32 µs | 51.91 µs | 49.02 µs | n/a | n/a | 209.96 µs | 87.8% | 8.19× | 23.8 ns | Fuseline 4.3 |

#### Transitions and timelines: time per frame

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | 4.3 per value | Winner |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tab transitions, drawn | 3.43 µs | 3.21 µs | 3.60 µs | 3.60 µs | 3.37 µs | 3.45 µs | 3.01 µs | 9.23 µs | -6.8% | 0.94× |  | unresolved (Fuseline 1 ahead, intervals overlap) |
| tab transitions, read | 1.97 µs | 2.01 µs | 2.91 µs | 1.84 µs | 1.94 µs | 2.36 µs | 2.37 µs | 7.16 µs | 1.9% | 1.02× |  | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| tab transitions, unread | 1.83 µs | 1.97 µs | 1.81 µs | 2.16 µs | 2.45 µs | 2.57 µs | 2.55 µs | 7.44 µs | 7.3% | 1.08× |  | unresolved (Fuseline 4 ahead, intervals overlap) |
| tab changes every 3 frames, drawn | 3.19 µs | 3.25 µs | 4.68 µs | 5.42 µs | 5.54 µs | 8.32 µs | 23.49 µs | 59.01 µs | 1.8% | 1.02× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| tab changes every 3 frames, read | 2.75 µs | 2.62 µs | 2.73 µs | 3.41 µs | 3.75 µs | 6.21 µs | 22.23 µs | 57.26 µs | -4.9% | 0.95× |  | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| tab changes every 3 frames, unread | 2.39 µs | 2.45 µs | 2.99 µs | 4.54 µs | 5.03 µs | 7.80 µs | 23.61 µs | 65.92 µs | 2.4% | 1.02× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| tab reversal every 4 frames, drawn | 2.44 µs | 2.48 µs | 2.86 µs | 3.12 µs | 3.10 µs | 6.76 µs | 15.47 µs | 37.69 µs | 1.6% | 1.02× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| tab reversal every 4 frames, read | 1.87 µs | 1.87 µs | 2.01 µs | 2.22 µs | 2.27 µs | 5.26 µs | 14.31 µs | 35.06 µs | 0.1% | 1.00× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| tab reversal every 4 frames, unread | 1.53 µs | 1.61 µs | 1.57 µs | 2.34 µs | 2.37 µs | 5.87 µs | 13.83 µs | 35.12 µs | 5.4% | 1.06× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| timeline, 20 tracks | 0.41 µs | 0.41 µs | 0.40 µs | 0.39 µs | 0.41 µs | 0.47 µs | 0.48 µs | 2.87 µs | 0.8% | 1.01× |  | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| timeline seek, 20 tracks | 0.91 µs | 0.94 µs | 0.92 µs | 0.93 µs | 0.92 µs | 1.34 µs | 1.11 µs | n/a | 3.5% | 1.04× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| timeline reverse, 20 tracks | 0.95 µs | 0.94 µs | 0.94 µs | 0.94 µs | 0.93 µs | n/a | n/a | n/a | -0.2% | 1.00× |  | unresolved (Fuseline 3 ahead, intervals overlap) |

#### Scheduling, start and stop: time per frame

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | 4.3 per value | Winner |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| driver churn, 10 | 4.82 µs | 5.10 µs | 4.96 µs | 5.00 µs | 5.15 µs | 5.16 µs | 4.97 µs | 14.82 µs | 5.4% | 1.06× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| driver churn, 100 | 51.59 µs | 56.05 µs | 50.41 µs | 54.30 µs | 49.56 µs | 47.59 µs | 53.97 µs | 159.70 µs | 8.0% | 1.09× |  | unresolved (Fuseline 2 ahead, intervals overlap) |
| driver churn, 1000 | 402.97 µs | 444.84 µs | 405.16 µs | 421.73 µs | 461.89 µs | 463.52 µs | 415.39 µs | 1720.03 µs | 9.4% | 1.10× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs started and cancelled | 597.39 µs | 681.57 µs | 693.99 µs | 752.43 µs | 732.41 µs | 821.49 µs | 806.38 µs | 2218.13 µs | 12.4% | 1.14× | 597.4 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs created, run and dropped | 75.60 µs | 93.55 µs | 103.20 µs | 138.46 µs | 145.15 µs | 174.72 µs | 174.08 µs | 1084.28 µs | 19.2% | 1.24× | 75.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| idle, 1000 settled values | < 0.05 µs | 0.05 µs | 0.10 µs | 0.06 µs | 0.06 µs | 0.09 µs | 0.06 µs | 0.07 µs | > 3.3% | > 1× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |

#### Refresh rates and irregular frames: time per frame

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | 4.3 per value | Winner |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1000 springs at 30 Hz | 160.13 µs | 178.13 µs | 335.65 µs | 360.35 µs | 362.44 µs | 409.25 µs | 415.72 µs | 2968.05 µs | 10.1% | 1.11× | 160.1 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 40 Hz | 155.13 µs | 194.26 µs | 373.34 µs | 380.35 µs | 374.21 µs | 400.47 µs | 439.07 µs | 3630.15 µs | 20.1% | 1.25× | 155.1 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 45 Hz | 168.85 µs | 168.83 µs | 335.00 µs | 384.72 µs | 416.31 µs | 453.36 µs | 473.35 µs | 3286.65 µs | -0.0% | 1.00× | 168.8 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 springs at 50 Hz | 163.22 µs | 166.47 µs | 332.29 µs | 358.08 µs | 426.07 µs | 422.12 µs | 403.31 µs | 3112.28 µs | 2.0% | 1.02× | 163.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 60 Hz | 164.06 µs | 153.25 µs | 377.74 µs | 385.92 µs | 401.48 µs | 428.20 µs | 431.85 µs | 3117.95 µs | -7.1% | 0.93× | 164.1 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 springs at 72 Hz | 172.18 µs | 183.20 µs | 389.09 µs | 403.65 µs | 376.15 µs | 408.61 µs | 431.70 µs | 3260.86 µs | 6.0% | 1.06× | 172.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 75 Hz | 171.12 µs | 162.65 µs | 369.50 µs | 415.00 µs | 430.69 µs | 504.66 µs | 519.96 µs | 3146.74 µs | -5.2% | 0.95× | 171.1 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 springs at 90 Hz | 173.26 µs | 151.69 µs | 352.43 µs | 352.93 µs | 366.55 µs | 413.88 µs | 408.47 µs | 2880.30 µs | -14.2% | 0.88× | 173.3 ns | Fuseline 4.2 |
| 1000 springs at 100 Hz | 159.86 µs | 167.14 µs | 349.23 µs | 386.61 µs | 415.39 µs | 422.17 µs | 410.63 µs | 3178.39 µs | 4.4% | 1.05× | 159.9 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 120 Hz | 189.15 µs | 186.36 µs | 420.94 µs | 370.41 µs | 459.54 µs | 456.00 µs | 488.65 µs | 3325.19 µs | -1.5% | 0.99× | 189.2 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 springs at 125 Hz | 153.23 µs | 166.48 µs | 346.46 µs | 372.13 µs | 382.75 µs | 420.06 µs | 403.78 µs | 3118.51 µs | 8.0% | 1.09× | 153.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 144 Hz | 152.55 µs | 177.36 µs | 355.30 µs | 365.98 µs | 397.61 µs | 518.68 µs | 463.90 µs | 3459.47 µs | 14.0% | 1.16× | 152.5 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 165 Hz | 165.58 µs | 205.41 µs | 361.92 µs | 371.04 µs | 371.08 µs | 426.82 µs | 458.71 µs | 3143.09 µs | 19.4% | 1.24× | 165.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs at 240 Hz | 190.33 µs | 204.86 µs | 407.55 µs | 429.39 µs | 380.12 µs | 444.61 µs | 534.82 µs | 3400.20 µs | 7.1% | 1.08× | 190.3 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs, jittered 120 Hz | 156.83 µs | 156.06 µs | 332.74 µs | 365.32 µs | 387.54 µs | 428.36 µs | 472.87 µs | 3034.99 µs | -0.5% | 1.00× | 156.8 ns | unresolved (Fuseline 4.2 ahead, intervals overlap) |
| 1000 springs, dropped frames | 188.19 µs | 207.43 µs | 396.24 µs | 447.22 µs | 381.92 µs | 449.89 µs | 457.35 µs | 3304.28 µs | 9.3% | 1.10× | 188.2 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs, a 500 ms stall every 2 s | 164.55 µs | 189.05 µs | 387.26 µs | 411.63 µs | 424.75 µs | 443.33 µs | 448.77 µs | 3481.83 µs | 13.0% | 1.15× | 164.6 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |
| 1000 springs, 60, 120 and 30 Hz in turn | 178.77 µs | 191.94 µs | 383.79 µs | 370.76 µs | 411.15 µs | 428.73 µs | 495.62 µs | 2965.01 µs | 6.9% | 1.07× | 178.8 ns | unresolved (Fuseline 4.3 ahead, intervals overlap) |

#### Sustained work: time per frame

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | 4.3 per value | Winner |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| a busy screen for 10 s | 51.23 µs | 57.06 µs | 91.00 µs | 99.77 µs | 104.06 µs | 115.55 µs | 165.36 µs | 820.71 µs | 10.2% | 1.11× |  | unresolved (Fuseline 4.3 ahead, intervals overlap) |

<details><summary>Allocations per frame, every row</summary>

| Test | Fuseline 4.3 | Fuseline 4.2 | Fuseline 4 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Least |
|---|---|---|---|---|---|---|---|---|---|
| 1 tweens, drawn | 731 B | 705 B | 705 B | 673 B | 952 B | 1032 B | 1032 B | 1040 B | Fuseline 3.1 |
| 1 tweens, read | 567 B | 527 B | 527 B | 495 B | 495 B | 575 B | 575 B | 583 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 tweens, unread | 511 B | 511 B | 511 B | 531 B | 728 B | 808 B | 808 B | 816 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| 1 springs, drawn | 952 B | 952 B | 952 B | 920 B | 952 B | 1031 B | 1031 B | 1039 B | Fuseline 3.1 |
| 1 springs, read | 527 B | 527 B | 527 B | 495 B | 495 B | 575 B | 575 B | 583 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 springs, unread | 511 B | 511 B | 511 B | 705 B | 728 B | 807 B | 807 B | 815 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| 1 vector springs, drawn | 701 B | 701 B | 700 B | 677 B | 735 B | 798 B | 798 B | 1047 B | Fuseline 3.1 |
| 1 vector springs, read | 401 B | 401 B | 401 B | 378 B | 378 B | 441 B | 441 B | 591 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 vector springs, unread | 385 B | 385 B | 385 B | 499 B | 540 B | 603 B | 603 B | 823 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| 1 colours, drawn | 820 B | 820 B | 820 B | 788 B | 976 B | 745 B | 745 B | 761 B | tie: Fuseline 2, Fuseline 1 |
| 1 colours, read | 539 B | 539 B | 539 B | 507 B | 519 B | 583 B | 583 B | 591 B | Fuseline 3.1 |
| 1 colours, unread | 511 B | 511 B | 511 B | 603 B | 728 B | 608 B | 608 B | 621 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| 100 tweens, drawn | 5907 B | 5860 B | 5860 B | 5828 B | 14978 B | 19026 B | 19026 B | 68897 B | Fuseline 3.1 |
| 100 tweens, read | 2163 B | 2116 B | 2116 B | 2084 B | 2077 B | 6124 B | 6124 B | 52076 B | Fuseline 3 |
| 100 tweens, unread | 509 B | 517 B | 517 B | 1066 B | 2082 B | 6130 B | 6130 B | 56001 B | Fuseline 4.3 |
| 100 springs, drawn | 13672 B | 13665 B | 15018 B | 14986 B | 14978 B | 19026 B | 19026 B | 68897 B | Fuseline 4.2 |
| 100 springs, read | 2123 B | 2116 B | 2116 B | 2084 B | 2077 B | 6124 B | 6124 B | 52076 B | Fuseline 3 |
| 100 springs, unread | 509 B | 517 B | 517 B | 2090 B | 2082 B | 6130 B | 6130 B | 56001 B | Fuseline 4.3 |
| 100 vector springs, drawn | 13564 B | 13557 B | 14898 B | 14866 B | 14978 B | 19826 B | 19826 B | 69697 B | Fuseline 4.2 |
| 100 vector springs, read | 2123 B | 2116 B | 2116 B | 2084 B | 2077 B | 6924 B | 6924 B | 52876 B | Fuseline 3 |
| 100 vector springs, unread | 509 B | 517 B | 517 B | 2077 B | 2082 B | 6930 B | 6930 B | 56801 B | Fuseline 4.3 |
| 100 colours, drawn | 9033 B | 9026 B | 9026 B | 8994 B | 17378 B | 9494 B | 9494 B | 56731 B | Fuseline 3.1 |
| 100 colours, read | 3327 B | 3320 B | 3320 B | 3288 B | 4477 B | 6924 B | 6924 B | 52876 B | Fuseline 3.1 |
| 100 colours, unread | 509 B | 517 B | 517 B | 1286 B | 2082 B | 5775 B | 5775 B | 52885 B | Fuseline 4.3 |
| 1000 tweens, drawn | 65051 B | 64937 B | 64937 B | 64905 B | 177410 B | 217538 B | 217538 B | 701709 B | Fuseline 3.1 |
| 1000 tweens, read | 30664 B | 30549 B | 30549 B | 30517 B | 30429 B | 70557 B | 70557 B | 534188 B | Fuseline 3 |
| 1000 tweens, unread | 509 B | 610 B | 610 B | 8148 B | 21410 B | 61538 B | 61538 B | 545709 B | Fuseline 4.3 |
| 1000 springs, drawn | 156975 B | 156900 B | 177530 B | 177498 B | 177410 B | 217538 B | 217538 B | 701709 B | Fuseline 4.2 |
| 1000 springs, read | 30624 B | 30549 B | 30549 B | 30517 B | 30429 B | 70557 B | 70557 B | 534188 B | Fuseline 3 |
| 1000 springs, unread | 509 B | 610 B | 610 B | 21498 B | 21410 B | 61538 B | 61538 B | 545709 B | Fuseline 4.3 |
| 1000 vector springs, drawn | 155674 B | 155599 B | 176057 B | 176025 B | 177410 B | 225538 B | 225538 B | 709709 B | Fuseline 4.2 |
| 1000 vector springs, read | 30624 B | 30549 B | 30549 B | 30517 B | 30429 B | 78557 B | 78557 B | 542188 B | Fuseline 3 |
| 1000 vector springs, unread | 509 B | 610 B | 610 B | 21324 B | 21410 B | 69538 B | 69538 B | 553709 B | Fuseline 4.3 |
| 1000 colours, drawn | 101181 B | 101106 B | 101106 B | 101074 B | 201410 B | 98502 B | 98502 B | 569907 B | tie: Fuseline 2, Fuseline 1 |
| 1000 colours, read | 42664 B | 42589 B | 42589 B | 42557 B | 54429 B | 78557 B | 78557 B | 542188 B | Fuseline 3.1 |
| 1000 colours, unread | 509 B | 610 B | 610 B | 11014 B | 21410 B | 54476 B | 54476 B | 524321 B | Fuseline 4.3 |
| 10000 springs, drawn | 1607800 B | 1603576 B | 1763611 B | 1763579 B | 1759543 B | 2163619 B | 2163619 B | 7297365 B | Fuseline 4.2 |
| 10000 springs, read | 326708 B | 322484 B | 322484 B | 322452 B | 318416 B | 722492 B | 722492 B | 5366068 B | Fuseline 3 |
| 10000 springs, unread | 496 B | 5622 B | 5622 B | 167579 B | 163543 B | 567619 B | 567619 B | 5701365 B | Fuseline 4.3 |
| 1000 unrelated springs, staggered, drawn | 15348 B | 15476 B | 16374 B | 16394 B | 16917 B | 20381 B | 20309 B | 64729 B | Fuseline 4.3 |
| 1000 unrelated springs, staggered, read | 31823 B | 31938 B | 31938 B | 31959 B | 31959 B | 35600 B | 35528 B | 76318 B | Fuseline 4.3 |
| 1000 unrelated springs, staggered, unread | 1818 B | 2188 B | 2188 B | 3462 B | 3464 B | 7104 B | 7032 B | 51455 B | Fuseline 4.3 |
| 1000 springs in their tails, drawn | 184 B | 184 B | 184 B | 184 B | 184 B | 184 B | 184 B | 184 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 springs in their tails, read | 30016 B | 30016 B | 30016 B | 30016 B | 30016 B | 30016 B | 30016 B | 30016 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 springs in their tails, unread | 64 B | 64 B | 64 B | 64 B | 64 B | 64 B | 64 B | 64 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 values, mixed motions, drawn | 120201 B | 120112 B | 132675 B | 132643 B | 133488 B | 164181 B | 164181 B | 546046 B | Fuseline 4.2 |
| 1000 values, mixed motions, read | 30770 B | 30673 B | 30673 B | 30641 B | 30553 B | 61246 B | 61246 B | 415205 B | Fuseline 3 |
| 1000 values, mixed motions, unread | 638 B | 759 B | 759 B | 14187 B | 14253 B | 44946 B | 44946 B | 426812 B | Fuseline 4.3 |
| 1000 springs seen again after 5 s unseen | 156760 B | 156760 B | 177480 B | 177448 B | 177448 B | 217488 B | 217488 B | 701552 B | tie: Fuseline 4.3, Fuseline 4.2 |
| 100 springs retargeted once, drawn | 5308 B | 5309 B | 5805 B | 5800 B | 5984 B | 7556 B | 7872 B | 27565 B | Fuseline 4.3 |
| 100 springs retargeted once, read | 1916 B | 1917 B | 1917 B | 1914 B | 1906 B | 3503 B | 3794 B | 21952 B | Fuseline 3 |
| 100 springs retargeted once, unread | 306 B | 325 B | 325 B | 907 B | 921 B | 2517 B | 2811 B | 22497 B | Fuseline 4.3 |
| 100 springs retargeted every frame, drawn | 14002 B | 14008 B | 15356 B | 16654 B | 16672 B | 40665 B | 210979 B | 610600 B | Fuseline 4.3 |
| 100 springs retargeted every frame, read | 2472 B | 2475 B | 2475 B | 2455 B | 2448 B | 26493 B | 210875 B | 590647 B | Fuseline 3 |
| 100 springs retargeted every frame, unread | 868 B | 881 B | 881 B | 3779 B | 3776 B | 27812 B | 210883 B | 597747 B | Fuseline 4.3 |
| 1000 springs retargeted every frame, drawn | 159172 B | 159244 B | 179805 B | 200342 B | 200513 B | 440095 B | 2108130 B | 6159197 B | Fuseline 4.3 |
| 1000 springs retargeted every frame, read | 33046 B | 33084 B | 33084 B | 33134 B | 33046 B | 273147 B | 2117018 B | 5919492 B | tie: Fuseline 4.3, Fuseline 3 |
| 1000 springs retargeted every frame, unread | 3044 B | 3205 B | 3205 B | 44601 B | 44513 B | 284615 B | 2108034 B | 6003716 B | Fuseline 4.3 |
| 100 vector springs retargeted every frame, drawn | 13999 B | 14009 B | 15357 B | 16655 B | 16673 B | 60663 B | 230974 B | 616995 B | Fuseline 4.3 |
| 100 vector springs retargeted every frame, read | 2469 B | 2477 B | 2477 B | 2457 B | 2449 B | 46490 B | 230870 B | 597042 B | Fuseline 3 |
| 100 vector springs retargeted every frame, unread | 865 B | 883 B | 883 B | 3780 B | 3777 B | 47809 B | 230878 B | 604142 B | Fuseline 4.3 |
| 1000 vector springs retargeted every frame, drawn | 159135 B | 159258 B | 179819 B | 200355 B | 200527 B | 640068 B | 2308077 B | 6223143 B | Fuseline 4.3 |
| 1000 vector springs retargeted every frame, read | 33010 B | 33097 B | 33097 B | 33147 B | 33059 B | 473121 B | 2316965 B | 5983439 B | Fuseline 4.3 |
| 1000 vector springs retargeted every frame, unread | 3007 B | 3218 B | 3218 B | 44615 B | 44527 B | 484588 B | 2307981 B | 6067663 B | Fuseline 4.3 |
| tween velocity, 1000 readings | 119 B | 119 B | 119 B | 119 B | 119 B | 119 B | n/a | 119 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Compose |
| 1 decays, drawn | 1051 B | 1013 B | 1013 B | 983 B | 984 B | n/a | n/a | 1067 B | Fuseline 3.1 |
| 1 decays, read | 615 B | 578 B | 577 B | 547 B | 547 B | n/a | n/a | 630 B | tie: Fuseline 3.1, Fuseline 3 |
| 1 decays, unread | 562 B | 562 B | 562 B | 765 B | 765 B | n/a | n/a | 848 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| 100 decays, drawn | 13786 B | 13746 B | 15098 B | 15066 B | 15058 B | n/a | n/a | 76897 B | Fuseline 4.2 |
| 100 decays, read | 2236 B | 2196 B | 2196 B | 2164 B | 2157 B | n/a | n/a | 60076 B | Fuseline 3 |
| 100 decays, unread | 598 B | 597 B | 597 B | 2170 B | 2162 B | n/a | n/a | 64001 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| 100 values tracking a gesture, drawn | 14679 B | 14696 B | 14696 B | 14693 B | 14696 B | n/a | n/a | 102479 B | Fuseline 4.3 |
| 100 values tracking a gesture, read | 1764 B | 1781 B | 1781 B | 1781 B | 1781 B | n/a | n/a | 88259 B | Fuseline 4.3 |
| 100 values tracking a gesture, unread | 156 B | 1787 B | 1787 B | 1787 B | 1787 B | n/a | n/a | 89583 B | Fuseline 4.3 |
| tab transitions, drawn | 1154 B | 1153 B | 1145 B | 1113 B | 1113 B | 1317 B | 1288 B | 2905 B | Fuseline 3 |
| tab transitions, read | 806 B | 805 B | 801 B | 763 B | 762 B | 999 B | 970 B | 2483 B | Fuseline 3 |
| tab transitions, unread | 796 B | 795 B | 790 B | 895 B | 894 B | 1147 B | 1119 B | 2735 B | Fuseline 4 |
| tab changes every 3 frames, drawn | 1168 B | 1168 B | 1288 B | 1322 B | 1322 B | 2597 B | 8074 B | 23325 B | tie: Fuseline 4.3, Fuseline 4.2 |
| tab changes every 3 frames, read | 711 B | 711 B | 711 B | 679 B | 679 B | 1987 B | 7498 B | 22141 B | tie: Fuseline 3.1, Fuseline 3 |
| tab changes every 3 frames, unread | 747 B | 747 B | 747 B | 1066 B | 1066 B | 2373 B | 7850 B | 23101 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| tab reversal every 4 frames, drawn | 1156 B | 1156 B | 1186 B | 1154 B | 1154 B | 2734 B | 5901 B | 16400 B | tie: Fuseline 3.1, Fuseline 3 |
| tab reversal every 4 frames, read | 700 B | 700 B | 700 B | 668 B | 668 B | 2108 B | 5276 B | 15366 B | tie: Fuseline 3.1, Fuseline 3 |
| tab reversal every 4 frames, unread | 714 B | 714 B | 714 B | 898 B | 898 B | 2510 B | 5677 B | 16176 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4 |
| timeline, 20 tracks | 119 B | 119 B | 119 B | 119 B | 119 B | 157 B | 119 B | 1053 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 1 |
| timeline seek, 20 tracks | 119 B | 119 B | 119 B | 119 B | 119 B | 599 B | 119 B | n/a | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 1 |
| timeline reverse, 20 tracks | 119 B | 119 B | 119 B | 119 B | 119 B | n/a | n/a | n/a | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3 |
| driver churn, 10 | 2562 B | 2554 B | 2538 B | 2520 B | 2504 B | 2751 B | 2671 B | 6799 B | Fuseline 3 |
| driver churn, 100 | 19962 B | 19885 B | 19724 B | 19849 B | 19689 B | 21795 B | 20995 B | 67120 B | Fuseline 3 |
| driver churn, 1000 | 193984 B | 193192 B | 191592 B | 193140 B | 191540 B | 212240 B | 204240 B | 670330 B | Fuseline 3 |
| 1000 springs started and cancelled | 281491 B | 281839 B | 281035 B | 290507 B | 289707 B | 310212 B | 306211 B | 795178 B | Fuseline 4 |
| 1000 springs created, run and dropped | 34216 B | 36440 B | 36171 B | 41489 B | 41222 B | 54968 B | 53635 B | 255952 B | Fuseline 4.3 |
| idle, 1000 settled values | 181 B | 181 B | 181 B | 181 B | 181 B | 181 B | 181 B | 181 B | tie: Fuseline 4.3, Fuseline 4.2, Fuseline 4, Fuseline 3.1, Fuseline 3, Fuseline 2, Fuseline 1, Compose |
| 1000 springs at 30 Hz | 159006 B | 158256 B | 178078 B | 178045 B | 177165 B | 218085 B | 218085 B | 783136 B | Fuseline 4.2 |
| 1000 springs at 40 Hz | 158460 B | 157898 B | 177945 B | 177912 B | 177252 B | 217953 B | 217952 B | 782737 B | Fuseline 4.2 |
| 1000 springs at 45 Hz | 158279 B | 157778 B | 177900 B | 177868 B | 177281 B | 217908 B | 217908 B | 782603 B | Fuseline 4.2 |
| 1000 springs at 50 Hz | 158133 B | 157683 B | 177864 B | 177832 B | 177304 B | 217872 B | 217872 B | 782496 B | Fuseline 4.2 |
| 1000 springs at 60 Hz | 157915 B | 157540 B | 177811 B | 177779 B | 177339 B | 217819 B | 217819 B | 782336 B | Fuseline 4.2 |
| 1000 springs at 72 Hz | 157735 B | 157423 B | 177769 B | 177737 B | 177369 B | 217776 B | 217776 B | 782205 B | Fuseline 4.2 |
| 1000 springs at 75 Hz | 157699 B | 157399 B | 177760 B | 177728 B | 177376 B | 217768 B | 217768 B | 782179 B | Fuseline 4.2 |
| 1000 springs at 90 Hz | 157556 B | 157306 B | 177727 B | 177695 B | 177401 B | 217735 B | 217735 B | 782074 B | Fuseline 4.2 |
| 1000 springs at 100 Hz | 157484 B | 157260 B | 177710 B | 177678 B | 177414 B | 217718 B | 217718 B | 782022 B | Fuseline 4.2 |
| 1000 springs at 120 Hz | 157377 B | 157190 B | 177685 B | 177653 B | 177433 B | 217693 B | 217693 B | 781944 B | Fuseline 4.2 |
| 1000 springs at 125 Hz | 157356 B | 157176 B | 177680 B | 177648 B | 177437 B | 217688 B | 217688 B | 781928 B | Fuseline 4.2 |
| 1000 springs at 144 Hz | 157288 B | 157131 B | 177664 B | 177632 B | 177449 B | 217672 B | 217672 B | 781879 B | Fuseline 4.2 |
| 1000 springs at 165 Hz | 157231 B | 157094 B | 177651 B | 177619 B | 177459 B | 217659 B | 217659 B | 781837 B | Fuseline 4.2 |
| 1000 springs at 240 Hz | 157109 B | 157015 B | 177623 B | 177591 B | 177481 B | 217631 B | 217631 B | 781748 B | Fuseline 4.2 |
| 1000 springs, jittered 120 Hz | 157079 B | 157004 B | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 4.2 |
| 1000 springs, dropped frames | 157079 B | 157004 B | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 4.2 |
| 1000 springs, a 500 ms stall every 2 s | 157079 B | 157004 B | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 4.2 |
| 1000 springs, 60, 120 and 30 Hz in turn | 157079 B | 157004 B | 177634 B | 177602 B | 177514 B | 217642 B | 217642 B | 781733 B | Fuseline 4.2 |
| a busy screen for 10 s | 38713 B | 38790 B | 42187 B | 42453 B | 43032 B | 52780 B | 69110 B | 241231 B | Fuseline 4.3 |

</details>

Whole interface paths (per frame, composition, layout and drawing included; memory is what every
thread allocated):

| Interface path | Fuseline 4.3 | Fuseline 4.2 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Reduction vs 4.2 | Speedup vs 4.2 | Winner |
|---|---|---|---|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 707 µs | 640 µs | 682 µs | 557 µs | n/a | n/a | 1858 µs | -10.5% | 0.91× | unresolved (Fuseline 3 ahead, intervals overlap) |
| shared element, moving destination | 654 µs | 592 µs | 548 µs | 621 µs | n/a | n/a | 818 µs | -10.6% | 0.90× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| rapid tab switching | 3677 µs | 3704 µs | 3675 µs | 4004 µs | 4054 µs | 3941 µs | 4085 µs | 0.7% | 1.01× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| a page of 120 tiles, 3 values each, composed | 2811 µs | 2649 µs | 2542 µs | 4350 µs | n/a | n/a | 4322 µs | -6.1% | 0.94× | unresolved (Fuseline 3.1 ahead, intervals overlap) |
| selection moving across 120 tiles | 1842 µs | 2000 µs | 1990 µs | 2016 µs | n/a | n/a | 2127 µs | 7.9% | 1.09× | unresolved (Fuseline 4.3 ahead, intervals overlap) |

| Interface path, memory per frame | Fuseline 4.3 | Fuseline 4.2 | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose |
|---|---|---|---|---|---|---|---|
| 50 tiles reflowing (layout motion) | 20.2 KB | 20.2 KB | 20.8 KB | 20.9 KB | n/a | n/a | 187.9 KB |
| shared element, moving destination | 10.9 KB | 10.9 KB | 9.7 KB | 9.8 KB | n/a | n/a | 13.5 KB |
| rapid tab switching | 35.0 KB | 35.0 KB | 35.2 KB | 35.4 KB | 48.4 KB | 48.4 KB | 124.7 KB |
| a page of 120 tiles, 3 values each, composed | 220.0 KB | 219.3 KB | 214.8 KB | 682.1 KB | n/a | n/a | 715.3 KB |
| selection moving across 120 tiles | 4.6 KB | 4.6 KB | 4.8 KB | 6.4 KB | n/a | n/a | 20.5 KB |

Per frame, composition, layout and drawing included (Compose's test clock), after a settled start; memory is what every thread allocated; 3 warm-up runs, then 9 rounds in rotating order, median; the winner's whole 95% bootstrap interval must lie below every other engine's, otherwise the row is unresolved.

A room needing 30 updates a second on a 120 Hz screen, redrawn per second:

| Room | Fuseline 4.3 | Fuseline 4.2 | Fuseline 3.1 | Fuseline 3 | Compose |
|---|---|---|---|---|---|
| while buttons are pressed | 65 | 65 | 3 | 44 | 130 |

## Tests

`ui/fuseline/src/desktopTest`, run on Linux, Windows and macOS in CI, and Fuseline's Android build
checked by `FuselineAndroidTest` in `app/android`:

- **Math**: curves and their slopes against finite differences, springs against their closed forms
  at every damping ratio, velocity handoffs between every pair of motions (`CurveMathTest`,
  `SpringSolverTest`, `HandoffTest`, `MotionMathTest`).
- **Values**: every operation, ownership, gestures, decay, seek, retarget, fuzzed sequences of all
  of them never jumping (`FuselineValueTest`, `RetargetTest`, `ConverterTest`), and colours read
  through Fuseline 4.3's table the same to the bit as through sRGB's curve (`SrgbCodesTest`).
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
