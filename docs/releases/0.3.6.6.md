# Fuse 0.3.6.6 - The Unity Update

Uploads to RomM, and every other transfer, no longer freeze Fuse on a computer.

## Fixed

- **Uploading to RomM froze Fuse until it finished.** On a computer, Fuse's transfers ran on the
  same thread that draws the interface. Before sending a game, an upload reads the whole file to
  check whether RomM already has it, then reads it again piece by piece to send it. For a large
  game that kept the screen still and stopped controller, keyboard and mouse input until the upload
  was done. Every transfer (uploads, RomM and Jellyfin downloads, updates) now runs in the
  background, so Fuse stays responsive and the Downloads page shows the upload moving. Android was
  not affected.
