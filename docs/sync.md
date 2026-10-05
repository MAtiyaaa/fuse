# Fuse Sync by Fuse

Fuse Sync keeps your saves, save states, play time, library and settings the same on every device
you play on. It runs on a computer of your own at home (the **host**), and every other device
(a handheld, a phone, another PC) connects to it. Nothing goes to anyone else's server.

It sits beside Fuse's other first-party parts: **Fuse Player by Fuse** (films, shows and music) and
**Fuseline by Fuse** (the motion engine).

- Stop on the PC, carry on on the handheld: the save is there before the game starts.
- Play offline anywhere. Changes wait on the device, safe, and go up when the host is back.
- Play time adds up across devices: 30 minutes on one and 20 offline on another is 50.
- A profile for each person: their own saves, play time, favourites, collections, Home and theme.
- Nothing is ever silently lost. When two devices both played, you choose, and the other is kept.

Your games themselves are never synced or copied to the host: each device keeps its own games, and
Fuse Sync carries only what you made playing them.

Off, Fuse Sync does nothing at all: no profiles, no Sync tab, no network, nothing in the background.

## Setting it up

**On the computer that will be the host** (Linux, Windows or macOS): Settings, Addons, Fuse Sync,
turn it on, then **Make This the Host**. Give it a name, choose whether it keeps running when Fuse
is closed (see below), and it shows **Fuse Sync is ready** with an eight-character code.

**On every other device**: Settings, Addons, Fuse Sync, turn it on, then **Connect to a Host**.
Fuse looks on the network (**Fuse Sync found**), you choose the host and type its code, and then
choose who is playing. A code works once, for ten minutes; the host makes a new one with
**Add a Device**.

Android devices connect to a host rather than being one: Android stops background work to save
battery, so it couldn't promise to be there for your other devices.

Onboarding offers the same two buttons, and both can be skipped.

## Profiles

Every person has a profile on the host, with one of Fuse's own avatars and, if they want, a PIN.
**Who's playing?** opens from the avatar at the top right, from the Sync tab and from Settings.
Switching happens in place, without a restart: the library's favourites, names, play time, Last
Played, collections, theme, Home and quick menu change to that person's at once.

At startup Fuse can use the last profile, ask who's playing every time, or always start as one
person (asking for their PIN when they have one). Several devices can be on different profiles at
the same time.

The first time a device joins a profile, what it already had joins that profile: a new profile
takes it as it is; a profile already in use keeps what it has and only gains what it was missing,
plus this device's play time. Nothing on the device is replaced without asking.

## People and saves

Each person has their own saves, even on a device several people share. Fuse remembers whose save
is in each emulator's folder; when someone else starts that game, the one who played last keeps
theirs (anything they changed is kept for them first, and their save is set aside on the device
by content), then the new player's own save comes in: the newest from the host, or, offline,
the one set aside for them here. Someone who never played that game starts it fresh, with nothing
of anyone else's. Memory cards shared by every game on them (PlayStation 2, GameCube, Dreamcast)
work the same way, per person. A save conflict is only ever between one person's own versions.

**Play One Save Together.** A game can instead be one save for everyone (a game a family plays
together). Turn it on in the game's Save History; Fuse asks whether to start from your save or
fresh. Every device then puts the same save in place for whoever plays, and it shows as "shared by
everyone". Play time stays each person's own, and each person's earlier saves stay in their
history, so turning it off later goes back to them.

## What syncs, and what stays

Settings are sorted into four kinds:

| Kind | Examples | Where it lives |
| --- | --- | --- |
| A person's | Theme and appearance, Home layout and pages, tabs and their order, quick menu, library sort and art, sounds and music, the clock, Fuse Player's languages and subtitles | Follows the profile to every device |
| A device's | Controllers and mappings, screens and the second screen, performance, drives and library folders, file paths, add-on accounts (Jellyfin, RetroAchievements), Fuse Sync's own connection | Stays on the device |
| The host's | Profiles, devices, every save version, the journal | Only on the host |
| Shared records | Each game's favourite, hidden, pinned, custom name, chosen emulator, play time, sessions and Last Played; collections | Merged across devices, per profile |

A setting added in a later version stays a device's until it is listed as a person's, so nothing
crosses over by accident. Each kind can be turned off under **What syncs**.

### This device's own Home

While arranging Home, a switch beside **Add widget** and **Done** chooses whose Home it is:
**This Device** (laid out for this screen, kept apart) or **All Devices on Profile**. The same
choice is in Settings, Addons, Fuse Sync, and only appears while settings follow a profile.
Switching keeps both: the profile's Home is untouched while a device has its own, and a device's
own Home comes back when it is chosen again.

## Games, not paths

Fuse Sync knows a game by what it is (its system and serial, else its title without region or
version tags), never by where its file is. "Pokemon Ruby (USA).gba" on the PC and
"pokemon ruby (Europe).gba" on a card in the handheld are the same game, and its save lands where
each device's emulator reads it.

Saves are found by per-emulator adapters. Fuse Sync and Syncthing use the same ones.

| Emulators | Where their saves are | Save states |
| --- | --- | --- |
| RetroArch | Per core, where its config says (beside the content, or its saves folder) | Per core |
| Lemuroid | Its saves folder, when moved out of Android's private storage | |
| DuckStation, ePSXe, FPse | Memory cards | DuckStation |
| PCSX2, NetherSX2, AetherSX2, ARMSX2, Play! | Memory cards (shared by every game on them, synced as cards) | |
| PPSSPP | SAVEDATA folders by game id, on the memory stick you chose | |
| Dolphin (and MMJR) | GameCube memory cards, Wii saves | |
| melonDS, mGBA, Mednafen, SkyEmu, My Boy!, My OldBoy!, Pizza Boy, NooDS and others | Saves beside the game, or their save folder | |
| DraStic | `backup/<game>.dsv`, converted to and from a plain DS save | |
| Mupen64Plus, M64Plus FZ | The game's EEPROM, SRAM, FlashRAM and paks, converted to and from RetroArch's one file | |
| Flycast, Redream | VMUs | |
| Yaba Sanshiro, Saturn emulators | Backup RAM | |
| MAME | NVRAM by set | |
| ScummVM | Its saves by game | |
| RPCS3 (and on Android) | dev_hdd0 savedata by serial | |
| Vita3K | ux0 savedata by title id | |
| shadPS4 | User savedata by serial | |
| Azahar, Citra, Lime3DS, Mandarine | The emulated SD card, by the cartridge's title id | |
| Eden, Citron, Sudachi, yuzu and forks | The emulated NAND, by title id | |
| Ryujinx | Its numbered save folders, matched by title id | |
| Cemu | `mlc01/usr/save`, by the title id in the game's `meta.xml` | |
| Xenia | `content`, by the title id in the game's name | |

Title ids come from the game itself where they can: a 3DS cartridge's header, a Switch game's
`[0100…]` tag or the ticket inside its NSP, a Wii U game's `meta.xml`. A save is only put where the
same format is read (a state from one core never lands in another), except where Fuse can convert:
DraStic's `.dsv` and a plain DS save, and Mupen64Plus's separate N64 save files and RetroArch's one
`.srm`.

**Save Folders** (Settings, Addons, Fuse Sync or Syncthing) lists each emulator in the library and
where its saves are on this device. Emulators that save where you tell them (Azahar, PPSSPP's
memory stick, DraStic, M64Plus FZ, Vita3K and others) can be pointed at that folder; the choice
is this device's own and is used by Fuse Sync and Syncthing alike. Android 11 and later keep each
app's `Android/data` folder private, so an emulator that only saves there says so, with how to move
its data out. Still out of reach: xemu (its saves are inside a hard disk image), Windows games in
Winlator and similar (each keeps its own), and PC games (Steam has its own cloud).

## Around a game

**Before a game starts**, Fuse asks the host for that game's newest save and puts it in place (a
launch never waits more than a few seconds; offline, the game starts with what is here).

**After it closes**, Fuse waits a moment for the emulator to finish writing, counts the session,
keeps the save as a new version, and sends it.

**When both sides played** since they last agreed, Fuse asks which save to use, showing where and
when each was saved and how long the game had been played by then: **Use This Device** or
**Use Fuse Sync**. The one not chosen is kept in the game's history as the other side of a
conflict. Timestamps alone never decide: every version records the one it was made from, so a
conflict is told by history, not clocks.

## Save history

Every game has a **Save History** (in its options), with versions from every device. Any version can
be put back (what is here now is kept first), and any can be **kept for good**. The rest are tidied
over time: the last 10, then one a day for two weeks, then one a week for two months. The newest is
always kept, and so is the other side of every conflict.

Every file is stored by its SHA-256 and checked after every transfer; a file is written to a temporary
name, checked, and only then moved into place, so a cut connection never leaves half a save.

## Offline, drives and devices

Everything works without the host. Changes queue on the device (play time, favourites, saves) and
go up in order when it answers. A drive or card that isn't there is **unavailable**, never deleted:
its games and saves are left alone until it comes back.

A host serves around 50 devices. Changes reach the others within seconds (they listen for the host's
journal). On a slow connection what matters most goes first: records, then the save a starting game waits for, then the rest.

## Reaching the host from outside

At home, devices find the host by themselves. Away, give each device an **outside address** that
reaches the host over the internet: a VPN such as Tailscale or WireGuard, or an https reverse
proxy or tunnel in front of port 47311. Fuse uses the home address when it answers and the outside
one otherwise, switching back by itself.

## Keeping the host running

**Keep running when Fuse is closed** runs the host on its own, as `Fuse --sync-host`, without a
window:

- **Linux**: a systemd user service (`~/.config/systemd/user/fuse-sync-host.service`) with
  lingering on, so it starts with the computer, before anyone signs in, and restarts if it stops.
- **macOS**: a LaunchAgent (`~/Library/LaunchAgents/io.github.matiyaaa.fuse.synchost.plist`),
  started when you sign in and kept alive. Turn on automatic sign-in for it to start after a restart.
- **Windows**: a scheduled task at sign-in, restarted if it stops. Windows may ask once to let Fuse
  through its firewall.

Fuse hands the host over to the service (they never run it at the same time) and manages it from
then on. Turning it off brings the host back into Fuse while it is open.

On the host computer, **http://127.0.0.1:47311/hub** shows the Hub in a browser: how long the host
has run, what it keeps, and every profile and device (who plays where, when each was last seen).
It opens on that computer only and only shows; changes are made in Fuse. In Fuse, Addons, Sync is
the same Hub, with Sync Now, Switch Profile and Add a Device.

## Security

- Pairing uses a one-time code, valid for ten minutes and five tries. The device's secret is sealed
  with that code (AES-GCM, key from PBKDF2) and never sent in the clear.
- Every request is signed (HMAC-SHA256 over the method, path, time, a nonce and the body). Requests
  more than five minutes off, or seen before, are refused.
- Each device has its own credential, scoped to it and revocable from the host at any time.
- PINs are stored as salted PBKDF2 hashes; after three wrong tries the host asks to wait, longer each time.
- Paths in a save are checked: nothing absolute, no `..`, nothing outside the save's own folder.
- Requests are rate limited per address.
- Managing a host that runs as a service uses a token readable only by your user, from this computer only.
- Use an https address (or a VPN) from outside: signing protects every request, encryption hides it.

## Where it keeps things

| | Linux | Windows | macOS | Android |
| --- | --- | --- | --- | --- |
| Host | `~/.local/share/fuse/sync/host` | `%LOCALAPPDATA%\Fuse\sync\host` | `~/Library/Application Support/Fuse/sync/host` | |
| This device's queue and copies | `~/.local/share/fuse/sync/device` | `%LOCALAPPDATA%\Fuse\sync\device` | `~/Library/Application Support/Fuse/sync/device` | Fuse's app storage |

**Backup and moving the host**: stop the host, copy its `host` folder to the new computer's, and
make that computer the host; every device keeps working, with the same profiles and history.
Fuse's own Backup (Settings, Backup) also keeps the person's records and settings.

## Leaving

**Unlink This Device** stops syncing and keeps everything on the device exactly as it is.
**Stop Hosting** stops the host and its service and keeps all of its data on the computer, so
hosting again carries on where it was. Deleting a profile is the only thing that removes data from
the host, and it asks first.

## Syncthing instead

For people who already run [Syncthing](https://syncthing.net), Fuse can use it in place of Fuse
Sync (Settings, Addons, Syncthing, or the Every device step in setup). The two never run together:
turning one on turns the other off on this device, because both would move the same saves.

**Connecting.** On Linux, Windows and macOS, Fuse finds Syncthing at `127.0.0.1:8384` and reads its
API key from Syncthing's own `config.xml` (`~/.local/state/syncthing` or `~/.config/syncthing` on
Linux, `~/Library/Application Support/Syncthing` on macOS, `%LOCALAPPDATA%\Syncthing` on Windows).
On Android it looks for Syncthing-Fork, can start it, and asks once for the API key from its Web
GUI (Actions, Settings, General). Another address can be typed with its key. The key is kept in
Fuse's secret store and only ever sent to that address; Syncthing-Fork's own certificate is
accepted on this device's loopback address and nowhere else.

**What it shares.** One folder for each emulator's saves, save states and memory cards that the
library uses, with an id every device agrees on (`fuse-<emulator>-<kind>`), so the same folder on
another device joins it. Folders Fuse shares keep older versions with Syncthing's staggered
versioning (a month, thinned as it ages), unless that is turned off. A folder that sits beside the
games themselves, or in another app's private storage, is listed as one that can't be shared:
games are never shared. Devices are added by their Syncthing device ID (shown as a code to scan on
wider screens), and devices that ask to join can be added with one press; Fuse's folders are
offered to every device added, and folders another device offers under Fuse's ids are accepted
into the matching folder here.

**Around a game.** Before a game starts, its folders are looked over and, while another device is
connected, Fuse waits briefly for Syncthing to bring in the newest save (Bring In the Newest Save
First). Then it checks for Syncthing's conflict copies
(`name.sync-conflict-YYYYMMDD-HHMMSS-DEVICE.ext`) of that game's saves; if there are any, it asks
whether to keep this device's or the other device's, and the one not chosen is moved into the
folder's `.stversions`, never deleted. After the game closes, its folders are looked over at once
so the new save goes out.

**What it can't do.** Syncthing moves files as they are named, so a save only matches where the
game's file has the same name on every device, and there is no play time, no profiles and no
library or settings sync. It also keeps one save per game for everyone who plays on these devices,
where Fuse Sync gives each person their own. That is why Fuse Sync is the one Fuse recommends.
Its folders come from the same adapters and Save Folders as Fuse Sync.

**Leaving.** Turning Syncthing off in Fuse, or Disconnect Syncthing, stops Fuse using it (and
Disconnect forgets the key). Syncthing keeps running and keeps syncing whatever it shares, and
every file stays where it is.
