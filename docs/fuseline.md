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
| 1 tweens | 1.6 us | 2.0 us | 0.81x | 488 B | 504 B |
| 1 springs | 1.6 us | 2.0 us | 0.83x | 488 B | 504 B |
| 100 tweens | 151.3 us | 198.6 us | 0.76x | 48800 B | 50400 B |
| 100 springs | 157.0 us | 191.7 us | 0.82x | 48800 B | 50400 B |
| 1000 tweens | 1718.3 us | 2299.1 us | 0.75x | 488000 B | 504024 B |
| 1000 springs | 1759.3 us | 2320.0 us | 0.76x | 488000 B | 504000 B |
| 100 springs retargeted every frame | 681.6 us | 1319.7 us | 0.52x | 239871 B | 565526 B |
| 100 colour fades | 158.5 us | 201.2 us | 0.79x | 49600 B | 51200 B |

Transitions (`Appear` and `Swap` against `AnimatedVisibility` and `AnimatedContent`, the whole run
including composition and layout):

| Case | Fuseline | Compose | Fuseline / Compose |
|---|---|---|---|
| 60 appearing and leaving, and a page of 120 tiles swapping (6 times) | 346.4 ms | 490.1 ms | 0.71x |

What makes it quick:
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
