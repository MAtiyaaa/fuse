# Fuse 0.1.2 - The Visual Update

## New

A release about how Fuse looks and feels: touch that moves like a phone, a look of its own on every
screen, themes anyone can make and share, and a website to download it from.

- **Move things like on a phone.** Hold and drag, and Fuse answers the way a phone does: the item
  lifts on a soft shadow, the others slide out of its way, and it glides into place when you let go.
  - **Home, Flow.** Hold a shelf by its title (or hold a widget) and drag the whole shelf up or down.
    "Move this shelf" in a game's options lets you drag it straight away.
  - **Home, Channels.** Hold a channel and drag it anywhere on the board; the others reflow around it.
  - **Systems** move more smoothly than before, with haptics for lifting, passing a place and dropping.
  - Holding and letting go without moving still opens the options, as before.
- **One family of surfaces.** Panels, menus and dialogs catch the light along their top edge, as
  tiles do, and screens without art (Settings, Apps, Achievements) get a soft second light for depth.
  Focus, tabs and progress share one crisp accent, so the art does the lighting.
- **Themes anyone can make.** A theme is a small file that starts from a built-in theme and changes
  what it likes (docs/THEMES.md).
  - Settings, Appearance, Theme now opens a gallery of live previews, each drawn in its own theme.
  - Add a theme from a link (GitHub and gist pages work), pasted text or a .json file.
  - Fuse shows what a theme is before adding it, and keeps every theme readable: text that would be
    hard to read is adjusted, and it tells you what changed.
  - Copy any theme, built-in ones included, as a file to start your own.
- **Starlight,** a new built-in theme: a slow drift of stars under a violet glow.
- **Posters.** Settings, Appearance, Game art: keep square box art (as before, the default) or show
  tall posters on Home, in the library and in Cartridge.
- **Collections and Series.** The Collections tab has two views: your own collections, and the series
  Fuse found, each with its own place.
- **A website.** [matiyaaa.github.io/fuse](https://matiyaaa.github.io/fuse/) looks and moves like Fuse
  (arrow keys and controllers work too), always offers the latest version for Android, Windows, macOS
  and Linux, and lists themes to add. Its screenshots, like the README's, now show a library of
  well-known games with the art Fuse fills in by itself.

## Changed

- **One tap opens.** A tap opens a system, a collection, a channel or a Cartridge action straight
  away. Games still show first and play on a second tap, so browsing never starts one.
- **More games on a system's page.** Scrolled down, its header gives back its whole height: the
  toolbar moves beside a one-line title, and a third row of games fits at 720p.
- **A slimmer Cartridge page.** The activity card is gone. While something downloads, a slim line
  with its progress sits above Browse by system; tap it for Downloads.
- **Settings put the important things first.** Media and Scraping starts with filling art; sources
  and their keys fold into one group at the end. Systems and Appearance are tidied the same way.
- **Built-in themes refined.** Wave moves to plum and coral with gold ribbons, Channels gets soft
  pinstripes, and Glass, Crossbar, Orbital, Blades and CRT each gain a second colour. Daylight's and
  Channels' accents are a little deeper, so text on them reads well.
- The library layout that was called Box art is now called Grid, so it can't be confused with Game
  art.
- Settings, About has a link to the website.

## Fixed

- System logos on the bottom screen (and everywhere else) were blurry: they were drawn at the logo
  file's small size and stretched. They are now drawn at the size they are shown.
- A short swipe on the cover carousel could start the game it settled on. A swipe now only moves,
  and a flick carries on with the finger's speed.
- The onboarding theme preview used the current theme's background colour instead of the previewed
  theme's.
- A game's page said "1 players" for a game for one player.
