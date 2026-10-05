# Fuse 0.3.1 - The Glide Update

## New

- **Syncthing, for people who already run it.** Settings, Addons, Syncthing finds the Syncthing
  on this device (its own key on a computer; Syncthing-Fork on Android), or takes an address and
  API key. It shares your emulators' save folders with the devices you add (a code to scan from
  Syncthing on a phone, or the ID to type), accepts devices that ask to join, and keeps a month
  of older versions of each save. Before a game starts, the newest save is brought in; after it
  closes, it goes straight out; and when two devices both played, Fuse asks which save to keep and
  keeps the other as an old version. Games themselves are never shared. Fuse Sync by Fuse is still
  the one Fuse recommends: it knows each game whatever its file is called, adds up play time and
  keeps a profile for each person. The two never run together; turning one on turns the other off.
- **Setup asks how to stay in step.** The Every device step shows Fuse Sync and Syncthing side by
  side, Fuse Sync recommended, with Use Fuse Sync (this device the host, or connect to one), Use
  Syncthing, or Skip.
- **Home widgets that turn like a catalogue.** Continue Playing, Systems, New in Library, Recently
  Played, Favourites, Pinned, Collections and Jellyfin's widgets show one item at a time, turned
  with L2 and R2 (L1 and R1 stay with the tabs) or a swipe, with dots and a gentle parallax; the
  next item peeks in, except in Continue Playing, which turns page by page, newest first.
- **New widgets.** Jellyfin favourites, new films and new music; Fuse Sync's status and its devices.
- **Thor's two screens and the controller.** On the AYN Thor, Fuse follows the Focus Mode you
  choose: Auto (the screen you last touched), top locked or bottom locked. The second screen takes
  the controller when the Thor sends it there and passes it to Fuse's menus, never while a game is
  in front. With the bottom screen off (the chin button), the menus come up to the top screen; with
  only the bottom one on, they go down to it.
- **Films ask which screen.** On two screens, a film asks where to play each time (the new default),
  with one tick to keep the answer. Fuse Player's top bar has Watch on the Other Screen, and Y does
  it too.

## Changed

- **Jellyfin answers quickly.** Every way to the server is asked at once (home wins when it
  answers), a call that can't get through tries the other way before anything says the server
  can't be reached, kept pages show at once while fresh answers follow, the home page asks for its
  shelves together, and Android lets 16 requests run per server instead of 5. Pages open far
  faster, and Jellyfin's widgets fill in.
- **The clock, storage and play time widgets, redrawn.** The clock is large with a calendar leaf
  and a track of the day, and on bigger faces a dial. Storage is a capacity ring (a bar on a strip)
  with free space large. This week's bars are lit with today glowing and the daily average dashed;
  All time counts toward the next round mark; Most played shows the leader's art.
- **Each profile has its own Home and quick menu.** A new profile starts with Fuse's own Home,
  quick menu and theme, never the last person's.
- **Menu music shuffles by default** (once for existing settings, unless you chose your own song).

## Fixed

- A game's play time came only partly into view with the stick; it now scrolls fully into view,
  as does every line of the detail cards (On this device on a small screen).
- The cover on a game's page sat lower than its title; it stands level with it now.
- The second screen's On this device page couldn't be reached with the controller.
- How to put a film on the other screen wasn't clear; the player says so in its top bar.
- Jellyfin said it couldn't reach the server while its outside address worked.
