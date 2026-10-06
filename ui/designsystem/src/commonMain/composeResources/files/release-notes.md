# Fuse 0.3.6 - The Unity Update

Everything in one place: RomM built into Fuse, one Downloads page for every transfer,
films and episodes kept for offline, streaming from a computer at home, and saves that reach every
device in the house by themselves. Fusi has a friend too.

## New

- **Fuse RomM, Fuse's native RomM integration.** Pair Fuse with a RomM server by approving it in
  RomM or with a pairing code, and Fuse keeps a read-only or upload-capable token, revocable in
  RomM, in its secure storage (a server without tokens takes a sign-in, kept there too, never in
  its database, logs or exports). It reads what the server can do from the server itself, keeps
  an offline copy of its library brought up to date a page at a time, and reaches it at home,
  from outside, or both, never waiting on an address that doesn't answer. RomM gets its own
  Addons tab with system and collection grids, and its games open in Fuse's game page with their
  discs, updates and DLC.
- **RomM games land in the folders you already have.** Downloads go into the system folders Fuse
  already uses. A RomM game is matched to the one in your library by its hash, the console's own
  title id, the exact file name, or the same name with the same region and revision when it is
  the only one, never by a name that only looks alike. Your games can go up to RomM too, in
  pieces, resumed where they stopped.
- **BIOS from RomM.** Missing firmware on a health page and each system's BIOS menu offer
  Download from RomM. A file you already have is never replaced by one of the same name; firmware
  an emulator installs itself is put in a Firmware folder with how to install it.
- **RomM in Search.** Search lists the RomM games you haven't downloaded yet, from the offline
  copy, so it works without the server.
- **"Do you use RomM?" in setup.** First-run setup asks, and offers Fuse RomM, Cartridge or
  neither. People who already finished setup are never asked again. Cartridge is still fully
  supported; turning one on turns the other off in Fuse, keeping both set up.
- **One Downloads page.** The Downloads button opens every download and upload Fuse makes, from
  RomM, Jellyfin, the Store and the rest: up to five each way at once, kept across restarts,
  resumed where they stopped, checked before they are put in place, waiting for a missing drive
  or the network. Filter them, reorder the queue, pause, retry or cancel each. On Android they
  carry on in the background with a quiet progress notification, and ones kept to Wi-Fi follow
  the connection.
- **Jellyfin downloads.** Options on a film, episode, season or show offers Download: Fuse keeps
  the server's original file, the subtitles beside it and the pictures, in tidy Films and Shows
  folders. Hold Options to play the downloaded copy; where you stopped is sent to the server once
  it can be reached. The Downloaded page plays everything kept without the server, and deletes it
  or moves it to another drive, several at once too. A server that doesn't allow downloads is
  respected.
- **Streaming from a computer at home.** Add the computer running Sunshine (or Apollo, or
  GeForce Experience) in Addons, Streaming. Fuse reads its open GameStream port to name it and
  learn how to wake it, and never pairs with it or changes its settings. Choosing an app wakes a
  sleeping computer, waits with a countdown, then starts Moonlight on that app. A computer that
  doesn't answer or wake says why and offers to try again.
- **Syncthing-Fork in the Store.** Every edition's Store offers it. Getting it from Syncthing's
  setup goes to its Store page, and once it installs Fuse goes back to setup and starts it.
- **Saves synced everywhere.** A save made on any device now reaches every other device that has
  the game, in the background, without launching it. Devices that were off catch up when they
  come back, new devices catch up when they join, and a game being played or a folder holding
  another person's save is never written. Each game's Fuse Sync page says "Synced everywhere" or
  "4 of 5 devices current", with each device's state.
- **Restore Fuse Default Art.** A system's options and Settings, Systems, System art put Fuse's
  own icon, background and logo back for one system or every system. Downloaded and chosen art is
  set aside, not deleted, nothing is downloaded for it by itself afterwards, and Undo puts back
  exactly what was there.
- **Screenshots and recordings on computers.** L3 + R3 on a controller, F12 for a screenshot and
  Shift+F12 to start or stop a recording, on Linux, Windows and macOS. Screenshots go to
  Pictures/Fuse at the screen's full sharpness; recordings to Videos/Fuse (Movies/Fuse on a Mac)
  as MP4, without sound for now.
- **Fusi has a friend.** Bo, a white pixel dog with a black collar and a small gold tag, shares
  her room in the Fusi theme. He watches her, trots after her when she runs, says hello when she
  comes close, and naps beside her house when she sleeps.

## Changed

- **Each handheld emulator's saves where its own code puts them.** melonDS follows the save
  folder in its settings, NooDS its separate saves folder, mGBA its savegamePath, Mednafen's saves
  are found by name and fingerprint, and My Boy and My OldBoy use their own folders. melonDS on
  Android warns when a game is outside its own ROM list. Every save spot now says how sure Fuse
  is: found, known, or a guess for emulators that don't publish their layout.
- **Fuseline 2.** A spring following a finger, a scroll or a selection now takes each new target
  in place instead of starting a new move. With 100 values retargeted every frame a frame costs
  25 us instead of 414 us, and with 1000 values 267 us instead of 4.4 ms, about 16 times faster
  with an eighth of the memory. That is the case it was built for; other moves cost what they did.
- Settings has a Downloads section, and Storage lists what Jellyfin keeps offline.

## Fixed

- Logs, caches, temporary files and system leftovers (Thumbs.db, desktop.ini and the like) in a
  save folder could be taken for a change to the save; they are never part of a save now.
- melonDS, NooDS and mGBA saves on computers were looked for beside the game even when the
  emulator was set to keep them elsewhere.
