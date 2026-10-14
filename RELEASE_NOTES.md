# Fuse 0.4.2.10 - The Native Update

A cooler, longer-lasting Fuse on Android, and a startup animation that plays smoothly from its first
frame, right after an update too.

## New

- **Fuse's updates arrive ready to run fast on Android.** Installed from a file, an app gets no
  compiled code until the phone next sits idle on its charger, often overnight; until then it runs
  slowly, hot and hungry, and the first start after an update stutters. When Fuse updates itself, it
  now hands Android the list of its code it uses most along with the update (as Play does for its
  apps), and Android compiles that code as it installs. This starts with the update after this one,
  since the version doing the installing is the one that hands it over. Should a phone refuse it,
  the installation is tried again without it.

## Changed

- **The screen slows down when Fuse is still, on phones that can.** Fuse asks for your screen's
  highest refresh rate, and that request fixed the screen at it even over a still picture, so phones
  whose screens drop to a few refreshes a second when nothing moves (LTPO screens) never could. After
  three seconds with no input and nothing moving, Fuse lets the request go; the next button, touch or
  animation asks for it again before anything moves, so movement always gets the full rate.

## Fixed

- The startup animation stuttered on the first start after an update. Its first frames carry the
  whole interface being built underneath, and its clock ran on through them, so the spark jumped
  ahead in steps; it now starts once frames come steadily, so every beat plays. It also stopped
  measuring the brand's lines again on every frame, and once more for every ember. Every frame looks
  exactly as it did.
