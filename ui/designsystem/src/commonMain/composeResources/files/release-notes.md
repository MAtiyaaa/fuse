# Fuse 0.3.4 - The Unity Update

One Fuse on every device and with every controller. This release went through Fuse Sync,
Syncthing, input, tabs and small screens looking for anything that could lose a save, trap you in
a menu or say the wrong thing, and fixed what it found.

## New

- **PICO-8 and Flash games on the computer.** Fuse finds PICO-8 and Ruffle on Linux, Windows and
  macOS, and RetroArch's Retro8 core for PICO-8, and starts games in them the way ES-DE does.
  Every system now has something to play it with on every desktop.
- **Hints follow your button mapping.** Map Confirm to another button and every hint line shows
  that button, not the standard one.
- **Mapping a button trades jobs.** In Controls, giving a button a new action hands its old one to
  the button that had the new one, and mapping it back undoes the trade. A mapping that would
  leave the pad with no Confirm or no Back is refused, with a message saying why.
- **The column beside the Sync and Syncthing tabs is reachable with a controller.** Devices, play
  time by device and recent activity used to run off the screen with no way down except touch.
  Right from the list moves into that column, Up and Down scroll it, Left or Back returns.
- **Back calls off a slow save check.** While Fuse Sync or Syncthing checks the save before a
  game, the launch screen says so and Back cancels the launch. Once the emulator starts, nothing
  interrupts it.

## Changed

- **Who's playing? never holds Fuse hostage.** At startup it can always be closed while the host
  isn't answering (Back reads Not Now). With the host away, a profile without a PIN switches at
  once and catches up later; one with a PIN says only the host can check it.
- **A host never touches files it didn't make.** Set up in a folder that already holds other
  things, a host keeps its files in a Fuse Sync Host folder of its own there, and Delete This Host
  or moving its saves only ever deletes or moves the host's own files.
- **Syncthing leaves your own setup alone.** Adding a device Syncthing already knows keeps its
  name, addresses and settings, removing one that also shares folders you set up by hand only
  takes it off Fuse's save folders, a save folder inside one already shared is never shared
  twice, and an emulator's folders are never merged into something as broad as your home folder.
- **Nintendo controllers confirm with A everywhere.** On Linux, Nintendo pads are read by their
  labels, as on Windows, macOS and Android. With a PlayStation or Xbox pad in hand, hints put
  Confirm on its bottom button even when the handheld's own glyphs are Nintendo's.
- **Small screens show what you're choosing.** On a 4:3 handheld, Who's playing? scrolls and puts
  the PIN pad beside the person, and Fuse Sync setup keeps the selected row and the code field in
  view.
- A save folder Syncthing shares with no device yet says Only here instead of Up to date.
- Durations read 9 h rather than 9 h 0 min everywhere.

## Fixed

- A restored save could be undone by the next launch on another device. It now goes up as the
  newest save and sticks everywhere.
- Copies kept for safety (the other side of a conflict, what was there before a restore) could
  become a game's newest save. They stay history only.
- Pinning a conflict copy made it the newest save, and unpinning one turned it into a played save.
  Keeping a save for good is now its own mark.
- A save sent the moment a game started could overtake the save it was made from and turn into a
  conflict. A save now waits for the one it came from.
- A save waiting to be sent could be dropped when the host asked for a PIN, a clock fix or a
  pause. Only a save the host can never take leaves the queue.
- A proxy's error page or a Wi-Fi sign-in page counted as the host refusing the device. Fuse now
  tries the next address and shows the device as offline.
- Saves that never reached a host you forgot are kept as plain files instead of disappearing.
- Turning records off on one device deleted the profile's collections on every device.
- A game known by a serial on one device and by its title on another could have its whole play
  time counted again as one device's own.
- Deleting a host set up in a folder that already held other things deleted all of them.
- The wrong-password wait could be dodged by sending proxy headers, and everyone behind one
  tunnel shared a single call allowance.
- Keeping the other device's version in Syncthing could lose this one if the swap failed.
- A tab switched to quickly while the one before was still sliding in could stay faded part way.
- On the computer, a video could lose its size and length as it opened, and switching quickly
  between videos could show the one before's error on the new one.
- On the computer, seeking back in a video and pressing play straight away could leave it paused
  where you sought to, because the old end of the video still counted for a moment.
- On the computer, switching videos quickly the first time the player started could leave every
  video after it with no sound and never ending, until Fuse was restarted.
- On Windows, a program's own name was read wrongly from a path with backslashes.
- A failed export left a half-written file next to your files.
- Setting up a host used a switch that looked unlike every other switch in Fuse.
