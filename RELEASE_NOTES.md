# Fuse 0.4.3.10 - The Overdrive Update

Fuse Tune changes the settings of 46 of the ports in Fuse Ports, up from 3: 5,155 settings in all,
each read from the port's own code at the release Fuse installs, by the names, choices and ranges
the port itself gives them.

## New

- Fuse Tune reads these ports' settings, each from its own code:
  - The N64 recompilations on N64ModernRuntime (17): Zelda 64, Banjo, Dinosaur Planet, Mega Man 64,
    Donkey Kong 64, Bomberman 64, Goemon, Snowboard Kids 2, Mario Kart 64, Star Fox 64, Harvest
    Moon 64, Space Station Silicon Valley, Lamborghini, Aero Gauge, Battlezone, Dinotale and Duke
    Nukem: Zero Hour. Each tab is its own file (graphics.json, general.json, sound.json); those built
    on RecompFrontend show the game's own words and descriptions.
  - The Harbour Masters' ports and those on their libultraship (8): Ship of Harkinian, 2 Ship 2
    Harkinian, Starship, SpaghettiKart, Ghostship, Lighthouse, PaperBoat and BattleShip, every
    setting their menus show, under the menu's own sections, with its tooltip.
  - id Tech 4 and the Quake III engine (9): dhewm 3, openQ4, Quake3e, OpenJK (Jedi Academy and Jedi
    Outcast), iortcw, OpenMoHAA, Lilium Voyager and Spearmint, every console variable the engine
    writes to its config, with its range and description where the code gives them.
  - Two ReXGlue recompilations, Perfect Dark Remaster and Wet, with the SDK's own settings.
  - Fallout 1 CE, Sonic CD, Sonic 1 and 2 (the Retro Engine decompilations), sm64coopdx, Arx
    Libertatis, EDGE-Classic and the Perfect Dark port.
- A port's settings are grouped as its own menus group them.

## Changed

- tools/tune reads each port at the release ports/index.json names (the tag its downloads come
  from), and the code a port takes from other projects (git submodules) at the commit that release
  pins.
- Fuse writes each port's file the way the port writes it: JSON with its keys at the top or inside
  objects, `seta` lines, `name value` lines, TOML, and ini with or without spaces around `=`.
  How a port's file is written comes from Fuse Tune's own data, so a list an older Fuse reads
  never names a form it doesn't know.

## Fixed

- Nothing fixed in this release.
