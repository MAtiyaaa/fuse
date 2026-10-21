# Fuse 0.4.3.5 - The Overdrive Update

Ports and recomps on Android, each attached to its game, and a Ports system that lists them beside
their own systems, one game with one time played wherever you start it.

## New

- Ports on Android. A port there is an app: a game rebuilt for Android that its project publishes
  as an APK, and that takes your game's files itself the first time it starts. A game's Play With
  now lists the ports that play it, Fuse's checked ones first, then those Open Ports Games lists
  (shown as unchecked). Picking one installs it from its project's GitHub releases, as the Store
  installs apps, and attaches it to the game. From then on Play starts the app in the emulator's
  place, and the time played and Continue Playing stay the game's own. Fuse's checked list starts
  with 13: Gen1Recomp (Pokémon Red to Emerald), Bloodborne-Android, Zelda 64: Recompiled for
  Android, 2 Ship 2 Harkinian, Ship of Harkinian, Starship, SpaghettiKart, Ghostship, Perfect Dark,
  the Goemon 64 and Donkey Kong 64 recomps, Animal Crossing and Zelda 3.
- Recomp or Port, a new Type in Apps. An app you installed yourself can be marked one, and Fuse
  works out which game it plays (by its package, or its whole name), or asks "What game is this?"
  with the likeliest games first. A port Fuse knows by its package is attached by itself when your
  library has one copy of its game. An APK added from Add a Game says which port it is before it
  installs, or asks what it is.
- Addons, Ports on Android: every Android port, with search, a system filter and In Your Library,
  Installed, Checked by Fuse and Everything, and Install, Attach, Play, Open and Uninstall.
- The Ports system. A game played with a port shows in Ports too, with the Ports art on Systems,
  and is still one game: one time played, one set of achievements, one page. Started from Ports,
  it plays with the port; from its own system, with the emulator you chose for that system (or for
  the game). Ports can be Android instead, or off, so the game is only in its own system and plays
  as Play With says. It is one setting for every game (Settings, Addons, Ports), with its own
  value for each system (in the system's settings) and each game (Shows In, in its options).

## Changed

- The Games filter in Apps lists Recomp or Port apps too. A port's menu there has Change, for the
  game it plays, and Detach, which gives the game back to its emulator (as uninstalling it does).

## Fixed

- Nothing fixed in this release.
