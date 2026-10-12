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
a Fuse that speak different bridge protocols say so instead of guessing.

## Build

```sh
pnpm install
pnpm run build
```

The plugin is `plugin.json`, `main.py`, `package.json` and `dist/index.js`, zipped in a folder named
`Fuse`, as Decky Loader installs plugins. Fuse's setup can install it for you on SteamOS and Bazzite.
