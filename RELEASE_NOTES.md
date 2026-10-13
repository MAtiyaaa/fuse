# Fuse 0.4.2.6 - The Native Update

One Save Sync for each device, Fuse One saves that really move, and a setup that never sends you
somewhere that can't work: Steam steps that know when Steam isn't there, a step counter that holds
still, the host screen that fits a small window and game folders before anything else.

## New

- **One Save Sync for each device.** Fuse One, RomM or Syncthing moves this device's saves, or none
  does. Two of them moving the same files would fight over them, so choosing one turns the others'
  save sync off here. It is a choice for this device only: turn it off on one device and every
  other device in your account keeps syncing. Choose in setup, or in Settings under Save Sync
  (also reachable from Fuse One, RomM and Syncthing). Existing setups keep what they used.
- **Saves made outside Fuse are sent too.** A game started from Steam, the emulator's own menu or a
  shortcut used to keep its saves on the device until Fuse launched that game itself. Fuse now
  looks over the saves it knows about every few minutes and sends the ones that changed, once
  they've settled for a minute.
- **A game with no one chosen says so.** On a device in a household with nobody picked, a save that
  couldn't go anywhere now says to choose who's playing instead of staying behind silently.
- **RomM tells you when a save didn't go up,** with the reason, instead of only writing it in the log.
- **RomM Saves in Addons.** With RomM moving this device's saves, Addons shows them the way Sync
  shows Fuse One's: every game with saves on RomM, the newest one, which device it came from and
  when, and whether this device's save is in step, newer here or newer on RomM. Open a game for
  every save RomM keeps of it. RomM keeps saves only; profiles stay with Fuse One.
- **Saves on This Device,** in every game's options: the save files its emulator keeps here (a
  RetroArch .srm, a memory card, save states), with their size, time and place, with or without
  any save sync.
- **The host keeps up by itself.** A computer hosting Fuse One in the background starts again on
  the new version as soon as Fuse is updated on it, and on Linux it updates itself when your
  devices already run a newer Fuse, so nobody has to open Fuse on that computer. Your devices say
  when the host runs an older Fuse, and how to update it (a Docker host too).

## Changed

- **Fuse One with RomM, or Fuse One with Cartridge.** After choosing Fuse One in setup, RomM (the way
  we recommend) or Cartridge can come along too. RomM and Cartridge still never go together.
- **Settings search works as you type.** With a keyboard, typing in the search row lists every
  matching setting at once, Enter goes to the first, and no separate dialog opens. A controller
  still gets the on-screen keys.
- **RomM in Settings.** Addons, RomM starts with Sign In to RomM, and searching for RomM finds it.
  RomM needs nothing else; Cartridge is only its separate companion app.
- **~/Games is found by itself,** with a ROMs folder inside it, and Documents/ROMs, on every system.
- **Setup's order.** Game folders, emulators and your games come first; Play on, Anywhere (Fuse One)
  now follows them, so making this computer the host never runs before Fuse knows your games, and
  setup no longer leaves for the host screens partway through.
- **The step counter holds still.** Setup counts its five chapters ("Your games, part 2 of 5")
  instead of a step total that grew and shrank with each answer, and the line along the top only
  moves forward.
- **Play on, Anywhere shows the three ways saves can move,** Fuse One, RomM and Syncthing, with
  Use Fuse One first and the others one press away.
- **The emulators step says what's still missing** ("GB needs an emulator") or that you're ready to play.
- **The artwork step checks your SteamGridDB key** and says whether it works, was turned down or
  couldn't be reached, with a way to change it.
- **The controller step offers Detect first only when a controller is in your hand.**
- **Menu music starts with setup,** on the Welcome step, instead of after a profile was made.
- **Downloaded RomM games keep RomM's details** (year, genres, developer, summary), filling only
  what the game didn't have.
- **Ctrl+A (Cmd+A on a Mac) selects all** in text fields.
- **Choosing a button in Controls looks the part.** The window that waits for a press shows the
  action's icon and name in large type, a controller with its current button marked, a countdown
  ring and a Cancel button, instead of thin lines of text.
- **The Guide button does nothing on Linux.** The button in the middle of the controller no longer
  takes you Home there, so it never fights the system or Steam, and Controls no longer offers it.

## Fixed

- **Setup without Steam.** When Steam isn't installed, "Not now" is chosen on the Steam steps
  instead of "Yes, integrate with Steam", the Decky step's "Not now" comes before "Look again", and
  "Yes, add Fuse to Steam" is only offered when Fuse can really add itself (it said nothing and did
  nothing before). When it can't, the step says why. An error adding Fuse to Steam is shown, not
  only logged.
- **"Make this computer the host" fits the window.** Its button was cut off in Fuse's own first
  window (1216 by 760).
- **Fuse One save sync.** Computers that played games outside Fuse never sent their saves; they do
  now. A save that met a newer one made by an older Fuse no longer leaves a conflict copy behind on
  every play. A blank save is never sent as the first save of a game. Saves follow this device's
  Save Sync choice everywhere they move: before a game starts, after it closes, while it runs and in
  the background.
- **No display.** Starting Fuse without a display says so in one line and exits, instead of a crash
  report.
- **No sound output.** Menu music waits quietly when there is nothing to play through and starts by
  itself once there is, instead of failing three times with an error each time.
- **Games no longer fail when Fuse's usual RetroArch core isn't installed.** On a computer Fuse
  plays each game in a core RetroArch really has (Nestopia when FCEUmm isn't there), uses the
  system's own emulator when RetroArch has no core for it (standalone mGBA for GBA), and otherwise
  says which core to download instead of RetroArch's own error. A core folder set in retroarch.cfg
  is found too.
- **Art is found for far more games.** Short or run-together file names ("Toy Story 3",
  "MEGAMAN2", "mariorpg", "SMB3", "JSRF - Jet Set Radio Future") and names that only add an edition
  or a platform now find their game, and Libretro's names with a "!" ("Metal Gear Ac!d") match. On
  a real library of 310 games, the games waiting for you to choose art went from 15 to none.
- **Artwork for games with edition words in their names** ("Pokemon Red Version", "Game of the
  Year") is found when the name is otherwise the same.
- **Player logs never include a server's key** in a stream's address.
- **A shell script that starts games** is no longer named as a game's missing emulator.
- **A cancelled download** shows that it was cancelled instead of a stuck percentage.
- **A game from RomM is linked to RomM** as soon as it is in the library, so its page never shows
  it as unlinked for a moment.
- **A Fuse One address typed in setup** shows the host by its name once found, and finds it by the
  address alone when it doesn't answer to the nearby search.
- **The controller works again after Controls.** Finishing or stopping guided setup, leaving the
  button test, or leaving Controls by any way (touch, mouse or controller) left the controller
  answering only to that page, so it did nothing until you went back. It now returns to Fuse at
  once. A direction while choosing a button cancels the choice.
- **After a file picker, the keyboard and controller reach Fuse again** without clicking the window.
