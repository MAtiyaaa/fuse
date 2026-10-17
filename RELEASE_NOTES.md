# Fuse 0.4.2.26 - The Native Update

Every device sees the household's games again however many it has, a device at home talks to the host at home, an update coming down says how much has come and how fast, and Fuse offers a GitHub token when
GitHub limits its checks.

## New

- When GitHub is limiting update checks from your network, About offers to add a GitHub token
  (the Store's, so both use it). Without one, the update still downloads straight from GitHub's
  website.

## Changed

- A Fuse update downloading shows how much has come and how fast ("78 of 180 MB, 2.1 MB a
  second"), and its percentage moves in tenths, so a slow download no longer looks stuck.
- An update found through GitHub's website (when its API has no checks left) has its size asked
  for before it downloads, so its progress shows from the start.

## Fixed

- A device could see none of the household's games in the Fuse Library, and turn down every game
  sent to it ("can't see this game on any device"), however long it waited. A device with games in
  folders (a PS3 or PC game lists every file in it, with its hashes) lists tens of megabytes: the
  host turned such a list away, and a device turned the household's lists away once together they
  passed 16 MB. A device that had kept a copy from before still showed that one; a device just set
  up, or set up again, had nothing to show. Both now take lists many times that size. Update the
  Fuse One host too (About, Update the Fuse One host).
- A device that joined from outside home (through the host's address on the internet) went that
  way even on the same Wi-Fi: "Online, from outside" at home, and games sent to and from it slow,
  each piece going out to the internet and back. The host says where it answers at home, and the
  device goes there whenever that answers as its host.
- An update could sit at 1% for a long time on a slow connection, as if stuck.
- A game with several versions could show under both Installed and Missing: a version whose file
  went while the game is still here under another version is one of its versions, not a missing
  game, so it no longer shows under Missing or counts in the missing games notice. Its record
  stays, so it comes back if its file does.
