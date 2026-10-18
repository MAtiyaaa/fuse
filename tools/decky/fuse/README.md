# The Fuse Decky plugin

Fuse inside Steam Gaming Mode, for [Decky Loader](https://decky.xyz). It is a thin client of the
**Fuse Decky Bridge**: Fuse owns the library, profiles, Fuse One, saves and transfers, and the plugin
only asks Fuse and shows the answers in Steam.

- **Steam's game menu**: Fuse: Add or Open, and Fuse: Remove from Fuse, above Properties.
- **Quick Access**: whether Fuse is running (and Open Fuse through its Steam shortcut), the running
  game with Add to Fuse or Open in Fuse, Fuse One's state, devices and transfers, adding every installed
  Steam game at once, and the emulators' Update all, Install all and Uninstall all.

## How it finds Fuse

While its Steam integration's Decky switch is on, Fuse (Linux) listens on `127.0.0.1` only, on a port
the system picks, and writes the port and a random token to `~/.local/share/fuse/decky-bridge.json`
(or `$XDG_DATA_HOME/fuse/`), readable by your user alone. The file goes when Fuse closes. Every request
carries the token; nothing is reachable from the network. The bridge has no way to read or write saves,
emulator files, household secrets or Steam credentials.

Either side can restart on its own: the plugin reads the file again on its next request. A plugin and
a Fuse that speak different bridge protocols say so instead of guessing (and which of the two to update).

## Changes to Steam's library while Steam runs (bridge protocol 2)

Steam writes its list of non-Steam games when it quits, so Fuse otherwise changes it only while
Steam is closed. With the plugin loaded, Fuse's games go into Steam at once instead: the plugin asks
Fuse every two seconds for waiting changes (`GET /v1/steam/changes`), makes each through Steam's own
running client, and says how it went (`POST /v1/steam/changes/<id>/done`, with Steam's id for a new
entry). The calls are Steam client's as `@decky/ui` types them (`dist/globals/steam-client/App.d.ts`):
`AddShortcut`, `SetShortcutName`, `SetShortcutExe`, `SetShortcutStartDir`, `SetShortcutLaunchOptions`,
`RemoveShortcut` and `SetCustomArtworkForApp` (capsule, hero, logo, header, icon). A change names its
pictures only by kind and format; the plugin fetches each through the bridge
(`/v1/steam/changes/<id>/art/<kind>`, base64), never by a path. A change the plugin doesn't make within
15 seconds is taken back, and Fuse writes it when Steam next closes, as before. Steam's collections are
kept apart from its list, so Fuse's collections still follow when Steam closes. It needs Fuse's
Change Library permission for the plugin (Settings, Library, Decky).

The same answer names Fuse's Direct entries (`watch`), which start the emulator with no Fuse to count
their time. The plugin listens to Steam's app lifetime events
(`SteamClient.GameSessions.RegisterForAppLifetimeNotifications`, typed in
`dist/globals/steam-client/GameSessions.d.ts`) and notes when Steam starts and stops one of those
entries, and nothing else Steam runs. Each finished session is kept in the plugin's settings folder
(`played.json`) until Fuse answers (`POST /v1/steam/played`), so time played with Fuse closed is
counted the next time it runs; Fuse counts each session once.

While a game is in front of Fuse in Game Mode, the notices Fuse would show on its own screen come in
the answer too (`notices`), and the plugin shows them with Decky's own toaster.

## Tests

```sh
python3 -m unittest discover -s tests
```

The backend against a stand-in bridge on the loopback address: finding it, the protocol check, the
token on every request, and the routes above.

## Build

```sh
pnpm install
pnpm run build
```

The plugin is `plugin.json`, `main.py`, `package.json` and `dist/index.js`, zipped in a folder named
`Fuse`, as Decky Loader installs plugins. Fuse's setup can install it for you on SteamOS and Bazzite.
