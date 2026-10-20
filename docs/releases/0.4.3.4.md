# Fuse 0.4.3.4 - The Overdrive Update

Your theme stays chosen even beside an older Fuse, and a device says which Fuse the host runs now.

## New

- Nothing new in this release.

## Changed

- A Fuse writes only what you changed in it. Before, each time it saved its settings it wrote
  everything it was showing, so a Fuse showing settings from a moment ago (one open beside another
  on the same computer, like a game Steam started with Fast Start next to the open Fuse) put them
  back, and Fuse One sent them to every device as your newest choices. What changed in Fuse's
  settings by any other way is now shown, and never written over.

## Fixed

- Twin or Viewfinder went back to Fuse a few seconds after you chose it, even on 0.4.3.2 and
  0.4.3.3. A device in the household on a Fuse from before 0.4.3.1 hasn't got those themes, shows
  Fuse instead, and before 0.4.3.2 it sent Fuse to all your devices as your newest choice every
  time it saved its settings. 0.4.3.2 stopped new devices doing this, but it couldn't change the
  older ones. Now the host leaves out Fuse sent by a Fuse from before 0.4.3.2 over a theme that
  Fuse hasn't got; everything else that device sends still counts. The host updates itself once a
  device runs 0.4.3.4 (Docker hosts with docker compose pull). Choose your theme once more after it
  has, and it stays.
- About said the host already runs the newest Fuse while Fuse One said it runs an older one. The
  host updates itself a minute or two after a device with a newer Fuse connects, but a device
  asked it which Fuse it runs only every ten minutes. A device now asks every minute while the
  host's Fuse is older than its own, and Update the Fuse One Host shows the Fuse the host answers
  with straight away.
