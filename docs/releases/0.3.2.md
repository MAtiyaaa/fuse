# Fuse 0.3.2 - The Corner Update

## New

- **The menus travel between screens.** On a two-screen device, switching between Fuse and Flipped
  no longer blinks: the menus sink toward the other screen and shrink a little as they go, and
  arrive on it from the edge facing the first, settling with a soft spring, while what takes their
  place rises in behind them. Both screens move as one picture.
- **Hello, whoever is playing.** Switching profile now plays an arrival: the person's avatar
  colours bloom out from the middle of the screen, their avatar springs up with "Hi" and their
  name, and the whole thing gathers itself into their avatar at the top right as their games,
  saves and Home arrive behind it.

- **Each person's own saves.** On a device several people share, Fuse Sync now keeps each
  person's saves apart. When someone else starts a game, the one who played last keeps theirs
  (set aside on the device), and the new player gets their own: the newest from the host, or,
  offline, the one set aside for them. Someone who never played it starts fresh. Memory cards
  shared by every game work the same way.
- **Play One Save Together.** In a game's Save History, a game can be one save for everyone, for
  games a household plays together. Fuse asks whether to start from your save or fresh; play time
  stays each person's own.
- **Saves in many more emulators.** Fuse Sync and Syncthing now find saves in Azahar, Citra,
  Lime3DS and Mandarine (3DS), Eden, Citron, Sudachi, yuzu and Ryujinx (Switch), Cemu (Wii U),
  Xenia (Xbox 360), DraStic, Mupen64Plus and M64Plus FZ, Redream, ePSXe, FPse, Play!, MAME,
  ScummVM, Lemuroid, My Boy!, Pizza Boy and more: over 60 in all. Title ids are read from the
  game itself (a 3DS cartridge's header, a Switch game's tag or NSP ticket, a Wii U game's
  meta.xml).
- **Saves move between different emulators of a system.** A DraStic save reaches melonDS or
  RetroArch on another device and back, and Mupen64Plus's N64 save files and RetroArch's one file
  turn into each other.
- **Save Folders.** Settings, Addons, Fuse Sync (or Syncthing) lists each emulator in your library
  and where its saves are on this device. Those Fuse can't reach come first, each saying what to
  do, and any can be pointed at a folder you chose (an emulator that saves where you tell it, a
  memory stick on a card).

## Changed

- **Syncthing says what it is for people.** Its page, setup and the docs now say it keeps one save
  per game for everyone, while Fuse Sync gives each person their own.
- Emulators Fuse still can't sync (xemu, Winlator and other Windows game apps, small Android
  emulators that keep saves private) each say why, instead of a general "not yet".

- **Who's playing opens gently.** The room settles in from a touch larger, and the people's cards
  rise into place one after another.
- **Whole names on small widgets.** A widget's name, date or figure that doesn't fit is set smaller
  instead of being cut short, so "Continue playing" reads in full even on the small screen.

## Fixed

- Connecting a device to a Fuse Sync host could time out when the host's computer also runs
  Docker, WSL or virtual machines: the host offered an address on one of their networks, which no
  other device can reach. The host now lists its home network address first and leaves those
  out, and a device tries each address the host has and uses the one that answers. When none
  does, it says what to check (the same Wi-Fi, the host's firewall) under the code, instead of
  the network's own error over the Connect button.
- The code "Fuse Sync is ready" showed for adding devices had already been used by the host
  itself while setting up, so a device typing it heard "No device is being added on the host
  right now". The host now shows a fresh code, never one that was used or ran out, and renews it
  while it is on screen.
- The pairing code's boxes cut wide letters such as Q and W short on a small screen. They are set
  a little smaller to fit now.

- On a device several people share, someone starting a game they had never played picked up the
  last player's save, and it could then be kept as their own. Each person now starts with their
  own save, or none.
- Fuse Sync didn't pass a game's serial to RPCS3's, PPSSPP's and Vita3K's save lookups, so their
  saves weren't found for games named by serial. It does now.
- Several Android emulators (DuckStation, Dolphin, Flycast, NetherSX2, Lemuroid) were looked for
  where newer versions no longer keep saves. Fuse now checks the folder you chose first, and says
  when Android keeps the emulator's folder private, with how to move it.

- Home's carousel widgets showed a square corner at the top right of the card in front, where the
  shade behind the widget's name poked out past the card's rounded corner. It is cut to the card's
  shape now.
- On a small screen, a carousel's dots ran into its name and into the game's system chip. The dots
  now sit at the card's top right, and the system chip just under them.
- Jellyfin's widgets stayed empty when they were on a Home page other than the first, and when the
  server didn't answer at first. They now load on every page, and reconnect and ask again soon
  rather than waiting five minutes.
