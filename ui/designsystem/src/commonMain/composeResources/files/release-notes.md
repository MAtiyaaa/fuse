# Fuse 0.4.0 - The Speed Release

Fuseline 4. Fuse's animation engine no longer runs motion just because time passes. Every motion in
Fuse is solved in closed form, so where something is at any moment follows from when it started and
where from: Fuseline 4 works a value out when something looks at it, leaves it alone until it could
next change by enough to be seen, and lets a value nobody is looking at rest until it arrives. Fuse
moves exactly as it did; the engine does a great deal less to make it move.

## New

- **Fuseline 4: motion is a function of time, not a job run every frame.** Fuseline 1 gave Fuse its
  own engine, Fuseline 2 stopped retargeting from restarting motion, Fuseline 3 made motion fully
  continuous and Fuseline 3.1 took work off the frames around it. Fuseline 4 asks whether a frame
  needs to touch a value at all:
  - **Worked out when it is read.** A value's position and speed come from its motion's own formula
    as of the frame being shown, exactly what stepping it through every frame would have left,
    without the frames.
  - **Left alone until it could be seen to change.** After each frame a value proves, from its
    motion's formula, the first moment it could move far enough from what is on screen to be seen
    (its event horizon), and the engine leaves it alone until then: the long, slow landing of a tween
    or the last of a spring's settle costs nothing in between.
  - **Resting while nobody looks.** A value whose readers have all been told of its last change, and
    haven't looked since (offscreen, or not drawn), isn't touched again until it arrives. The first
    look brings it back exactly where its motion has reached, at the speed it has: a list scrolled
    away mid-animation and back finds every tile where it would have been.
  - **Shared motion, solved once.** Every value on the same spring shares that spring's solution
    for the moment: solved once a frame for all of them, and carried on from the last frame with a
    few multiplications instead of an exponential, a sine and a cosine. A colour's four numbers or a
    position's two solve their curve once, not once each. A spring's Newton solve for when it comes
    to rest only runs once it could be near rest.
  - **One driver, no coroutine per motion.** Moves due every frame are kept in a list in the order
    they started; the rest wait in a heap ordered by when they are due. Finishing on exactly the
    frame it always did, a move started, retargeted or cancelled anywhere in a frame behaves as it
    always did.
- **The Motion Inspector, in Developer options.** What the engine does every frame: how many values
  are moving, how many were stepped, how many wait for their horizon and how many rest unread,
  springs solved, shared and stepped, curves shared, when the next value is due, and the frame's
  cost against its budget; for every value, how the engine treats it now. Off, it costs nothing.
- **Heat and battery saver ease decoration.** On Android, the device's thermal status and battery
  saver reach Fuseline: an ambient room or a slow drift updates less often while the device runs
  hot or saves power, always at its true time, so it never catches up. What you are doing (input,
  navigation, focus, transitions) keeps every frame and the same motion.

## Changed

- **Fuse moves exactly as before.** Every curve, spring, decay, keyframe, duration, overshoot and
  handoff is the same. Fuseline 3.1 is kept in Fuse's tests as it shipped, and a differential test
  runs Fuseline 4 and 3.1 through the same random histories (every kind of motion, retargets, seeks,
  gestures, takeovers, cancellations, motion-speed settings, moves started inside other moves'
  frames, and frames from 30 to 240 Hz with jitter, drops and stalls) and compares everything a
  caller can see after every step. Positions and velocities agree to the last bit of a float or one
  step either side (where a spring was carried on from the frame before rather than solved afresh);
  arrivals, ownership and order agree exactly; what is drawn agrees exactly, except that a value
  brought back from resting unread may show its next change a frame sooner or later, by at most a
  quarter of its threshold (an eighth of a pixel for a position).
- **Colours that fade redraw, not recompose.** Buttons, menus, chips, tabs, keys, control tiles and
  the Hud, Sync, RomM and Downloads rows read their animated colours while drawing: a focus fade
  redraws the one component instead of composing it again every frame. The startup intro and pages
  fading over the tabs no longer compose or measure again every frame either.
- **Benchmarks against every Fuseline and Compose.** Fuseline 4 is measured against Fuseline 3.1,
  3, 2, 1 and Compose on every workload, drawn, read every frame and unread (see
  [docs/fuseline.md](../fuseline.md#speed) for the method and every row). A thousand springs nobody
  is looking at cost 1.3 µs a frame instead of 173 µs, a thousand tweens 5.9 µs instead of 90;
  springs given a new target every frame take about half the time, values following a finger half.
  Of 111 rows Fuseline 4 is clearly first on 52, within run-to-run noise on 57, and behind on 2
  (both explained there). The numbers come from a computer (OpenJDK on Linux); Fuseline 4 has not
  yet been measured on an Android device.

## Fixed

- **A second screen no longer upsets the first's frame pacing.** Frame intervals are measured per
  screen: the second screen's frames no longer mix into the main window's, which could make Fuse
  think frames were running late and thin decoration for nothing.
- **A game asked of another device is never reported failed as it arrives.** The device asked took
  the same request again and again while the game came over; taken once more just as it landed,
  before its library had found it, the request could fail as it succeeded. Each request is now
  taken once.
- **A system without pack art always gets its panel from its games.** The PlayStation 5 (and any
  system the art pack lacks) could stay bare when its games' art arrived before the system was
  listed; it now gets its panel whichever comes first.
