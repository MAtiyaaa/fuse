# Jellyfin

Jellyfin is an addon. Fuse uses a Jellyfin server only as the place your films, shows and music
live: browsing, pages, search and playback are all Fuse's own, played in [Fuse Player](player.md).
Nothing about Jellyfin runs, asks the network or shows anywhere until it is turned on in
Settings, Addons, Jellyfin.

## Setting up

Settings, Addons, Jellyfin (a group beside the Store and Cartridge), then Server and playback:

1. **Connection:** Automatic (home when it answers, outside otherwise, and back home when it can,
   never in the middle of something playing), Home only, or Outside only.
2. **Home address:** Fuse looks for servers on the network (Jellyfin's discovery on UDP 7359) and
   offers what answers, at the address the answer came from (a server in Docker reports its
   container's address, which nothing else reaches); or type one, like `192.168.1.20:8096`.
   However it is typed, Fuse tries what it could mean: spaces and a phone's comma for a dot are
   forgiven, a missing or half-typed `http://` is added, the browser's `/web/index.html#...`
   is dropped, and a home address without a port tries Jellyfin's own (8096 over http, 8920 over
   https). It is tested straight away, and the address that answered is what is kept. Android
   allows plain http for this (a network security config), as home servers rarely have https.
3. **Outside address:** the server's web address, with its path if it has one
   (`https://media.example.com/jellyfin`).
4. **Test** each address: the server's name and version, or why it didn't answer.
5. **Sign in** with your Jellyfin user name and password.

The password is used once to sign in and never kept. Fuse keeps the access token, your user id and
the server's id and name in its secret store, never in logs or backups.

## Where it shows

- **Addons, Jellyfin:** Continue watching and Next up (A plays on), your libraries, what's new in
  each, favourites and collections. Every library opens as a grid, loaded a page at a time, sorted
  A to Z, by date added, by release or by rating, showing everything, what you haven't watched or
  your favourites.
- **Pages** for films, shows (with their seasons and episodes), seasons, episodes, albums (with
  their songs), artists, people and collections: art, logo, facts, overview, cast, and Play or
  Resume, From the start, Favourite and Watched.
- **Search:** Jellyfin only (films, shows, episodes, collections, people, artists, albums, songs),
  never your games.
- **Home widgets:** Continue watching, Next up and New on Jellyfin, as board widgets in Fused
  and rows in Network. They are offered only while Jellyfin is on and never added by themselves.

## Playing

Fuse asks the server how to play each item for this device, sending a device profile built from
what its player can decode (codecs and their profiles, levels and sizes, HDR where the device can
show it, sound formats and channels, containers and subtitle formats) and the quality limit for
the route in use. The server answers with the best it can do: Direct Play, Direct Stream
(repackaged) or Transcode.

- **Quality** is set separately for home and outside; "No limit" lets the server send the file as
  it is when the player can play it.
- **Sound and subtitle languages:** when a title starts, the user's languages choose its tracks
  (Server's choice, Always, Other languages only, Signs and songs only, Off). The server is asked
  again only when that differs from its own choice, so a subtitle it must draw in is prepared.
- **Next episode** plays after a countdown when turned on.
- Resume points and watched marks are reported back as you play.
- **Two screens:** Films play on the main screen or the second screen (Settings, Jellyfin). The
  screen without the picture is its remote; the menus stay free to browse, and Play here swaps the
  picture between screens while it plays. With the menus below, the second screen is the touch
  screen, and the screen above shows what is playing. See [Fuse Player](player.md).
- Fuse's menu music stops while anything plays.

## Offline

Pages you have opened are kept on disk per user, so they still open when the server can't be
reached; a banner says so, and playing waits for the server. Pictures are cached by the picture
itself, not by the address used, so switching between home and outside never fetches them again.

## Not included

Downloads for offline playback, and anything Jellyfin outside the Addons tab, its settings page
and its Home widgets.
