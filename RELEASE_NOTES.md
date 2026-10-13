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

## Changed

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
- **Artwork for games with edition words in their names** ("Pokemon Red Version", "Game of the
  Year") is found when the name is otherwise the same.
- **Player logs never include a server's key** in a stream's address.
- **A shell script that starts games** is no longer named as a game's missing emulator.
- **A cancelled download** shows that it was cancelled instead of a stuck percentage.
- **A game from RomM is linked to RomM** as soon as it is in the library, so its page never shows
  it as unlinked for a moment.
- **A Fuse One address typed in setup** shows the host by its name once found, and finds it by the
  address alone when it doesn't answer to the nearby search.
- **After a file picker, the keyboard and controller reach Fuse again** without clicking the window.
