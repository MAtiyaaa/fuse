# Fuse 0.4.3.8 - The Overdrive Update

Fuse Ports grows from 5 games to 147 on a computer and 116 on Android, each checked every night on
the system it's for.

## New

- 142 more ports on a computer, for 147 in Addons, Ports: 143 with a Linux build and 128 with a
  Windows build. Each is the program its own project publishes, and what it needs from your game,
  where it keeps your saves and how it starts are read from that project's own code at the release
  listed, and cited. Among them: Ship of Harkinian, 2 Ship 2 Harkinian, Starship, SpaghettiKart,
  Ghostship and sm64coopdx; vkQuake, Quake3e, Woof!, Nugget Doom, DSDA-Doom, EDGE-classic and
  PsyDoom; OpenJK, iortcw, OpenMoHAA and dhewm 3; Arx Libertatis, fheroes2, VCMI, Julius, CorsixTH
  and Daggerfall Unity; the Sonic CD and Sonic 1 and 2 decompilations; X-Wing, TIE Fighter, X-Wing
  vs. TIE Fighter and X-Wing Alliance; and recompilations from Super Mario World and A Link to the
  Past to Lost Odyssey and Kameo.
- 103 more Android ports, for 116, each APK read by Android's own tools: signed so Android installs
  it, the app the list names, and with code for an arm64 device.
- A port that asks for your game itself on its first start (most recompilations do, with a file
  picker or their own importer) says what it asks for and where it keeps it.

## Changed

- The nightly check of Fuse Ports also runs every port's Windows build on Windows, and reads every
  Android port's APK. Only a build that passed is marked Verified.
- What a port writes beside itself (its saves and settings, for the ports that keep them there)
  outlives an update, and its own folders can live in its home.
- A port whose build for one system can't run there as its project publishes it (it needs a newer
  system library than most have) is offered on the other system only, and says so. A port that
  takes a library from your system names it.

## Fixed

- A port whose zip was made on Windows couldn't start on Linux or macOS: such a zip keeps no Unix
  permissions, so its program wasn't runnable (Quake3e's). Fuse now makes the program the list
  names runnable when it installs it.
- Opening Ports as Fuse started could read its list twice at once, each read putting back a copy an
  interrupted update had left aside. It is read once now.
