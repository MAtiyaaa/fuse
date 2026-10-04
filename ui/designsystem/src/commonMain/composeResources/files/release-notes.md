# Fuse 0.2.8 - The Media & Connectivity Update

## New

- **Jellyfin, in Addons.** Turn it on in Settings, Addons, Jellyfin, and your Jellyfin server's
  films, shows and music join Fuse in a Jellyfin tab of Addons: Continue watching and Next up (A
  plays on), your libraries, what's new in each, favourites and collections. Every library opens
  as a grid, sorted A to Z, by date added, by release or by rating, showing everything, what you
  haven't watched or your favourites. Films, shows, seasons, episodes, albums, artists and people
  each get a page with their art, logo, facts, story and cast, and Play or Resume, From the start,
  Favourite and Watched. Search finds only what is on your server. Jellyfin is only the server
  behind it: every screen and control is Fuse's own. While it is off, nothing about it runs.
- **Setting up Jellyfin.** Connect at home, from outside, or automatically (home when it answers,
  outside otherwise, and back home as soon as it can, never in the middle of a film). Fuse finds
  servers on your network, tests each address, and signs in with your Jellyfin user. The password
  is used once and never kept.
- **Fuse Player.** Fuse plays video and music with a player of its own: a big timeline with chapter
  marks, skips that go further the longer you hold, previous and next episode on LB and RB, sheets
  for sound tracks, subtitles and settings (speed, subtitle size, position, timing and background),
  the next episode counting down at the end, and how it plays (Direct Play, Direct Stream or
  Transcode, and why) one press away. Subtitles are Fuse's own, styled ASS included, and rise above
  the controls. On Android it plays through Media3; on Windows, macOS and Linux through FFmpeg,
  decoding on the graphics card where it can. Music shows its album large with what plays next, a
  queue and repeat.
- **Your languages and quality.** Choose the sound and subtitle languages (subtitles always, only
  for other languages, only signs and songs, or never) and the most a stream should carry at home
  and from outside. Fuse tells the server exactly what this device can play, so it converts only
  what it must.
- **Jellyfin on Home.** Continue watching, Next up and New on Jellyfin, as board widgets in Channels
  and rows in Flow, offered only while Jellyfin is on.
- **Two screens, for films too.** The second screen shows the film or show you're on (its logo,
  facts, how far in you are and a few lines about it), and while something plays it becomes the
  remote: art, time, the timeline, play and pause, skips, next, and the sound and subtitle tracks.
  Flipped, the picture goes to the screen above and the remote stays on the touch screen.
- **Type with your phone.** The on-screen keyboard has a phone key: it shows a code to scan, and the
  phone becomes the keyboard for that field, paste included. A phone already signed in to Phone
  Link opens its keyboard by itself whenever a field opens.
- **Your phone as a controller.** Phone Link's Remote is a full controller: D-pad, face buttons,
  shoulders, triggers, Start, Select and Home, with your controller's glyphs, held either way.
  Turn it on in Settings, Accounts, Phone Link.
- **Steam's own library, on a computer.** On Windows, macOS and Linux, Steam's games come straight
  from Steam: each library is a library folder, its installed games are read from Steam's own
  records at their folders in `steamapps/common`, they start through Steam, games you install later
  join by themselves, and games you leave unticked are hidden. Games added by an earlier version
  move over keeping their play time, favourites and edits. Android is unchanged.
- **Rotation, chosen by Fuse.** Settings, Display, Rotation: Automatic keeps a handheld (or a device
  with two screens) landscape either way up by its sensor, even with Android's rotation lock on;
  Landscape, Portrait, Any way and Like Android are there too.

## Changed

- **Storage is redesigned.** A ring for the drive at a glance, the drives side by side, your
  systems in colour, and the games largest first, with a selection bar that stays in view.
- **Fuseline by Fuse is faster than Compose's own animation engine in every case measured**: about
  a quarter less time per frame for tweens, springs and colour fades, half the time for springs
  given a new target every frame, and less memory throughout.
- **Smoother tabs.** Pages you have visited stay ready, so switching back is instant, and the line
  under the tabs can no longer trail behind them.
- **Tabs scrolled by touch** glide back to where you are after ten seconds.
- **Smoother light.** Large gradients no longer band, and backgrounds are there the moment Fuse
  comes back from a game instead of loading in.
- Settings has **Display** and **Sound** as two sections instead of Screen and sound.
- The second screen stays dark while the startup animation plays, and fades in after.
- Developer options can play setup's opening again.

## Fixed

- Tapping between the keys of the on-screen keyboard no longer closes it; every panel keeps its
  own taps.
- Cartridge appears only where it runs (Android and Linux), never in Addons on Windows or macOS.
- Steam games no longer show as missing. A folder of shortcuts is read once for Steam's games and
  once for Windows', and the Windows pass marked every Steam game missing; and on a Mac the disk
  went offline after a macOS update. Both are fixed.
- Opening a handheld's lid shows the startup animation once, instead of the animation, Standby,
  then the animation again.
- A Thor turned over by accident turns back with the device, instead of staying upside down even
  after Fuse restarts.
