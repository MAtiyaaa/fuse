# Fuseline by Fuse

Fuseline by Fuse is Fuse's animation engine. It is named after the line of a fuse, the wire in Fuse's
logo that carries the spark. Every animation in Fuse runs on it, from a focus lift to the
setup's opening sequence.

It lives in `ui/fuseline` (package `io.github.matiyaaa.fuse.ui.fuseline`).

## What it owns

Fuseline's code covers the whole of an animation:

- **Curves** (`Curve`, `CubicCurve`, `Curves`). A cubic Bézier curve is solved exactly: Newton's
  method, with bisection where the curve flattens.
- **Springs** (`Spring`). The damped oscillator is solved in closed form for under-, critically
  and over-damped springs. A spring retargeted mid-flight keeps its speed. A bouncy spring is
  done once its decaying envelope is within the threshold, so an overshoot is never cut short.
- **Tweens, snaps and repeats** (`Tween`, `Snap`, `Repeating`).
- **Converters** for floats, lengths, positions, sizes, rectangles and colours. Colours blend
  through Oklab, so a fade stays even instead of dipping grey.
- **Animated values** (`FuselineValue`). A new move takes over from the one under way.
- **State helpers**: `fuselineFloat`, `fuselineColor`, `fuselineDp`, `fuselineInt`, `fuselineOffset`.
- **Entrances and exits** (`Appear`, with `fadeIn`, `slideIn…`, `scaleIn`, `expand…` and their
  exits).
- **Content swaps** (`Swap`, `Crossfade`, with `togetherWith` and `SizeTransform`).
- **Loops** (`rememberLoopClock`).
- **Scrolling** (`fuselineScrollBy`, `fuselineScrollTo`).
- **Choreography** (`Timeline`, `rememberTimelinePlayer`).
- **Gliding indicators** (`rememberGlide`). Given a key, a glide only travels when the key
  changes; when the same item moves because of layout or scrolling, the indicator stays on it.
- **Fuse's motion profile** (`FuselineMotion`, `Durations`, `MotionLevel`). `Fuse.motion` returns
  it, scaled to the motion choice in Accessibility.

From Compose, Fuseline takes only:
- the frame tick (`withFrameNanos`);
- the system's animation speed (`MotionDurationScale`);
- the policy for endless animation (`InfiniteAnimationPolicy`);
- layout and the graphics layer to draw the result.

## The one bridge

Lazy lists move their items into new places themselves, and they only accept a Compose spec for
it. `FuselineBridge.placement(motion)` hands them a spec whose every number comes from Fuseline's
own tracks.

It is the only file in Fuse allowed to import `androidx.compose.animation`.
`OwnMotionTest` fails the build if anything else does.

## Writing motion

Start from the profile, so the person's motion choice applies:

```kotlin
val motion = Fuse.motion
val lift by fuselineFloat(if (focused) motion.focusScale else 1f, motion.focusSpring())
val tint by fuselineColor(if (on) c.accent else c.text, motion.tween(Durations.FAST))

Appear(visible, enter = fadeIn(motion.fade()) + slideInVertically(motion.enter()) { it / 4 }, exit = fadeOut(motion.exit())) {
    Toolbar()
}
```

A choreography is a timeline: named tracks of keyframes, each reached along a curve.

```kotlin
val intro = Timeline(3_200) {
    track("trace") { at(0, 0f); at(1_100, 1f, Curves.Standard) }
    track("spark") { at(1_100, 0f); at(1_520, 1f, Curves.Enter) }
}
val play = rememberTimelinePlayer(intro, reduced = Fuse.motion.reduced)
Canvas(Modifier.fillMaxSize()) { drawMark(trace = play["trace"], spark = play["spark"]) }
```

## Speed

Fuseline is measured against Compose's own animation engine under the same conditions: one
thread, one manual frame clock, the same number of values and frames, both warmed up and each
measured twice in turn. Time and memory are per frame, once every value is moving
(`FuselineBenchmark`, `TransitionBenchmark`; run them with
`./gradlew :ui:fuseline:desktopTest -Pfuse.bench=true`). Lower is better; the ratio is Fuseline's
time over Compose's.

| Case | Fuseline | Compose | Fuseline / Compose | Fuseline memory | Compose memory |
|---|---|---|---|---|---|
| 1 tweens | 1.7 us | 2.4 us | 0.71x | 520 B | 528 B |
| 1 springs | 1.9 us | 2.0 us | 0.94x | 520 B | 528 B |
| 100 tweens | 14.9 us | 214.0 us | 0.07x | 4480 B | 50424 B |
| 100 springs | 19.6 us | 206.3 us | 0.10x | 4480 B | 50424 B |
| 1000 tweens | 145.1 us | 2848.0 us | 0.05x | 40480 B | 504024 B |
| 1000 springs | 203.5 us | 2634.4 us | 0.08x | 40480 B | 504024 B |
| 100 springs retargeted every frame | 588.3 us | 1409.4 us | 0.42x | 192697 B | 553538 B |
| 100 colour fades | 19.9 us | 208.4 us | 0.10x | 5280 B | 51224 B |

Transitions (`Appear` and `Swap` against `AnimatedVisibility` and `AnimatedContent`, the whole run
including composition and layout):

| Case | Fuseline | Compose | Fuseline / Compose |
|---|---|---|---|
| 60 appearing and leaving, and a page of 120 tiles swapping (6 times) | 315.1 ms | 569.4 ms | 0.55x |

What makes it quick:
- Every move on a frame clock shares one frame callback ([FrameDriver]). A move hands the driver
  its frame work and waits once, until it ends; the driver waits for each frame once and steps
  them all. A hundred values moving cost one frame wait instead of a hundred, which makes a
  screen full of motion (a page of tiles rising in, a grid shuffling) about ten times quicker per
  frame than Compose, with a tenth of its memory. Endless loops keep their own frames, so tests
  and screenshot tools still see them as endless.
- Curves are solved from a table of samples and a few Newton steps, not a long search.
- Springs are solved in closed form, without allocating. The end of a calm spring is found in
  strides and then to the millisecond.
- A value writes its frame straight from its own buffer, with no copy per frame.
- A new move takes over from the one under way without waiting for it to unwind, and ends it
  with an exception that carries no stack trace. A value following a finger or a scroll, given a
  new target every frame, costs about half of Compose's and a fraction of its memory.

## Tests

`ui/fuseline/src/desktopTest`:

- Curves and springs are checked against their closed forms, and against the curves and springs
  Fuse used before Fuseline, so moving to it changed nothing anyone can see.
- Oklab round trips.
- Timelines: interpolation, seeking and skipping.
- `Appear` and `Swap` lifecycles.
- A keyed glide following a moving target.
- The import guard (`OwnMotionTest`).
- The benchmarks above, which also check that Fuseline is never slower than Compose.
