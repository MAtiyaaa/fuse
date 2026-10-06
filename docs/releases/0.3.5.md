# Fuse 0.3.5 - The Swift Update

Quicker where it was slow: Fuse Sync from outside home, switching profiles, moving between tabs and
the Hub in a browser. The Sync tab is rebuilt so your games come first, and saves that are whole
folders now arrive exactly as they were made.

## New

- **A Sync tab you can see.** Who's playing and the things to do sit in one slim line that folds
  away with the tabs as you move into your games, so four or more are always in view, even on the
  AYN Thor's upper screen. Each game shows its cover, system, play time, saves and states, and
  which device saved last. Devices and recent activity sit beside the games on wide screens and
  after them on smaller ones.
- **A page for each game's saves.** Opening a game on the Sync tab shows its cover and figures
  (play time, sessions, last played, what the host keeps), then every version of each save on a
  timeline: the one in use lit, the ones kept for good marked, each with its device, how far in
  and why it was kept.
- **Folder saves keep their whole structure.** Saves that are folders (PSP, PS3, Switch and the
  like) travel with every nested folder, empty ones included, and a save put in place is that
  save exactly: files of the one it replaced that it doesn't have are removed, and kept in its
  history first.
- **A new update viewer on the website.** Every release, newest first, with its notes in sections
  you can filter and a link straight to any version.

## Changed

- **Fuse Sync from outside home is much quicker.** Away from home, Fuse goes straight to the
  outside address instead of waiting on home first for every call, and only looks for home now
  and then on the side, switching to it the moment it answers. Saves go up and come down four
  files at a time, and big answers from the host come compressed.
- **Switching profiles is quick.** A switch sends and brings in only the records, once; saves
  waiting to go follow in the background without holding the switch up or rewriting the library
  as you start using it.
- **Tabs change at once.** Moving from Home to Systems or the Library shows the page in the very
  next frame, with a short nudge from its side, instead of sliding two pages across each other.
  Systems and the Library are built ahead while Home sits idle, so even the first visit is
  instant.
- **The Hub opens at once.** The Hub in a browser lists every game first, and brings a game's
  versions and files only when you open it. Pages come compressed, and Refresh with nothing new
  sends nothing again.
- **No light for all being well.** The Sync and Syncthing tabs only show how things stand when
  something is wrong, such as the host being away.
- The website tells more of what Fuse does, from profiles for everyone to saves from every system.

## Fixed

- On a handheld, the Sync tab's games were hidden behind its header, its buttons and the column
  beside them, leaving about one and a half games on screen.
- A folder save brought down over another could leave the other's files mixed into it.
- Switching people on one device could leave the last person's empty folders in the next person's
  save.
