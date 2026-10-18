# Fuse 0.4.2.27 - The Native Update

A handheld that sleeps through the night, a cooler Fuse while it sits on screen, backgrounds
without a line between their shades on Linux, and a mouse's back and right buttons on computers.

## New

- On a computer, the mouse's back button goes back, and the right button opens the options of what
  it points at (as Options does on a controller). Neither does anything while you type.
- On Linux, a controller plugged in or turned on works at once: Fuse hears from the system when
  one arrives, instead of looking every two seconds.

## Changed

- **Asleep, Fuse leaves the device alone on Android.** With the screen off, Fuse no longer waits
  for Fuse One's news, looks at who is around, reads the household's lists or Downloads, or checks
  on RomM, Jellyfin and Syncthing. Each of those answered every half minute or so all night, and
  every answer woke the processor and the Wi-Fi again, a game open on it or not. They wait for the
  screen to come back and catch up the moment it does. A save made as the screen goes off is still
  sent, and transfers and art searches carry on (they keep the device awake until they finish).
- **Cooler while Fuse sits on screen.** A moving background held a 120 Hz screen at 120 Hz for as
  long as Fuse was left on, for a room that changes 30 times a second at most. Ten seconds after
  your last press, while only the background moves, Fuse asks for about 60 Hz where the screen can
  switch without a blink (Android 12 and later), and the next press brings the full rate back
  before anything moves. A film keeps the screen's rate.
- Downloading no longer switches off the Wi-Fi's power saving while Fuse is on screen: the
  low-latency Wi-Fi lock only warmed the device, since a busy download keeps the radio awake by
  itself. Before Android 14, Wi-Fi still stays at full speed with the screen off.
- A game being played says so to Fuse One once a minute instead of every 15 seconds (its save is
  still looked at every 15 seconds).
- Paused decoration (while a game runs, or the device sleeps) waits to be told it may move again,
  instead of looking four times a second.

## Fixed

- **Linux: no more lines between the shades of a background.** Every glow, scrim and veil behind
  the interface showed thin rings and lines, one at each step of its colour, on every Linux
  computer and in or out of Steam's Game Mode. Each gradient was dithered as it was drawn, but
  every pane, dim and veil drawn over it at part strength rounded it to 8 bits again without noise
  of its own, and the steps came back as lines. On a graphics card Fuse now draws the whole window
  in 16-bit floating point and rounds it to 8 bits once, with a step of noise, as it reaches the
  screen: a layered room stays within a hundredth of a step of its true colour, where it strayed by
  almost half a step before. `FUSE_DITHER=gradients` goes back to the old way, for comparing.
- On Android, a transfer that could never start (its source signed out) kept the device awake,
  with its Wi-Fi at full power, for as long as it stayed in the list.
- A worn controller stick trembling at rest counted as someone using Fuse: it held the screen at
  its full rate and kept Fuse's moving backgrounds from ever slowing down when left alone.
