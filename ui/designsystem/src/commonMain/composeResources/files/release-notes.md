# Fuse 0.3.3 - The Together Update

Close the handheld, pick up the computer, keep playing. This release is about Fuse Sync doing
exactly that, every time, plus joining without codes, a host you can reach from anywhere, and a
long list of fixes from real use.

## New

- **Saves go up while you play.** Fuse Sync watches a running game's save and sends it to your
  host as soon as the game writes it, not only when the game closes. On Android a quiet
  notification keeps Fuse running during the game, and when the screen goes off (a handheld's
  lid closing) Fuse sends the newest save right away.
- **Devices know who is playing what.** Start a game on the computer while the handheld is still
  on it, or still sending its save, and Fuse says so: wait a moment for the save (the game starts
  by itself once it is in), or play with the newest save the host has. A handheld that went to
  sleep mid-game is told apart from one that is playing right now.
- **One game, whatever each device calls it.** A game one device knows by its serial and another
  only by its name is the same game: its saves, play time and history follow it either way. Saves
  kept under the other name are moved over by themselves.
- **Fuse tells you what it did.** A quiet message when a save reaches your host, when one from
  another device is put in place before a game ("Your save from your handheld, 4 min ago"), and
  when the newest save can't be used here (another emulator's), with what to do.
- **Join without a code.** On a new device, Connect to a Host offers Ask to Let This Device In. A
  card appears on the host and on every connected device with a six-digit number; the new device
  shows the same number. Check they match and choose Let It In. Typing a code still works, and any
  connected device can show one.
- **A host account.** Give the host a username and password (Settings, Addons, Fuse Sync, Host
  Account). A device can then let itself in with them when nobody is at a screen to say yes (the
  password never leaves the device), and the Hub opens from away after signing in.
- **One outside address for every device.** Reach your host through a tunnel or reverse proxy
  once, and the host remembers that address and shares it with every device, so away from home
  they all just work. You can also type it on the host. The Hub is at the same address
  (`https://sync.example.com/hub`), behind the host account.
- **The host has its own profile.** Setting up a host makes Admin, a profile only that computer
  sees, so nobody needs a profile there. Other devices never see Admin.
- **Choose where the host keeps saves.** Setting up a host asks where everyone's saves should
  live (a bigger drive, say). Settings can move them later: everything is copied and checked
  before the old folder goes.
- **Delete This Host.** On the host, it removes every profile, save and device link kept there;
  that computer keeps its own games, library, settings and Home.
- **Erase Fuse**, at the bottom of About: start over as new. Fuse's library, settings, profiles,
  art and caches go, and Fuse Sync is let go of first. Game files, emulators and the saves in the
  emulators' folders stay. It asks twice.
- **Undo Home Reset**, in Settings (Home) and in Fuse Sync, brings back the Home you had before a
  reset.
- **3DS save states** (Azahar, Citra) sync along with saves.
- **Packages install on first press.** A PS3, Vita or 3DS package that isn't installed yet
  installs behind the launch screen, which shows its progress, then the game starts. One that
  needs a licence key opens its page instead.

## Changed

- **Turning Fuse Sync off forgets the host.** The device lets go of the host, its profiles and
  anything waiting to be sent, and keeps its games, saves, library, settings and Home exactly as
  they are. Turning it on again starts fresh.
- **New profiles keep this device's look.** A new profile starts with this device's theme, Home
  and settings, and they become the profile's own; they are never put back to Fuse's defaults.
- **Resetting Home changes this device only.** A Home shared by the profile becomes this
  device's own, so your Home on other devices stays as it is.
- **The mouse works the way you expect.** Pointing at a widget, tile, game or menu row highlights
  it, one click opens or plays it, and the wheel turns a carousel one card at a time. A pointer
  resting on the screen never takes the highlight from a controller. Touch still shows a game
  first and plays it on the second tap.
- **The Sync and Syncthing tabs are redesigned.** Each opens on a card with the host (or
  Syncthing), how things stand, who is playing and their totals. Devices and what happened lately
  sit beside the list, games show their covers, and folders show what they hold.
- **Carousels look cleaner.** The Systems carousel shows each system's art and logo at a sensible
  size. A card waiting at the edge of any carousel (games, Jellyfin, collections, systems) shows
  its picture only; its words fade in as it comes forward.
- **Smooth on computers whose graphics card Fuse can't use.** Where Fuse ends up drawing on the
  processor (common with some Linux drivers), pages change at once and effects stay light instead
  of stuttering. Settings, Performance, About this device shows what draws Fuse.
- **Fuse Sync on Android uses Android's own secure connection**, as Jellyfin does, so an https
  address answers the same way it does in a browser.
- An address typed without `http://` or `https://` is reached securely when it is a name on the
  internet and plainly when it is at home (an IP address, a `.local` name). When the home address
  doesn't answer, Fuse tries the outside address before giving up.

## Fixed

- Moving in Who's Playing, on Home's widgets, in the Sync tab and in Syncthing played two sounds
  per move. Each move plays one.
- Holding L2 on the first item or R2 on the last made a widget shake again and again. It now
  bumps once.
- A translucent box showed over carousel widgets while moving between them, and the last widget
  showed an odd background on hover.
- A save made on one device sometimes wasn't found on another, because the two knew the game by
  different names (a serial on one, only a title on the other).
- Closing a handheld mid-game could leave its newest save on the handheld until Fuse was opened
  there again.
- Games scanned in as packages said "not installed" and needed a trip to their page first.
- A host reached through a tunnel on the same computer treated those calls as coming from the
  computer itself, so the Hub could be opened from the internet. Calls a tunnel or proxy carries
  now count as coming from outside.
