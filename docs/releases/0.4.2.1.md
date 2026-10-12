# Fuse 0.4.2.1 - The Native Update

A point release for the Native Update: series that look like their games, Moonlight on the Systems
page if you want it there, a check that shadPS4 can draw, and Fuseline 4.1 measured against
Fuseline 4.

## New

- **Series wear their first game's art.** A series on Home and in Search shows its earliest game's
  picture behind its name, darkened so the words read, with that game's logo where one was found.
  No provider publishes a series' own logo, so the first game's stands for it; a series whose games
  have no art yet keeps its own tile.
- **Moonlight on the Systems page, if you want it.** Settings, Addons, Streaming, Show on Systems
  puts Moonlight beside your systems, with every app your computers stream. It is off unless turned
  on, and its options can take it off again.
- **shadPS4 needs Vulkan, and Fuse says so.** Fuse Intelligence looks, by name only, for the Vulkan
  library where Linux and Windows keep it; when shadPS4 is installed and none is there, its check
  says to install the graphics driver's Vulkan support instead of calling it ready. A Flatpak brings
  its own, so it isn't asked.

## Changed

- **Fuseline 4.1, measured.** docs/fuseline.md now has Fuseline 4.1 against Fuseline 4 on every
  benchmark row, with each value's share of a frame in nanoseconds: 1000 springs drawn in 160 µs a
  frame (from 336), 10000 in 2.3 ms (from 5.9), about 2× quicker at every refresh rate from 30 to
  240 Hz, and 1.5 ns a value for springs nobody reads. Of 111 rows, 4.1 is clearly quicker on 45,
  65 are within noise and 4 is quicker on one, all stated.
- **Setup's library step** describes Fuse One in fewer words: all your devices, fetch to play, no
  server.

## Fixed

- **Theme files carry Home's widget look.** The preset files in docs/themes now include each
  theme's widget look (Lit, Tinted or Glass), as the app's own presets do.
- **The Cartridge mark's notice** names the file its mark comes from.

## Acceptance

Tested by the automated suites in CI (Linux, with Windows and macOS packaging) and by rendered
screen audits. Not verified on physical hardware: shadPS4's Vulkan check on a machine without
Vulkan, Moonlight on the Systems page with real computers, and everything 0.4.2 lists as still to
be tried on SteamOS, the Steam Deck, the AYN Thor, Android devices and macOS. Fuseline 4.1 was
measured on a Linux computer only, not on an Android device.
