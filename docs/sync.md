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
- One household, one newest save: a save made on any device reaches every other device that has
  the game by itself, and each game says "Synced everywhere" or "5 of 6 devices current".

Your games themselves are never synced or copied to the host: each device keeps its own games, and
Fuse Sync carries only what you made playing them.

Off, Fuse Sync does nothing at all: no Sync tab, no network, nothing in the background. Profiles
don't need it: a device keeps its own (see [Profiles](#profiles)), and Fuse Sync takes them along
when it joins a host.

## Setting it up

**On the computer that will be the host** (Linux, Windows or macOS): Settings, Addons, Fuse Sync,
turn it on, then **Make This the Host**. Give it a name, choose whether it keeps running when Fuse
is closed (see below), and it shows **Fuse Sync is ready** with an eight-character code.

**On every other device**: Settings, Addons, Fuse Sync, turn it on, then **Connect to a Host**.
Fuse looks on the network (**Fuse Sync found**) and you choose the host. Then either:

- **Ask to Let This Device In.** A card pops up on the host and on every device already connected,
  naming the device and showing a six-digit number; the new device shows the same number. Anyone
  there checks they match and chooses **Let It In**. Nothing to type. The request lasts five
  minutes.
- **Type a Code.** **Add a Device** shows an eight-character code, on the host or on any device
  already connected. A code works once, for ten minutes.

Then the device makes its profile (or chooses one), and it is in. A device that already has
profiles of its own brings them along (see [Joining with profiles](#joining-with-profiles)).

**The host's own profile.** Setting up a host makes **Admin**, a profile only that computer sees
and plays as, so nobody needs a profile there. Other devices never see or open Admin; each person
makes their own from their device. A host set up before 0.3.3 gets Admin too, and keeps playing as
the profile it was using.

Android devices connect to a host rather than being one: Android stops background work to save
battery, so it couldn't promise to be there for your other devices.

Onboarding offers the same two buttons, and both can be skipped.

**If a device can't reach the host.** A computer with Docker, WSL, Hyper-V or virtual machines has
networks of its own (`172.17.0.1` to `172.31.x.x` are typical) that no other device can reach. The
host leaves those out and lists its home network address first, and a device tries every address
the host has and uses the one that answers. If none does, the host's firewall is usually what
stops it: allow Fuse (or TCP port 47311 and UDP port 47310) on private networks. On Windows, that
is the "Allow access" prompt the first time Fuse hosts, or Windows Security, Firewall, Allow an app.

## Profiles

Every person has a profile, with one of Fuse's own avatars and, if they want, a PIN. Profiles work
without Fuse Sync: on a device with no host they are that device's own, each with their own saves,
play time, favourites, collections, Home and theme, and a PIN kept only as a salted hash. Each
person's save in an emulator's folder is put away when someone else plays that game and put back
when they do. With a host, profiles are the host's and follow each person to every device.

Setup asks who's playing near the start (**Create Your Profile**, then **Add Another** or
**Continue**; it can be skipped). **Settings, Profiles** lists everyone, adds someone, edits a
profile (name, picture and PIN, asking for the PIN first when it has one), puts them in order
(**Profile Order**, the same order on every device with a host) and deletes one. Deleting a
profile without a host keeps their saves as plain files in Fuse Sync's `kept` folder.
**Who's playing?** opens from the avatar at the top right, from the Sync tab and from Settings.
Switching happens in place, without a restart: the library's favourites, names, play time, Last
Played, collections, theme, Home and quick menu change to that person's at once.

At startup Fuse can use the last profile, ask who's playing every time, or always start as one
person (asking for their PIN when they have one). Several devices can be on different profiles at
the same time. With the host away, Who's playing? can always be closed (Not Now), a profile without
a PIN switches at once and catches up later, and one with a PIN waits for the host, the only one
that can check it.

The first time a device joins a profile, what it already had joins that profile: a new profile
takes it as it is; a profile already in use keeps what it has and only gains what it was missing,
plus this device's play time. Nothing on the device is replaced without asking.

### Joining with profiles

A device with profiles of its own joining a host (or becoming one) takes them along. The host's
own profile (Admin) never counts either way.

- **The host has nobody yet** (or only its Admin): every profile goes up as it is, PIN and all,
  with its records and saves, and the person playing stays playing. A message says so.
- **The host has people**: **Your profiles and the host's** pairs each profile here with what it
  becomes. **Same as** someone there (suggested when the names match, case and spaces aside):
  their records join (the host's choices win where both say something, play time adds up) and the
  saves go up as their history, so a different save on both sides is a choice later; joining a
  profile with a PIN asks for it. **Someone new**: it goes up as it is. **Leave out**: it leaves
  this device, its saves kept as plain files. **Bring Them** does it all at once; **Use Only
  Theirs** leaves every profile here out; **Not Now** stops joining and keeps everything here.
- **Making this computer the host** with profiles here: the new host has only Admin, so they all
  go up, and this computer keeps playing as its own person rather than Admin.
- **The same person on two devices**: the second device suggests **Same as** them by name, and
  their play time from both adds up.

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

Each device tells the host every name it knows a game by (its serial when it has one, and its
title), and the host answers with one id for the game that every device then uses. So a game the
computer knows by its serial and the handheld knows only by its title is still one game, and saves
kept under the other name are moved over by themselves.

Saves are found by per-emulator adapters. Fuse Sync and Syncthing use the same ones.

| Emulators | Where their saves are | Save states |
| --- | --- | --- |
| RetroArch | Per core, where its config says (beside the content, or its saves folder) | Per core |
| Lemuroid | Its saves folder, when moved out of Android's private storage | |
| DuckStation, ePSXe, FPse | Memory cards | DuckStation |
| PCSX2, NetherSX2, AetherSX2, ARMSX2, Play! | Memory cards (shared by every game on them, synced as cards) | |
| PPSSPP | SAVEDATA folders by game id, on the memory stick you chose | |
| Dolphin (and MMJR) | GameCube memory cards, Wii saves | |
| melonDS (computer) | The save folder in its settings (`SaveFilePath` in `melonDS.toml`), else beside the game; portable, Flatpak and system config folders | |
| melonDS (Android) | Beside the game for games in its ROM search folders; games opened another way save in its private `Android/data` folder, and Fuse says so | |
| NooDS | Beside the game, or its separate `saves` folder when that setting is on | |
| mGBA (computer) | `savegamePath` in its `config.ini`, else beside the game | |
| Mednafen | `sav/<name>.<fingerprint>.sav` (and `.eep`, `.rtc` for GBA), only between Mednafen installs | |
| My Boy!, My OldBoy! | Their own `MyBoy/save` and `MyOldBoy/save` folders | |
| mGBA, SkyEmu (Android), Pizza Boy, Linkboy, other handheld emulators | Beside the game (a guess where the emulator publishes no layout) | |
| DraStic | `backup/<game>.dsv`, converted to and from a plain DS save | |
| Mupen64Plus, M64Plus FZ | The game's EEPROM, SRAM, FlashRAM and paks, converted to and from RetroArch's one file | |
| Flycast, Redream | VMUs | |
| Yaba Sanshiro, Saturn emulators | Backup RAM | |
| MAME | NVRAM by set | |
| ScummVM | Its saves by game | |
| RPCS3 (and on Android) | dev_hdd0 savedata by serial | |
| Vita3K | ux0 savedata by title id | |
| shadPS4 | User savedata by serial | |
| Azahar, Citra, Lime3DS, Mandarine | The emulated SD card, by the cartridge's title id | `states/<title id>.<slot>.cst` |
| Eden, Citron, Sudachi, yuzu and forks | The emulated NAND, by title id | |
| Ryujinx | Its numbered save folders, matched by title id | |
| Cemu | `mlc01/usr/save`, by the title id in the game's `meta.xml` | |
| Xenia | `content`, by the title id in the game's name | |

Title ids come from the game itself where they can: a 3DS cartridge's header (a `.3ds`, a `.cxi`
or the TMD inside a `.cia`), a Switch game's `[0100…]` tag or the ticket inside its NSP, a Wii U
game's `meta.xml`. Where Fuse can't read a game's id (a compressed 3DS game, a PSP or PS3 game with
no serial, a Vita, PS4, Wii or Switch game it can't open), the first play here teaches it: Fuse
notes the emulator's save folders before the game starts and, after it closes, takes the one or
few game folders that changed as that game's, then keeps them in step from then on.

On Android, emulators that ask for a folder on first start (Azahar, PPSSPP, Dolphin, DuckStation,
ARMSX2, Flycast) keep the answer in their own private settings. Fuse finds that folder by its
layout wherever it is, in the device's storage or on a card (two folders down anywhere, three
under folders named for emulation, never inside photos, music or other apps' private storage),
after the folder chosen in Save Folders and the usual places. With several, the one holding the
game's save wins.

**How sure Fuse is.** Every save spot says whether Fuse **found** it (read from the emulator's own
settings or seen on disk), **knows** it (the emulator's documented or source-code default), or is
making a **guess** (an emulator whose source and layout aren't published). Save Folders shows it, so
a guess is never presented as fact.

| Emulator | Evidence for where its saves go |
| --- | --- |
| melonDS (computer) | Its source: `SaveFilePath` under `[Instance0]` in `melonDS.toml`, relative to the config folder; config in a portable folder, `~/.config/melonDS`, the Flatpak's, `%LOCALAPPDATA%` or `~/Library/Preferences` |
| melonDS (Android) | Its source: a game found through a ROM search folder saves beside it; one opened without that folder's permission saves in the app's private files |
| NooDS | Its source: `savesFolder` in `noods.ini`; `1` means a `saves` folder beside the settings |
| mGBA (computer) | Its source: `savegamePath` in `config.ini` under `[ports.qt]` or `[default]`, relative to the config folder |
| SkyEmu | Its source on computers (beside the game); unpublished on Android, so a guess there |
| Mednafen | Its documentation and source: `sav/<stem>.<md5>.<ext>`, so its saves only fit another Mednafen |
| My Boy!, My OldBoy! | Their own folders, as their users and documentation describe |
| Pizza Boy, Linkboy, Seedless DS | No published layout: a guess, beside the game |

**Not a save.** Logs, caches, shader caches, temporary and partial files, and system leftovers
(`Thumbs.db`, `desktop.ini`, `.DS_Store`) in a save folder are never part of a save, and a folder
only they changed is never learned as a game's save folder.

**Folder saves.** A save that is a folder (a PSP or PS3 game's save folder, a Switch save) goes
with its whole structure: every nested folder, empty ones included. Putting a save in place makes the
folder exactly that save: files of the save it replaces that it doesn't have are removed (they are
kept first, as that save's own version in the history), along with the folders that leaves empty.

**Never silent.** When a played game's save can't be kept to send (its folder isn't reachable, or
Fuse found no save where it looked), a message says why and what to do, once per game while Fuse
runs. A save that simply didn't change says nothing. A save is only put where the
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

**While it runs**, Fuse watches the save. When the game writes it and leaves it alone for a few
seconds, Fuse keeps it as a new version and sends it, so the host has the newest save even if the
device is put away mid-game. On Android, a quiet notification ("Keeping your save in step") keeps
Fuse running while the game is open, and when the screen goes off (a handheld's lid closing) Fuse
sends the save at once, staying awake only for those few seconds.

**After it closes**, Fuse waits a moment for the emulator to finish writing, counts the session,
keeps the save as a new version, and sends it. A short message says when a save reached the host,
when one from another device was put in place, and when the newest can't be used here.

**Another device on the same game.** Devices tell the host what they are playing. Starting a game
another device is playing, or has just stopped and is still sending, asks first: wait for its save
(the game starts by itself once it arrives) or play with the newest save the host has. A device
that went quiet mid-game (asleep or switched off) is said to be quiet, with when its last save
arrived.

**When both sides played** since they last agreed, Fuse asks which save to use, showing where and
when each was saved and how long the game had been played by then: **Use This Device** or
**Use Fuse Sync**. The one not chosen is kept in the game's history as the other side of a
conflict. Timestamps alone never decide: every version records the one it was made from, so a
conflict is told by history, not clocks.

## Every device, by itself

Fuse Sync is a household system: every device that has a game converges on its newest save, in the
background, without anyone launching it there. When a device sends a new save, the host tells the
others (its journal), and each one that has the game puts the save in place. A device that was off
or away catches up on every game when it comes back; a new device catches up when it joins; a
device without the game skips it, and gets the newest save the day the game arrives. Folders the
save needs are made as needed.

A device never writes a save under a game that is being played on it, and never into a folder that
holds another person's save (a shared device whose emulator keeps one save per game). When both
sides played, nothing is put in place in the background: the choice waits for the person, at
launch, as above.

Each device tells the host what is in place for each game:

| State | Meaning |
| --- | --- |
| Current | This device has the newest save in place |
| Behind | It has an older one (it hasn't caught up yet, or is off) |
| Conflict | It played too since they last agreed; the person chooses at launch |
| Another emulator | Its emulator reads another format, which Fuse can't convert |
| Unreachable | The save's folder isn't reachable here (a missing card, private storage) |

A game's page on the Sync tab sums it up: **Synced everywhere**, or **5 of 6 devices current** with
each device and its state. Hosts before 0.3.6 don't keep these states; devices simply carry on.
Tests run six devices against one host: a save fanning out, an offline device catching up, a
device joining late, a device without the game, a conflict left for the person, and per-person
folders on a shared device.

## Save history

Every game has a **Save History** (in its options), with versions from every device. Any version can
be put back (what is here now is kept first), and the version put back becomes the newest on every
device, so it sticks. Any version can be **kept for good**. The rest are tidied over time: the last
10, then one a day for two weeks, then one a week for two months. The newest is always kept, and so
is the other side of every conflict. Copies kept for safety (the other side of a conflict, what was
there before a version was put back) are history only: they never become a game's newest save by
themselves, kept for good or not.

Every file is stored by its SHA-256 and checked after every transfer; a file is written to a temporary
name, checked, and only then moved into place, so a cut connection never leaves half a save.

## Offline, drives and devices

Everything works without the host. Changes queue on the device (play time, favourites, saves) and
go up in order when it answers. A drive or card that isn't there is **unavailable**, never deleted:
its games and saves are left alone until it comes back.

A host serves around 50 devices. Changes reach the others within seconds (they listen for the host's
journal). On a slow connection what matters most goes first: records, then the save a starting game waits for, then the rest.

## Reaching the host from outside

At home, devices find the host by themselves. Away, the host needs an **outside address** that
reaches it over the internet: a VPN such as Tailscale or WireGuard, or an https reverse proxy or
tunnel in front of port 47311. Set it once on the host (Settings, Addons, Fuse Sync, Address From
Outside), or simply connect a device through the tunnel: the host notices the name it was reached
by and keeps it. Every device learns it from the host, so none has to be told. A device can still
use its own (its Outside Address setting). Fuse uses the home address when it answers and the
outside one otherwise, and tries it too while connecting when home doesn't answer. Once away, every
call goes straight to the outside address: home is only looked for on the side, at most once a
minute and for a second and a half, and Fuse switches back to it the moment it answers. Saves go up
and come down four files at a time, and big answers (a profile's records for a large library, the
Hub) come compressed.

A Cloudflare tunnel works well: point it at `http://localhost:47311` on the host and use its
address (`https://sync.example.com`) as the outside address. Leave Cloudflare Access off for that
hostname (or let Fuse's requests through), since Fuse signs its own requests and can't answer a
login page. An address typed without `https://` is reached securely when it is a name on the
internet and plainly when it is at home. On Android, Fuse Sync uses Android's own secure
connection, as the rest of Fuse does.

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
Every game is listed at once; a game's versions and files are brought when it is opened, so the Hub
opens quickly however much the host keeps.
From away it is at the outside address too (`https://sync.example.com/hub`), after signing in with
the host account. It only shows; changes are made in Fuse. In Fuse, Addons, Sync is the same Hub,
with Sync Now, Switch Profile and Add a Device.

## The host account

**Host Account** (on the host: Settings, Addons, Fuse Sync) is one username and password for the
times nobody is at a screen:

- **Joining from away.** A device asking to join can choose **Use the Host's Account**. It
  stretches the password itself and proves it knows it over the key exchange of that one request,
  so the password never travels, and a recorded request can't be replayed.
- **The Hub from away.** The Hub at the outside address asks to sign in; the session lasts twelve
  hours and is kept in a cookie only the browser sends back to that page.

Five wrong passwords in ten minutes make that caller wait. Without an account, devices join only
with someone at a screen, and the Hub opens on the host computer only.

## Security

- Pairing uses a one-time code, valid for ten minutes and five tries. The device's secret is sealed
  with that code (AES-GCM, key from PBKDF2) and never sent in the clear.
- Asking to join uses a key exchange instead (ECDH on P-256): the device's secret is sealed with a
  key only the device and the host have. Both screens show six digits made from both public keys,
  so someone in the middle would show a different number. Only a device already in (or the host)
  can let one in, and a request lasts five minutes.
- The management calls answer only on the host computer itself, and so does the Hub unless the
  host account signs it in. A call that a tunnel or proxy on that computer carries (cloudflared, a
  reverse proxy) counts as from outside.
- The host account's password is kept as a salted PBKDF2 hash (210,000 rounds). Joining with it
  sends a proof bound to that one key exchange, never the password; signing in to the Hub sends it
  only over the https address.
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

Setting up a host asks **where to keep saves**: Fuse's own folder (above), or one you choose, such
as a bigger drive. **Where Saves Are Kept** in the host's settings moves them later: the host stops
for a moment, everything is copied and checked, and only then is the old folder cleared.

**Backup and moving the host**: stop the host, copy its `host` folder to the new computer's, and
make that computer the host; every device keeps working, with the same profiles and history.
Fuse's own Backup (Settings, Backup) also keeps the person's records and settings.

## Leaving

**Unlink This Device** stops syncing and keeps everything on the device exactly as it is.

**Turning Fuse Sync off** forgets the host: its link and anything still waiting to be sent. Fuse
asks first: with **Keep Profiles Here** (the default) every profile on the host (Admin aside)
stays as this device's own, with its newest records brought down first (a profile with a PIN when
this device has opened it), the saves here and the PIN last typed here; everyone else's saves parked here are kept
as plain files in a `kept` folder in Fuse Sync's data folder. **Forget Them Too** lets the
profiles go as well, saves that never reached the host kept first the same way. Unlinking asks the
same. The device keeps its games, the saves in its emulators' folders, its library, settings and
Home as they are now. Turning it on again and joining a host brings the profiles kept here along.

**Stop Hosting** stops the host and its service and keeps all of its data on the computer, so
hosting again carries on where it was. **Delete This Host** removes every profile, save and device
link kept there, after asking twice; the computer keeps its own games, library, settings and
Home, and other devices keep everything they have. Deleting the host, or moving its saves, only
ever deletes or moves the host's own files: a host set up in a folder that already held other
things keeps its files in a `Fuse Sync Host` folder of its own there.

**Erase Fuse** (the bottom of Settings, About) starts Fuse over as new: its library, settings,
profiles, art and caches go, and Fuse Sync is let go of first (a host is deleted, a device forgets
its host). Game files, emulators and the saves in the emulators' folders stay.

A new profile starts with the device's current look, Home and settings, which then become the
profile's own. Resetting Home (Settings, Home) changes this device only, and **Undo Home Reset**
brings back the Home from before.

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
