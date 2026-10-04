# Fuseline

Fuseline is Fuse's animation engine. It is named after the line of a fuse, the wire in Fuse's
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

## Tests

`ui/fuseline/src/desktopTest`:

- Curves and springs are checked against their closed forms, and against the curves and springs
  Fuse used before Fuseline, so moving to it changed nothing anyone can see.
- Oklab round trips.
- Timelines: interpolation, seeking and skipping.
- `Appear` and `Swap` lifecycles.
- A keyed glide following a moving target.
- The import guard (`OwnMotionTest`).
