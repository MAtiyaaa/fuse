# Fuse 0.3.6.5 - The Unity Update

Fuse sized for a 4K TV by itself, sharper backgrounds on big screens, clean subtitles, and Steam
kept out when you say no.

## New

- **Fuse sizes itself for the screen.** On a big screen with many pixels, like a 4K TV from a
  Steam Deck in Game Mode, a computer counts every pixel as one, so Fuse was drawn at a quarter of
  its size: a tiny Home in a corner and a Library twenty covers wide. Fuse now draws everything as
  large as on a 1080p screen there (twice the size on 4K, a quarter more on 1440p), and again when
  the screen changes, like docking. Handhelds, phones and 1080p TVs look as before.
- **Interface size** in Settings, Display, This screen: Automatic (it says what it picked), or 100%
  to 300% for a size of your own.
- **Steam games** in Settings, Library, Steam: a switch for whether Steam's games are in your
  library at all.

## Changed

- **Sharper backgrounds on big screens.** Background art is decoded for the screen Fuse is on now
  and at closer to its full size, so it stays crisp on a TV: a Deck that started on its own screen
  and was docked afterwards no longer stretches art made for 1280 pixels across a 4K TV, and
  Balanced allows art past 1080p's on a big screen (High quality up to 4K).

## Fixed

- **Saying no to Steam is kept.** No thanks (or Skip) on setup's Steam step now stays no: Steam
  shortcuts a games folder brings along, like the `steam` folder EmuDeck and ES-DE make, no longer
  put a Steam system in the library anyway. On a computer that never added Steam, they are left
  out too. The switch in Settings, Library, Steam brings them back, and adding Steam games turns it
  on.
- **Subtitles looked doubled and blurred.** Subtitles inside a video (on a computer every text
  subtitle reads as an ASS one) were outlined in their own white instead of black, so the letters
  looked thick, doubled and soft. Their outline is black again, as the subtitle says, and a cue read
  twice after the stream catches up shows once.
- On KDE Plasma with Wayland, Fuse (like every X11 app) is stretched by the system on a scaled
  screen and looks soft. Set System Settings, Display and Monitor, Legacy Applications (X11) to
  Apply scaling themselves: Fuse is then drawn sharp and sizes itself for the screen.
