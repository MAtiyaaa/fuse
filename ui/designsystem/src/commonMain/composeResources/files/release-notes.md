# Fuse 0.4.3.8 - The Overdrive Update

Fuse Ports grows from 5 games to 146 on a computer and 116 on Android, each checked every night on
the system it's for: that it installs and starts there, or on Android that Android would install it.
No check plays a game, which needs your files.

## New

- 141 more ports on a computer, for 146 in Addons, Ports: 142 with a Linux build and 128 with a
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
  Android port's APK. Only a build that passed is marked Verified. A computer port passes when its
  build, the list's file to the byte, unpacks as Fuse unpacks it and starts without your game: it
  prints its version, is still running after a few seconds, or ends by itself (most end saying the
  game's files are missing), never by a crash or a library the system lacks. An Android port passes
  when its APK is signed so Android installs it, is the app the list names and has code for an arm64
  device; it isn't started. On a pull request, a port that doesn't pass fails the check.
- What a port writes beside itself (its saves and settings, for the ports that keep them there)
  outlives an update, and its own folders can live in its home.
- A port whose build for one system can't run there as its project publishes it (it needs a newer
  system library than most have) is offered on the other system only, and says so. A port that
  takes a library from your system names it.
- On Android, where you open a game decides what plays it: from its system's page, its emulator;
  from Ports, its port; from your library, whatever played it last, until Play With picks again.
- On Android, an app marked Recomp or Port needn't play one of your games. One that plays none is a
  game of its own in Ports, and Choose Its Game in its options plays it in a game's place.

## Fixed

- A port whose zip was made on Windows couldn't start on Linux or macOS: such a zip keeps no Unix
  permissions, so its program wasn't runnable (Quake3e's). Fuse now makes the program the list
  names runnable when it installs it.
- Opening Ports as Fuse started could read its list twice at once, each read putting back a copy an
  interrupted update had left aside. It is read once now.
- On Android, a game with a port attached could name its emulator on the game page and start the
  port anyway, or the other way round. The page now names what Play starts.
