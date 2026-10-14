# Fuse 0.4.2.13 - The Native Update

Profiles switch at once, the second screen lets go of what you left, Jellyfin shows its logos, Fii's
Home is reachable end to end, and the Library's All view is rebuilt.

## New

- **Duo and Trio, each in light and at night.** Two new theme families, each with a Home of its
  own that the theme unlocks and asks about, as Fii's does:
  - **Duo** and **Duo Night**, after a dual-screen handheld's menu. Faint graph paper drifts slowly
    behind everything, with squares of blue lighting in turn. Tiles are flat cards in thin grey
    frames, and the sounds are short, bright blips. Duo's Home puts the time and date in a lit
    panel on a bevelled grey strip, between two squared buttons.
  - **Trio** and **Trio Night**, after a 3D handheld's home. A soft light falls over a fine grid of
    dots, with wide sheens crossing it now and then. Tiles are glossy, rounded icons, and the sounds
    are soft wooden taps. Trio's Home has a rounded white toolbar along the bottom, with glossy
    buttons at each end.
  - Duo's and Trio's Homes stay in Settings, Home, Home style once unlocked, and work with every
    theme in its colours. Moving between a theme's light and night asks nothing.
  - Community themes can use the new pieces too: the `duo` and `trio` backgrounds, the `pip` and
    `tap` sounds, and `"tiles": "panel"` (docs/THEMES.md).
- **Jellyfin on the second screen.** Focusing a film, show or episode shows a card like a game's:
  the logo, the episode and its name, the year, length and rating, where you're up to with a bar and
  the minutes left, and the overview.
- **Ignition sounds.** Fuse's start-up animation now plays a match strike, the flare as it catches,
  then the ignition. The first-start opening has its own strike and flare, and a warm swell when the
  view pulls back and expands. Every sound is made by Fuse's own synthesizer, like the others.
- **The game's options are tabs like the quick menu's.** The sections expand as you move between
  them, with liquid glass where it's on, and L1 and R1 step through them while L2 and R2 jump to the
  first and last.

## Changed

- **Switching profiles is instant.** Someone this device already knows switches straight away from
  what is kept on the device (their saves, settings and play time, as last synced), online or not.
  Fuse One is asked afterwards, in the background, and anything newer is brought in then. A profile
  removed on another device, or a PIN changed there, takes you back to the profile you came from,
  with the reason. Before, a switch waited behind any sync that was running and then for the host,
  which could take minutes. Fuse One's activity log now says how long each switch took.
- **Jellyfin widgets show the title's logo** instead of its name (an episode shows its show's logo),
  falling back to the name where there is no logo. Network's stage shows it too. Albums keep their
  names.
- **The Library's All view, rebuilt.** A stage above the shelves names whatever you're on (its logo,
  where it is, its system and year, or a system's count), always in the same place so the page never
  jumps. Every shelf has a title, a count and its own See all, the shelves only scroll when the one
  you're on isn't wholly in view, and L2 and R2 jump six games along a shelf.
- **Smoother first scroll through the Library.** Tile art is decoded from a small copy kept on disk
  (made once, off the screen's thread) instead of the full picture every time, and the Library
  prefetches a whole row ahead, so a fling through a freshly updated library no longer stutters while
  art loads.
- **Sharper text on a TV.** On Linux, Windows and macOS letters are hinted fully, so their stems land
  on whole pixels and text reads as crisply as on Android. Steam's Game Mode still needs Fuse's Game
  Resolution set to Native for a sharp picture (Settings says so when it isn't).
- **Fewer bands in gradients.** The dark scrims over art on Home, tiles, the player, the Store and the
  setup pages, and the soft light at the top and bottom of the scenes behind everything, now get
  the same fine noise before rounding that the backgrounds have had since 0.4.2.9.

## Fixed

- The second screen kept a game long after you'd left it: back on Home on a widget without a game
  (the clock, the weather), or on a page that sets no game, it showed the last game's art. It now
  follows what's on the main screen and goes back to its idle page.
- **Fii's Home (and now Duo's and Trio's):**
  - Cards and carousels slid under the bar; the board now stops above it.
  - The bar's two buttons couldn't be reached with a controller. Down past the board's last row now
    lands on them (Sections, then the quick menu, Left and Right between them, Up or Back to return).
    The focused button lights its ring, with hints for each.
  - Tiles took two taps to open with touch or a mouse; one does.
  - The bar is compact on short screens and when the screens are flipped, so the board keeps its room.
  - The round menu's liquid glass looked muddy over Fii's white; on bright themes it is now a near
    solid gloss with its shadow.
- **On a game's page,** Down from Play didn't reach the More button when it had wrapped under Play
  (it acted as Right). The button row now moves by where buttons actually are.

## Not in this release

- Native patches on Android. ARMSX2 can keep its data in a folder you choose in shared storage, with
  PCSX2's own layout, so its patches can be reached there. AetherSX2 and NetherSX2 keep theirs in
  Android/data, which Android 11 and later close to other apps. Fuse has no Android side for emulator
  files yet; it is planned, starting with ARMSX2 and PPSSPP.
