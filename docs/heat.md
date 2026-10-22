# Heat and battery: what Fuse does that costs power

An audit of everything Fuse runs that could warm a device or drain its battery, in four moments:
asleep, opening Fuse, the first start, and a game running. It covers Android (the AYN Thor and other
two-screen handhelds first), Linux, Windows and macOS. Each item says what runs, how often, and
whether it was already held back or is fixed in 0.4.3.9.

How it is held back: the Performance Governor (`PerformanceGovernor`) knows whether Fuse is in use,
idle, behind another window, playing a game or asleep, and `EnginePolicy` says what each allows.
Decorative motion is paused while a game runs, while Fuse is behind another window and while the
device sleeps (`FramePacing.decorationRate`). Art and detail lookups and emulator detection wait
while a game runs.

## A game running

On a two-screen handheld Fuse stays on the second screen while the game plays on the first
(`CompanionActivity`), so whatever it draws there competes with the game for the same processor
and graphics.

| What | How often | State |
|---|---|---|
| Fusi (the animated pet) on the second screen | Asked for every display frame (120 a second), drew 30 | **Fixed in 0.4.3.9.** It took every frame of the display through its own loop, which the decoration pause didn't reach, so it kept the second screen drawing under every game. Now paced like other decoration (`decorationFrames`): no frames at all while a game runs. |
| Every decorative loop clock (shimmers, glows, sweeps: `rememberLoopClock`) | Every display frame | **Fixed in 0.4.3.9.** Paused loops still asked for every frame only to skip it, which woke the processor at the screen's full rate for as long as a game ran. A paused decorative loop now takes no frames until decoration may move again. |
| Ambient backgrounds and the drifting hero art | Up to 30 a second | Already paused while a game runs (`decorationFrames`). |
| Spinners for work that carries meaning (a download, a sync) | Every frame, only while that work shows | Kept: they say something is happening. Shown only while it is. |
| Fuse One's look at the save while playing | Every 15 s: reads the save files' sizes and times | Kept: cheap, and it is what sends a save made mid-game. |
| Fuse One's presence ("still playing") | Once a minute | Kept. |
| The performance card on the second screen | Once a second, only while shown and only when turned on | Kept, opt-in. |
| Refresh rate of Fuse's windows | Full rate only while something moves; released after 3 s still; calmer near 60 Hz after 10 s of decoration alone (`RefreshHold`) | Already in place. |
| Menu music | 30 ms fade ticks only while fading | Stops ticking once settled, and pauses when silent. |
| Art lookups, emulator detection | None while playing | Held by the governor. |

## Asleep (lid closed, screen off)

| What | State |
|---|---|
| Decoration, art lookups, detection | Paused while asleep and for 8 s after waking (`SUSPENDED`, `RESUMING`). |
| Wakelock for downloads and uploads (`AndroidStayAwake`) | Held only while that work runs; capped at 6 hours per hold. |
| Save sent when the lid closes mid-game (`PlaySyncService`) | Awake for at most 30 s, once. |
| Screen-on flags | The second screen lets the screen sleep while Fuse is away or resting (`CompanionPresentation.setAway`). |
| The governor's own tick | Once every 5 s, to notice a wake. |

## Opening Fuse

| What | State |
|---|---|
| Decorative loops | Now take no frames while paused (fixed above); thin out under heat or battery saver (`pressureEvery`). |
| Art for what is on screen | Waits until the person stops moving for 1.5 s (`awaitQuiet`). |
| Refresh rate | Full only while something moves (`RefreshHold`). |

## First start

| What | State |
|---|---|
| Finding every game's art and details | Waits for a quiet moment and stops while a game runs; resumes where it was. |
| Emulator and app detection | Once, then on change; held while a game runs. |
| Android compiling Fuse (ART) | Out of Fuse's hands; the first scroll's art work waits for quiet so the two don't stack. |

## Computers (Linux, Windows, macOS)

The same composition code runs there, so both fixes above apply: a game running with Fuse behind it
no longer keeps Fuse's window asking for frames for paused decoration. The desktop app's own threads
are a once-a-minute frame report and a render trace that runs only when turned on for debugging.

## What 0.4.3.9 can't settle from here

The second screen itself stays lit while a game runs, showing the game's art and status. On an OLED
panel that costs some power whatever Fuse draws. Turning the second screen off while playing is a
setting to add if the fixes above aren't enough on the Thor; measured temperatures before and after
on the device are what decide it.
