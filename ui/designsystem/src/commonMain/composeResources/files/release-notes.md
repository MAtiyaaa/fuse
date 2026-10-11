# Fuse 0.4.2 - The Native Update

The Native Update makes Fuse feel at home on Linux, SteamOS and Android: Steam and Decky as part of
setup, controllers that are understood, Fuse One as the household's one place for profiles, saves
and the library, and a first step toward Fuse knowing emulators for itself.

## New

- **Steam and Decky in setup.** Setup asks once whether to integrate with Steam, then offers the
  Fuse Decky plugin (on Linux, where Decky runs) and Fuse in Steam's library, each optional and
  each named on the rail. Nothing privileged runs without asking, Steam is never closed for it,
  and a finished part moves straight on. Settings, Library has the same controls afterwards: add,
  update or remove Fuse in Steam, and install, update or remove the Fuse plugin and its bridge.
- **Fuse Decky Bridge.** A loopback-only, token-protected bridge between Decky and Fuse, with the
  Fuse Decky plugin source in tools/decky.
- **Guided controller setup.** Controls has Guided and Edit. Guided asks for each button in turn,
  picks the controller when several are connected, and is left by holding any button for five
  seconds. Pad pictures show sticks as sticks (AYN Thor and others).
- **Multiple controllers.** Fuse knows which controllers are connected and which one moves around
  Fuse (chosen per profile in Settings, Input); any controller does while the chosen one is away.
- **Fuse One Host.** The household host on its own, without the app: a Docker image, a Compose
  file and a one-line installer (tools/host), built in CI and published to ghcr.io on release.
- **Sync without a household.** A device keeping its own profiles has its Sync page: each person's
  games, play time and the saves kept for them here, with what a host would add and the two ways
  to set one up.
- **Keys for the whole household.** SteamGridDB, TheGamesDB, IGDB, ScreenScraper and
  RetroAchievements keys added on one device reach every device through Fuse One, sealed like the
  RomM and Jellyfin sign-ins. A key removed on one device stays off that device only.
- **Fuse Intelligence.** Each emulator's page shows how far Fuse has checked it on this device:
  detected, installed, able to run, set up, and launch verified (a game started through Fuse ran
  in this install). Fuse learns where emulators keep their settings by looking, never touching:
  everything outside Fuse is seen through an interface with no way to open, write, move or delete
  a file, and what it learns stays in Fuse's own file. It looks again only when an install changes.
- **Stream from your computers.** With streaming on, the Library's All view has a shelf of every
  app the household's computers offer through Sunshine or Apollo, once however many computers have
  it, wearing the library's art when the library has the game. With several computers offering
  it, Fuse asks which, saying which are on, asleep (woken first) or not answering.
- **Haptics for what happens.** A light vibration with each notice, a firm pulse when something
  finishes well and a double knock when something didn't. On Windows and macOS the controller in
  use rumbles too, scaled by Settings, Input, Vibration.
- **Fuse's own files in Storage.** Beside the games, Storage shows the saves Fuse One keeps on this
  device (each file once, however many versions share it), the household's on a host, saves of
  profiles removed from this device, and the art and caches Fuse fetched. Nothing is deleted to
  make room: Fuse never removes the last copy of a save.
- **When transfers move.** Downloads has "Only while charging", "On battery, wait below" and
  "Quiet hours" beside Wi-Fi only, the bandwidth limit and the rules for while you play. A held
  transfer says what it waits for and carries on by itself. Saves always go: they are small.
- **Patches.** A game's options apply an IPS, UPS or BPS patch (a translation, a hack, a fix) as a
  new copy beside the game, which joins the library; the game itself is never changed. UPS and BPS
  patches are checked against the game they were made for, and one for another version is refused
  with both checksums rather than making a broken copy. A copier header is left off when the patch
  expects it.
- **Steam achievement rarity.** The share of players who have each Steam achievement, and when it
  was unlocked, under the chosen badge on a game's page.
- **Add a game folder.** A folder holding a game with its updates, DLC or discs, read the way the
  library scan reads a system's folders.
- **Updates and DLC.** A game Fuse took for its own can be made an update, DLC, patch or mod of
  another game of the same system, and back again.
- **Fuseline 4.1.** Cues: one clock that many parts of an entrance play from, so a page of tiles
  coming in is one frame callback. Fuseline is publishable on its own as an SDK
  (io.github.matiyaaa.fuseline:fuseline:4.1.0); see docs/fuseline-sdk.md.
- **Quick menu sections.** Along the top of the quick menu, as on Steam's: Quick settings (your
  grid), Downloads (what is moving with its progress, Pause everything, and what finished lately),
  Performance (the quality choice, Low Power Mode and the performance overlay) and Fuse (its own
  actions). LB and RB move between them, or a tap; the menu opens where it was left.
- **Simple and Advanced theme making.** The theme studio opens in Simple: light or dark, a colour,
  corners, the background, glass and the quick menu's look, in six steps. Advanced is the full
  studio, every colour, amount, motion and sound. Switching keeps what was chosen, and the studio
  remembers which you like.
- **The quick menu in your theme.** A theme says how the quick menu's tiles and widgets are drawn:
  Soft, Solid, Outlined or Glass (the `quickMenu` key in a theme file). Built-in themes each have
  the one that suits them, and the studio shows a quick menu on its preview while you choose.
- **Liquid glass.** With Glass on, every menu, sheet, dialog and notice that floats over the
  interface is a pane of glass over what is behind it: the screen frosted and its colours richer,
  bent along the pane's rim and lit around its edge, with a lighter tint. It is made small and drawn
  large, and refreshed about ten times a second at most, so it stays light; with Low Power, without
  a graphics card or before Android 12 it is the tinted pane it was.
- **A mark for Fuse One.** Fuse's own frame and fuse, the fuse rising into a 1 with the spark at
  its top, wherever Fuse One appears (docs/assets/brand/fuse-one-mark.svg).
- **Arrange the top line in place.** Hold a tab (touch, mouse or confirm) and it lifts; move it
  along with the D-pad or a drag, as Addons' tabs move, and put it down. Home stays first.

## Changed

- **Fuse One** is the name of Fuse's household system throughout (profiles, Fuse Save Sync and the
  Fuse Library); every one of its settings is in Settings, Fuse One, in those parts. RomM is called RomM, and its games are in the
  Library's All view with every other game.
- **Library.** All, then Installed; All gathers this device, RomM and other devices as shelves,
  with Installed as one of a tile's own marks. Library opens on All, or on Installed when chosen in
  Settings. View As is gone.
- **Sync page.** How things stand is always in view, with the last saves as they went; history
  lasts across restarts and names the device; the top line is Sync Now, Library, Switch Profile
  and More.
- **Steam achievements** come from one Steam account only: the one chosen in Settings, Library,
  Steam (offered when Steam remembers more than one), else the one Steam remembers as most recent.
- **Controls.** "Detect my buttons", "Button layout" and "Swap confirm and back" are one
  "Controller setup".
- **Sound.** A master switch and volume over music and interface sounds.
- **Mouse.** Joystick-style pointers and tablets are no longer taken for controllers, hovering never
  scrolls a list, any pointer that isn't touch clicks once, and hints follow the mouse.
- **Game Mode on a TV.** Fuse says when gamescope enlarges its picture, with the fix, and keeps
  the anti-banding grain from showing as squares.
- The game page names the game's folder and file, small, at its end. Option sheets show Select
  and Back. Hidden or removed games never ask for an art choice.
- Developer, Start Onboarding can rehearse setup as Linux, Windows, macOS or Android.

## Fixed

- Saves are found where emulators really keep them, checked against each one's own source:
  DuckStation's newer Windows folder (%LOCALAPPDATA%) and portable settings.ini, Eden's own Save Data
  and NAND directories (on Android too, with the setting to use named), Kenji-NX read as the Ryujinx
  it is built from, and RPCS3's dev_hdd0 where its vfs.yml moved it.

- A choice made just as a profile switch or a sync round was being applied (a theme picked the
  moment after switching) is no longer thrown away; it stays the person's newest choice.
- A profile switch records the outgoing person's latest choices first; someone new to a device
  starts from Fuse's defaults, never the previous person's look.
- A PIN-protected profile opens offline against the PIN entered on this device before.
- Games that are another game's parts never show among removed games.
- Phone Link starts on the next port when its own is taken (by another program, or by connections
  that just closed), instead of never starting and never saying why.
- Restarting Fuse on a device that keeps its own profiles no longer shows the profiles for a moment
  with nobody chosen.
- The Controls page no longer crashes on its tabs.

## Acceptance

Tested by the automated suites in CI (Linux, with Windows and macOS packaging) and by rendered
screen audits. Not verified on physical hardware for this release: SteamOS Gaming Mode, the Decky
runtime and plugin installation on a real Steam Deck, the AYN Thor, Android devices, macOS and TVs,
controller rumble on Windows and macOS, and streaming real apps from Sunshine or Apollo (Fuse lists
the apps entered for each computer; it doesn't discover them). On Linux Fuse doesn't drive
controllers' motors.
The Fuse One Host image was built and its installer run in a container; pairing real Android,
Windows and macOS clients with it was not tested.
