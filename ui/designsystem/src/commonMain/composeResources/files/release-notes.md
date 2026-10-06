# Fuse 0.3.6.3 - The Unity Update

Sign in to Fuse Sync and the rest comes with it: your household's RomM and Jellyfin, each person
with their own Jellyfin account, and a say in whose saves move on each device.

## New

- **RomM and Jellyfin come with Fuse Sync.** A device signed in to RomM or Jellyfin shares the
  addresses and its sign-ins with your household, and a device without them gets them: join a host
  and RomM and Jellyfin are there too, signed in. Sign-ins are sealed for each device on the way,
  kept sealed on the host with a key only the host has, and kept on each device only in Fuse's
  secure storage, never in its database, logs, diagnostics or exports. A device that already has
  its own server or sign-in keeps it. Settings, Fuse Sync, Share Sign-ins turns it off for a device.
- **Asked once, when you update.** Devices already in a household when they update aren't changed
  by themselves: the first one with RomM or Jellyfin set up asks whether to share its sign-ins from
  there. Yes shares them; Not From This Device leaves the question to the next device that updates.
  Once one device has shared, the others come along without asking, and devices joining from now on
  just share.
- **Each person's own Jellyfin account.** Everyone can sign in to the same Jellyfin server as
  themselves, so what they watch, resume and mark stays theirs. Switching profiles switches the
  account; someone without their own uses the device's until they sign in. Settings, Addons,
  Jellyfin shows whose account is in use and offers Use Mo's own account.
- **Don't Take Saves From.** Settings, Fuse Sync can keep chosen people's saves from coming to a
  device (a child's handheld that never takes a grown-up's saves). That device still sends theirs.
- **Don't Sync Saves For.** Chosen people's saves on a device stay on it: nothing comes in and
  nothing goes out. Play time and the library still sync for both.

## Changed

- **Fuse Sync comes first in setup's connections**, right after BIOS and before RomM and Jellyfin,
  so joining a household fills those steps in.
- **The grand welcome, the first time anyone plays here.** The fuse that burns in and lights your
  avatar now plays for every new profile, and the first time someone signs in to their profile on a
  device, not only for the first profile ever made there. Devices that updated don't replay it for
  the people already playing on them.
- **Motion follows the device.** Motion on Automatic (until now Theme default) matches the effects
  setup recommends for the device and the Performance choice: Minimal for light effects, Standard
  for balanced, Enhanced for high quality. Setup's device step names both, and Settings,
  Appearance, Motion shows what Automatic is on this device. A calm theme stays calm, and a level
  you chose is kept.
- Jellyfin keeps your user name and password in Fuse's secure storage after signing in (it used to
  keep only the server's token), so your household's other devices can sign in as you. Signing out
  forgets both.

## Fixed

Nothing this time: this release adds and changes.
