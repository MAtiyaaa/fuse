# Fuse 0.4.2.5 - The Native Update

Your newest save first, every time: a game no longer starts quietly with an older save while the
newer one is still on its way, and a test run can show Fuse where any game's emulator really keeps
its saves. None of it has been tried on a real Bazzite PC, Steam Deck, AYN Thor or phone yet; it is
tested end to end with a real Fuse One host and test saves on a computer.

## New

- **Find Where It Saves.** In a game's Save History, a test run starts the game with save sync
  paused for it. You make one save and quit, and Fuse sees where it appeared. It then keeps the
  save in step there from now on: as that emulator's save folder (the same as choosing it in
  Settings, Save folders), or as the game's own folders where Fuse can't read a game's id. Your
  own save is put where the game reads it right away (or, with no save of yours anywhere yet, the
  test save becomes your first). The test save is kept aside on the device. When the save landed
  somewhere Fuse can't use yet, it says where and changes nothing.

## Changed

- **A game waits for your newer save.** When Fuse One is slow to send your newest save, or this
  device is offline but knows Fuse One has a newer one, the game doesn't start with the older save
  here. You choose: keep waiting (the save keeps coming meanwhile), play with this device's save
  (the newer one stays safe, and you pick between the two afterwards), or not now. Offline with no
  newer save known of, games start straight away as before.
- **A newer save that didn't finish coming down is remembered,** across restarts, until it is in place.

## Fixed

- **Playing anyway can't be overwritten mid-game.** A save still coming in the background is
  stopped before the game starts, so nothing lands while it runs.
- **A save Fuse couldn't find says what to do:** the message now points to Find Where It Saves.
