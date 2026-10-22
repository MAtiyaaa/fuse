# Fuse Ports' list

`index.json` is the list of ports Fuse offers in Addons, Ports on a computer: games rebuilt to run
natively, each the program its own project publishes, played with the person's own copy of the
game. Fuse carries this file inside every build. What Fuse does with it is in
[INTEGRATIONS.md, Fuse Ports](../INTEGRATIONS.md#fuse-ports).

## Adding a port

A port belongs here when its project publishes builds for Linux and Windows (x86_64) and it plays
a game from the person's own files, without them nothing; a recompilation made from one ROM (`rom`
in its `data`) may be Linux only where it keeps its files in the person's own folders on Windows,
and says so in its `notes` ("Linux only"). A port whose build for one system can't run as it is
published there (a newer system library than most have) lists the other one and says so ("Windows
only"). A build that starts only with a library most desktops have but its project doesn't ship
(SDL2_mixer, GLEW) names it in its `notes`, and the library goes in the Linux check's packages in
`.github/workflows/ports.yml`. For each one:

1. Read the port's own code and documentation at the release you list, and cite every fact in its
   `source` (the file and function): where it reads the game's files, how it is given a folder,
   where it writes saves and settings, how it prints its version. Never read or copy another
   launcher's or manager's code or lists.
2. Download each build and write its exact `size` and `sha256`. The `run` path is the program
   inside the unpacked build.
3. Say what it needs from the game (`needs`, each a list of names any one of which will do; `exact`
   when the port reads the names exactly as written) and how they reach it: a placeholder
   `{data:<id>}` in `args` when it takes a folder by its path, else an `overlay` with every path it
   writes in `own` (folders end with `/`). A port that asks for the game itself on its first start
   (a file picker or its own importer, as most recompilations do) has no `data`: its `notes` say
   what it asks for and where it keeps it.
4. Keep its saves and settings in its home: `{home}/...` in `args`, and `saves` relative to it.
5. Give it a `check` the nightly job can run without the game's files: `args` and the `expect`ed
   output (a regular expression), or `stay` seconds it keeps running.
6. Name its own settings file in `settings` (`{"file": "{home}/config/diablo.ini"}`, under
   `{home}` or `{install}`) and add a reader for its settings code to `tools/tune` with the port's
   id as its family, so Fuse Tune offers every setting the port reads.
7. Give its game's box art in `cover` (an HTTPS picture: the one Open Ports Games' entry for the
   game points to, Wikipedia's or the libretro thumbnails' box) and whom it is from in
   `coverCredit`.
8. Raise `revision`. Leave `verified` out: only the nightly job writes it.

`tools/ports/check.py check --out results.json` runs the same check on your computer.

## Signing

Fuse believes a list it fetches only when its Ed25519 signature verifies with the public key it
carries. To turn this on, once:

```sh
openssl genpkey -algorithm ed25519 -out ports-signing.pem
# The private key: the repository secret PORTS_SIGNING_KEY (the whole PEM file).
# The public key, as Fuse carries it (Base64 of its 32 bytes), for PortsSignature.PUBLIC_KEY:
openssl pkey -in ports-signing.pem -pubout -outform DER | tail -c 32 | base64
```

Keep the private key out of the repository. From then on, the Ports workflow signs the list each
night and puts `index.json` and `index.json.sig` on the `ports` pre-release, and a Fuse carrying
the public key shows the ports that passed as verified.

## PlayStation games' native ports

`native.json` lists the PlayStation games rebuilt to run natively that Fuse finds when the person
installed them (`core:launch` `PsPorts`), and the files each release must still hold. Each night
`tools/ports/native_check.py` checks them against each project's newest release; a change opens an
issue labelled `native-port-changed`. Keep each entry the same as its `PsPorts` entry: a test
compares them.

## Ports on Android

The `android` list in `index.json` holds the ports published as Android apps that Fuse checked
itself; `android/openports.json` is Open Ports Games' catalogue of the rest (its data under CC BY
4.0), as Fuse carries it. What Fuse does with both is in
[INTEGRATIONS.md, Ports on Android](../INTEGRATIONS.md#ports-on-android).

A port belongs in the `android` list when its project publishes an APK in its GitHub releases and
it plays a game from the person's own files (never one that ships the game's own). For each one:

1. Read its own source at the release you list, and cite every fact in its `source`: its package
   (`applicationId` in its build file, written as `package`), what it asks for on its first start
   (`needs`, in words), and where it says which ROM it takes.
2. Give the game it plays (`game`), its other names (`titles`) and the library's systems (`systems`,
   Fuse's ids such as `n64`), its `kind` (`recompilation`, `decompilation`, `source-port`,
   `runtime-port` or `reimplementation`) and `status` (`stable`, `beta` or `alpha`), and its
   `licence` as its LICENSE file names it.
3. Where a release holds more than one APK, say which in `asset` (a regular expression over the
   file's name). Fuse takes the newest release with an APK for the device, older ones when the
   newest has none.
4. List the ROMs it is made for in `roms` only where its source names them by the XXH3-64 of the
   big-endian ROM (`supported_games` in an N64: Recompiled port).
5. Give its game's box art in `cover` and `coverCredit`, as for a computer's port. Its game's
   publisher, year and genre come from Open Ports Games' listing of the same project or game.
6. Raise `revision`.

The snapshot keeps each listing's box art (`cover`), its first picture (`screenshots`), and its
game's publisher, year and genre; a listing without box art takes another listing's of the same
game. To bring it up to date:

```sh
python3 tools/ports/openports.py snapshot --out ports/android/openports.json
```
