# Fuse 0.3.6.6 - The Unity Update

Uploads to RomM no longer freeze Fuse on a computer, and the standby screen never stops Fuse's work.

## New

- **Fuse keeps the device awake while it works.** While Fuse finds art and details, downloads or
  uploads, it asks the system to stay awake, and lets go once the work is done.

## Changed

- **Every transfer runs in the background.** Uploads, RomM and Jellyfin downloads and updates no
  longer share the thread that draws Fuse on a computer.

## Fixed

- **Uploading to RomM froze Fuse until it finished.** On a computer, Fuse's transfers ran on the
  same thread that draws the interface. Before sending a game, an upload reads the whole file to
  check whether RomM already has it, then reads it again piece by piece to send it. For a large
  game that kept the screen still and stopped controller, keyboard and mouse input until the upload
  was done. Every transfer (uploads, RomM and Jellyfin downloads, updates) now runs in the
  background, so Fuse stays responsive and the Downloads page shows the upload moving. Android was
  not affected.
- **The standby screen stopped Fuse's work.** Left on the standby screen (Fuse's dark screen that
  guards OLED screens against burn-in), the system's own sleep soon followed, and finding art and
  details, downloads and uploads stopped with it. While Fuse works, it now asks the system to stay
  awake: on Android the processor and Wi-Fi stay up, and the screen stays on under the standby
  screen; on Linux and the Steam Deck through `systemd-inhibit`, on macOS through `caffeinate`, and on
  Windows through the system's own setting. Once the work is done, Fuse lets go and the device
  sleeps as usual.
