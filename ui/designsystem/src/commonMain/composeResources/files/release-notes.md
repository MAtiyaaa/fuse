# Fuse 0.4.2.23 - The Native Update

Fuse One's host runs on its own and updates from any device, a game with several versions is one
game, and fixes for RomM uploads, saves, art, gradients on Linux and battery life on handhelds.

## New

- About has Update the Fuse One host: from any device, the host fetches the newest Fuse in place of
  its AppImage and starts again on it by itself.
- A game's page shows its versions between its facts and Play: each copy (this device's, RomM's,
  another device's) with where it is, its size, and this device's updates and DLC. Choosing one
  offers downloading it too.

## Changed

- Fuse One's host runs as a service of the system, so it keeps serving with Fuse closed, after a
  restart or a power cut, and when the network comes back. A host set up before is handed to the
  service by itself (unless the service was taken away in Settings); systemd never gives up on it,
  and it tells the household its address again when the network comes back with another one.
- A system opened from Library All looks as it does from Systems: its logo on top with its name,
  and its background behind it.
- A game on RomM and here is one tile in the All views: "&" and "and", a Switch update number, a
  region or a release group in brackets and a trailing "v0" no longer make it two games.
- RomM uploads send four pieces at once, several times faster over a slow route.
- A game that arrives in the library (a download, a copy) gets its save from Fuse One at once,
  instead of at its first start.
- An automatic art search stops once its sources are out of requests and it keeps finding
  nothing, instead of going through every game adding nothing.

## Fixed

- RomM uploads stopped near the end with RomM 5.3, which moved its library scan: Fuse now starts
  the scan the way 5.3 asks, so an uploaded game appears on the server.
- The Devices list showed every device last seen at the same old time, and other devices' games
  could be missing: the list is read from the host again every 45 seconds, and a device's list of
  games that didn't reach the host is sent again within half a minute.
- After the first art search following a scan, Fuse kept the device awake for as long as it ran:
  Android held a wake lock and a low-latency Wi-Fi lock, and Linux blocked sleep. It now lets go
  once the search is done.
- On a handheld, every shared game file was read in full for the household, many gigabytes at full
  speed, and ran it hot. Android now does it only while charging.
- A focused game showed no art until it was opened: art that failed to load once (a source busy,
  the network coming back) is asked for again, and a focused game without art has it looked for
  at once.
- Gradients behind Home still banded on Linux: the dim and veils drawn over the dithered room
  scaled its noise below one 8-bit step. They now carry noise of their own.
