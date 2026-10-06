# Fuse 0.3.6.5 - The Unity Update

Fuse sized for a 4K TV by itself, a Systems page you arrange like Home, sharper backgrounds on big
screens, clean subtitles, downloads that open their game, and Steam kept out when you say no.

## New

- **Arrange your systems like Home.** The Systems page is now a board, arranged exactly as Home's
  Channels board is: hold a system (or hold confirm, or Options, Arrange Systems) and drag it
  anywhere or carry it with the D-pad, resize it by its handles or by holding Options with the
  D-pad (a large system, a wide one, as you like), take one off and add it back, and put systems on
  pages of their own (handhelds on one, home consoles on another), turned with the right stick, a
  swipe or the dots. Undo and Reset are there while arranging. It works by controller, keyboard,
  mouse and touch, follows your profile like Home does, and its order is the systems' order
  everywhere else.
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

- **System logos fit every card.** A system's logo now sits inside the card's coloured part, clear
  of its art panel and its rounded corners, at any card size (small, wide, large, on a phone or a
  TV); before, wide logos like PS2's, Genesis's and the NES's ran under the corner and were cut.
- **The PlayStation 5 has a logo.** The art pack has none for it, so Fuse drew its own PS5
  wordmark in the same thin-line style as the PS4's and PS3's.
- **Downloads opens the game.** A game that finished downloading plays when chosen on the Downloads
  page, or opens its page when Selecting a game is set to show it first, like choosing a game
  anywhere else.
- **A RomM game keeps its art when downloaded.** The art Fuse found for a RomM game is the
  downloaded game's straight away; Fuse no longer looks for it all again.
- **Sharper backgrounds on big screens.** Background art is decoded for the screen Fuse is on now
  and at closer to its full size, so it stays crisp on a TV: a Deck that started on its own screen
  and was docked afterwards no longer stretches art made for 1280 pixels across a 4K TV, and
  Balanced allows art past 1080p's on a big screen (High quality up to 4K).

## Fixed

- **A RomM game's page said Download after it was downloaded.** It now turns into the game's own
  page as soon as the game is in the library, with Play.
- **Steam showed Fuse without its art.** Fuse's capsule, wide capsule, hero, logo and icon only
  went to the entry Add Fuse to Steam made, and only with Steam closed (never the case in Desktop
  Mode). Fuse now gives its art to every Steam entry that starts it, including one made with
  Steam's own Add a Non-Steam Game, each time it starts, without touching Steam's list, so it is
  safe while Steam runs. Steam shows it the next time it draws its library.
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
