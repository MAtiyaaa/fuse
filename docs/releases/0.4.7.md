# Fuse 0.4.7 - The Cool Running Update

A notebook for every game on the second screen, a page of its own for every Android port, each
person's own emulators, and an interface that does far less work for the same motion: cooler, longer
on a battery, and steady on a handheld.

## New

- **A notebook in the Journal.** On the second screen while you play, a scroll down from the game is
  its Journal: where you are with it, your rating, and your notes as a notebook. As many pages as you
  like, each as long as you like, ruled like paper with a margin. Touch a page to write with the keys
  that rise at the foot of the screen; the line you are writing stays above them. Pages keep
  themselves a moment after you stop, and again when you're done. New Page starts the next, the
  strip above flips between them by their first words, and Tear Out takes one out (a second tap
  asks first). Notes are kept with the game and your profile, so they are there on every device, in
  backups, and on RomM.
- **A page for every Android port.** Choosing a port opens its page: its box art, what it is, which
  of your games it plays, where to get the game, and its buttons (install, open, attach, its newest
  release, its project page, uninstall). Nothing installs until you press Install.
- **Your own emulator for each system.** Two people sharing a handheld each start a system's games
  in the emulator they picked: one in Eden, the other in Ryujinx. The choice is the person's, for
  that kind of device, and follows their profile; anyone who never chose gets the device's.
- **Filters for games on your other devices.** See all on Other devices sorts by name, date added,
  size or system, and narrows to one system or one device. The heading says how many of how many
  are shown.
- **Devices by what they are.** Sync, the household's games and join requests show each device with
  an icon of its kind: a two-screen handheld (an AYN Thor), a handheld (a Steam Deck, a ROG Ally, a
  Retroid), a TV or living room computer, a server, a laptop, a desktop, or a phone.

## Changed

- **Android Ports, rebuilt.** The heading folds away as you move down (with the controller or by
  touch) and the box art shrinks a fifth, so three rows of ports show at once; on a handheld's short
  screen, or a narrow one, the ports are slim cards for the same reason. Flipped (the menus on the
  lower screen) it fits the same way, and a port's page puts its buttons under the case where the
  screen is narrow.
- **The second screen while you play.** The game's logo sits in the middle with how long you've
  played this session, this week and in all under it, every word whole, and the next achievements
  to earn below. Journal and Quit are two touches beside it; the Journal itself is a scroll away,
  and while you read it the page dots step aside.
- **Time to beat, told plainly.** A game's page says "26 hours to beat", and how long you've played
  it on its own, instead of "0% of 26 hours to beat".
- **Shows by the year they began.** Jellyfin shows say the year they started ("2010", not "2011 to
  2019"), posters no longer count episodes, and the upper screen names what is chosen and its year
  only.
- **The Library's places read clearly.** This device, RomM and Other devices put their covers to
  the right, fading into the card's own colour, so the name and count on the left read on any art.
  The chosen card's light no longer reaches the next heading, and See all says every game there:
  1,923, not the 40 shown in the row.
- **Continue playing always has something.** Someone who last played a few weeks ago (another
  profile, back after a while) finds their last games there, instead of "Games you play show here".
- **RomM's copies told apart by size.** Two games on one system that RomM names the same, with
  nothing else between them (one game put on the server twice), say how large each is.
- **Much less work for the same motion.** Nothing moves less than before; every move costs less:
  - Moving through the Library builds again only what changes (the chosen game's stage and the
    selection), not the whole page for every step.
  - The top line no longer rebuilds itself each time the chosen game changes.
  - Home and Systems: a widget already where it belongs does nothing when the board is shown again
    (it was four animations each, hundreds on a page of systems), and on a handheld the Systems
    header folding away no longer builds the board and the page again on every frame of the fold.
  - The second screen follows a quick run of presses once it rests, and reads a game's details once
    for its room and its page together, where it read them twice (a dozen lookups each) on every
    step.
  - A tile asks for its art once, at its size, where it asked twice and recomposed again a moment
    after appearing.
  - The theme's moving room rests while a game's art covers it entirely, on both screens: unseen,
    it was a whole screen drawn again thirty times a second.
  - Jellyfin's logos are fetched at one size, by the main screen and the second screen alike,
    instead of twice.

## Fixed

- On Android the Sync page offered Sign in as the host, which an Android device can't be. It no
  longer does.
- The host could read as away ("seen 7 days ago") on your other devices while it was online and up
  to date, even while RomM on the same computer showed online. The host always counts itself as
  here.
- Choosing a port on Android Ports started installing it. It opens the port's page.
- On a handheld's short screen the Android Ports grid began below the hints, with almost nothing of
  it in view.
- The second screen took a long time to show a Jellyfin item's details: its logo was fetched again
  at another size.
- Continue playing was empty for a profile whose last game was more than two weeks ago.
