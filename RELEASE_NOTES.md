# Fuse 0.4.2.7 - The Native Update

Fuse on a Linux TV, made right: a Home background that stays sharp and steady, Jellyfin films you
can hear, controls that can always be put back, Steam's menus that keep the controller to
themselves, a Decky plugin with something to show, and a long list of fixes from the 0.4.2.6 audit.

## New

- **Start and Select, held for three seconds, put the controls back.** From anywhere, whatever the
  mapping: every remapped button, the swapped Confirm and Back, the swapped shoulders and the
  controller type return to how they came, and Fuse says so.
- **The Decky plugin shows your library.** Steam's Quick Access Menu lists your games and systems,
  what's playing, your time this week and the last five games you played, and opens any of them in
  Fuse. Once the plugin is installed, Fuse turns on its side of the connection by itself (unless
  you chose otherwise), and the plugin says when Fuse One isn't set up.
- **Keep this size?** After changing the interface size, or calibrating a screen to a new size,
  Fuse asks whether to keep it, and goes back to the size before by itself after five seconds. A
  size too large for the screen can no longer leave you stuck with every button out of reach.
- **Restore Original Title,** in a renamed game's Details tab, gives the game back its own name.
- **Unpin from Home,** in a pinned game's options. Pinning a game now also shows the Pinned shelf on
  Home if it was hidden.
- **Apply a patch from a file on Linux and Windows,** with a picker that opens in the game's folder
  and lists only patch files.
- **See all on RomM.** Search lists the first RomM games that match, then a row that lists the rest.
- **A log Fuse keeps on Linux,** at ~/.local/share/fuse/logs/fuse.log (the previous session's beside
  it), with the time of every line. When the interface stops answering for two seconds, the log
  says what it was doing, and again when it recovers.

## Changed

- **Home's background stays put.** Moving between Home's widgets no longer drops it to black for a
  moment: the last game's art stays until the next is ready. The art of the shelf you're on is
  fetched ahead, and art too small for a TV is shown softened instead of stretched into blocks.
- **No bands in the background on a 4K TV.** Gradients and the blurred room are drawn with stronger
  dithering on large screens and in Steam's Game Mode, and the room is baked at up to 3840 pixels.
- **Jellyfin films are heard.** Every film's sound is evened out the way a TV does it: quiet speech
  comes up, loud moments come down to meet it, and nothing clips.
- **Menu music on Android sits lower.** The top of the volume slider is now as loud as the middle
  used to be, and the middle a comfortable background level. Linux stays as it was.
- **Text size and Screen edges change the screen they're on.** On a screen you calibrated, Settings
  now changes that screen's own text size and edges, so Large and Extra Large show at once.
- **Enhanced motion is enhanced:** a larger lift on focus, longer slides and a livelier spring.
- **Guided controller setup stops sooner and leaves nothing behind.** A short hold of any button
  stops it, and stopping puts back every control as it was before you started. A mapping that would
  leave Confirm or Back on a button that can't reach Fuse (the Guide button on Linux) is set aside.
- **Steam's menus keep the controller.** While Steam's Quick Access Menu or overlay is open in Game
  Mode, Fuse ignores the controller instead of moving behind it.
- **Choosing Xbox, PlayStation or Nintendo labels only changes the labels.** The buttons keep doing
  what they did.
- **Fuse's logo in Steam's side menu** sits inside its space instead of filling it; the art Fuse
  placed before is replaced by itself.
- **The Store knows emulators installed outside it.** Flatpak and AppImage emulators (Azahar too)
  show their version, so the Store can offer an update.
- **Focus on the desktop lifts with a neutral shadow,** as on Android, instead of one tinted by the
  game's colour.
- **The Linux AppImage runs on Java 21** with its generational collector, for fewer and shorter
  pauses while animating.
- **A game's options fit their tabs.** With four tabs, the tabs show their icons, and the name of
  the one you're on.
- **Rescanning says what it found,** in Library, Systems and Settings.
- **The RomM page says Connected at home only for a server at home,** and Uploads shows On or Off.
- **Page Up and Page Down move along Home three shelves at a time,** and the mouse wheel scrolls
  Home smoothly.

## Fixed

- Renaming a game no longer wipes its details and art, and Reset Name and Details undoes a rename.
- Find Details says what it found.
- Downloaded RomM games keep RomM's cover, logo and screenshot instead of showing initials.
- System counts (the Systems row, Show games from) include the games on RomM.
- Enter in Search opens the first result with a keyboard, and moves into the results otherwise.
- The Video previews setting is hidden where previews can't play, instead of showing On.
- Settings, Home now reaches the top line when you press Up from its first row.
- Store cards no longer cover each other when focused.
- Also installed on a game's page no longer lists Linux scripts and shortcuts as emulators.
- Words are no longer broken across two lines in a narrow window ("Ro" over "mM").
- Hiding a game says where to find it again.
- The controller's diagram in Controls fits a short window.
