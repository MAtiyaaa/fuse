# Fuse 0.4.6 - The Continue Update

Fuse remembers the person now, not only the games: the exact moment you stopped, on any device;
where you are with each game, what you thought of it and what you want to play next; the years
of play you had before Fuse; and your year in play. Games kept compressed are known by their
discs, saves kept anywhere come home, the same hotkeys work in every emulator, and the game in
front can be closed from the quick menu, Steam's Quick Access or your phone.

## New

- **Continue where you left off.** A game left mid-play opens on Continue, with Start fresh beside
  it. Continue starts the emulator on the state the game was left in, on this device or another:
  DuckStation and PCSX2 load their resume state, RetroArch its automatic state. A game left on the
  Steam Deck carries on from the same spot on the PC. On by default (Settings, Library).
- **Your Journal.** Every game can have a status (Want to play, Playing, On hold, Beaten,
  Completed, Dropped), a rating in half stars and a note. It follows you to every device through
  Fuse One, goes both ways with RomM's own marks, and is kept in backups. The game page has a
  Journal card, tiles carry the status, the Library has a Journal tab, and search understands
  `status:` and `rating:`.
- **Stars as on Letterboxd.** A rating is five stars set by the half star: tap or drag across
  them, click (a mouse shows what a click would give first), or step them with the arrow keys or
  the D-pad. Touching the rating already given clears it. On a game's page the stars sit right on
  its Journal card.
- **Up next.** A Home widget of the games you want to play, the latest first. Suggestions never
  offer a game you finished or dropped.
- **Move in.** Settings, Library, Bring in your history: favourites, hidden games, play time, last
  played, ratings, completed games and collections from ES-DE (and EmulationStation, Batocera,
  RetroBat), RetroArch and Pegasus, Steam's own play time when you ask, and your Want to Play list
  on RetroAchievements as Want to play in your Journal. It shows what will change first, never adds
  on top of an earlier import, and the last one can be put back.
- **Your Year.** Play time leads with recaps of this year, this month, last month and every year
  before: the time over the months (or days) as bars, the most played game, the longest session,
  the longest streak, games new to you, games finished in your Journal, and when you play most.
  Save as a picture draws the recap as a poster made for keeping and sharing (1080 by 1350, the
  same from every device) and saves it with your screenshots.
- **Saves from anywhere.** Import brings in PlayStation saves kept on their own (MemcardRex .mcs,
  a PS3's .psv, Action Replay and GameShark files) onto the game's card beside the saves already
  there, whole cards in a wrapper (DexDrive .gme, Connectix .vgs), GameCube .gci saves into
  Dolphin's folder under the name Dolphin gives them, and PlayStation 2 saves from EMS and
  uLaunchELF (.psu), an Action Replay Max (.max), a CodeBreaker (.cbs) or a SharkPort or X-Port
  (.sps, .xps) onto PCSX2's card beside the saves on it, written as the console writes one (or as
  a folder of a folder card).
- **Same hotkeys in every emulator.** Settings, Controls: Select + Start quits, Select + R1 saves a
  state, Select + L1 loads it, Select + the top button opens the emulator's menu, in DuckStation,
  PCSX2 and PPSSPP (RetroArch gets the quit; Dolphin binds hotkeys to one controller by name, so
  its stay its own). Your own hotkeys keep working beside them, and turning it off takes out
  exactly what Fuse wrote.
- **While you play.** The quick menu's Playing page can quit the game (on Linux and macOS it asks
  the game to close as its own window would, so its saves go up as it ends) and open its Journal.
  The Decky plugin's Quick Access shows the game being played with its Journal and Quit.
- **The household now.** Home's devices widget says what each device is playing, and so does
  Fuse One in Steam's Quick Access. Devices hear of a game starting or ending as it happens, with
  nothing asked on a timer.
- **The people you follow.** With RetroAchievements, the Achievements page shows the achievement of
  the week (with whether you have it, and how many players do) and the people you follow, with
  their points and what they last played.
- **Make room.** Storage suggests the games that can go: none a favourite or played in three
  months, each with an identical copy (checked by its files) on RomM or another device, so it comes
  back in a press. Pick them, look them over, then delete as with any game.
- **PPF patches.** The patches PlayStation translations and fixes come as (PPF 1.0, 2.0 and 3.0)
  are applied like IPS, UPS, BPS and xdelta: checked against the disc where they carry a piece of
  it or what they replace, and copied with their changes written in, so a 700 MB disc never has to
  fit in memory. A .cue game's patch goes to its data track, with a copy of the sheet naming the
  patched track, and a game of several discs asks which disc the patch is for.
- **Download later.** Any RomM game, or any download waiting in Downloads, can be kept for later:
  it goes once the device is charging on Wi-Fi with no game running, and never keeps the device
  awake while it waits. Download Now sends it at once.
- **A whole collection at once.** RomM's collections are on its page now, drawn like your own. A
  collection's or a system's page has Download All: it shows how much it is and which drives it
  goes to, beside what is already on its way there, then queues it now or for later. When it
  doesn't all fit, what fits can go, or Make Room is a press away.
- **More like this.** A game's page ends with the games in your library nearest to it, each with
  why it is there.
- **Skip Intro on Jellyfin.** Where your server knows an episode's intro, recap or credits (from
  Intro Skipper, a chapter-based provider or Jellyfin's own media segments), the player offers to
  skip them, or skips them by itself if you ask (Jellyfin's settings: Intros and recaps, and
  Credits, each Offer a button, Skip by itself or Leave it). The credits of an episode with another
  after it go straight on to the next one. Going back into a part skipped by itself leaves it to
  play.
- **Pictures while seeking.** On a Jellyfin server that makes trickplay pictures, the picture at
  the point you are moving to shows above the timeline, by touch, mouse or controller.
- **Lyrics.** Songs on Jellyfin show their words, from a lyrics file beside the song, the song's
  own tags or the LrcLib plugin. Words timed to the song follow it line by line; others scroll
  along with it. The Lyrics button shows or hides them.
- **Subtitles found online.** On a Jellyfin server with a subtitle plugin (OpenSubtitles and the
  like), the player's Subtitles has Find subtitles online: what the plugins found in your subtitle
  language, the ones made for that very file first, each with where it is from. Choosing one adds it
  to the server and plays on with it from the same moment.
- **Sign in to Jellyfin with a code.** Quick Connect: Fuse shows a six-digit code (and a QR for a
  phone on the same network) to approve in Jellyfin on your phone or computer, so nothing is typed
  with a controller. Jellyfin's settings and its setup page both offer it.
- **Play time on screen only, on Android.** With Usage access given to Fuse (Settings, Library,
  Count only time on screen), a game's time stops when you switch to another app rather than when
  you come back to Fuse. Fuse reads it once, as the game ends.
- **Add a widget, as a gallery.** Adding to Home shows every widget as it will look, live and in
  its own shape, under headings, laid out on Home's own columns.
- **Phone Link.** Your phone shows Up next on its Now tab, can quit the game being played, and has
  the Journal on every game's page, the note written on the phone's own keyboard. A game's page
  shows its achievements too: how much of the set is yours, its award, and every badge, a tap away
  from what it asks and when you earned it. The Library on your phone can look through your RomM
  server too, by system or by search, and send a game to the device now or for later.
- **The second screen while you play.** On a device with a second screen, the game being played
  shows there with its Journal and Quit, a touch away.

## Changed

- **Library, All.** The black panels are gone: each place your games are is a card made of its own
  games in its own colour, the stage shows the chosen game's art, and each shelf ends in its own
  games under See all.
- **Jellyfin, page by page.** Continue Watching and Next Up are scenes now: the backdrop, the
  title's logo, the episode and the time left. A library shows the item you are on whole above its
  grid. An item's page has its poster beside it on a wide screen, and the cast open a page of
  their own, with their films and shows, and their dates, birthplace and story where the server
  has them. On a wide screen, search tells the result you are on whole beside the results.
- **Widgets.** Spotlight and Now Playing are drawn like the other game widgets, with the game's
  logo, box and system. Now Playing with nothing running shows the game you played last, and a
  game widget with nothing in it yet shows an empty shelf rather than a blank card.
- **Ports on Android.** The Ports page is box art now, with the chosen port told above it: its
  systems, year and maker, the port and how it stands here. A mark in each corner says whether it
  plays your game, needs attaching or has a problem.
- **Every disc known.** PlayStation, PS2 and PSP games kept as CHD, CSO or ZSO are now known by
  the serial on their disc, as ISO and BIN ones were: their own memory card, patches matched by
  CRC, their own emulator settings and their household identity.
- **Bandwidth is shared.** Your download limit is one budget for every transfer, so a lone download
  gets all of it. Each direction is held back only while a game is being played.
- **Emulator requirements.** Xenia's need for AVX is checked like shadPS4's and PCSX2's.
- **A blocked install says why.** When Play Protect or a device policy blocks an install, Fuse
  says so, what to do, and what Android said.

## Fixed

- Scrolling the Installed list on some Android devices stuttered: each tile blurred its own art
  as it moved. The soft backdrop is now made once, from a tiny copy of the art.
- On a phone, the code for signing in to RomM sat squeezed beside its list, too thin to read: a
  code on show now takes the page.
- RomM's collections were read from the server but never shown anywhere.
- Two downloads to the same drive could both start when there was room for only one: each now
  leaves room for the ones ahead of it.
- A download added while transfers were held (on mobile data with Wi-Fi only, in quiet hours)
  showed as next in line instead of saying what it waited for.
- The Ports list was never used on Android before 13, which can't check its signature: Fuse now
  checks it with a verifier of its own.
- The Ports nightly build failed on a runner without the old GLEW and Theora packages (#111).
- Settings pages and messages said compressed disc images couldn't be read.
- A PCSX2 memory card (8,650,752 bytes for 8 MB) was never recognized for import, and importing
  one of PCSX2's two cards took the other away: it now stays as it was.
- The save import preview named formats by Fuse's own codes; it now says what each save is and
  how it gets to its emulator.
- Applying a patch to a .cue game patched the sheet's text rather than the disc, and a compressed
  disc was called an archive: a compressed disc is now refused with what to patch instead.
