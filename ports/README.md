# Fuse Ports' list

`index.json` is the list of ports Fuse offers in Addons, Ports on a computer: games rebuilt to run
natively, each the program its own project publishes, played with the person's own copy of the
game. Fuse carries this file inside every build. What Fuse does with it is in
[INTEGRATIONS.md, Fuse Ports](../INTEGRATIONS.md#fuse-ports).

## Adding a port

A port belongs here when its project publishes builds for Linux and Windows (x86_64) and it plays
a game from the person's own files, without them nothing. For each one:

1. Read the port's own code and documentation at the release you list, and cite every fact in its
   `source` (the file and function): where it reads the game's files, how it is given a folder,
   where it writes saves and settings, how it prints its version. Never read or copy another
   launcher's or manager's code or lists.
2. Download each build and write its exact `size` and `sha256`. The `run` path is the program
   inside the unpacked build.
3. Say what it needs from the game (`needs`, each a list of names any one of which will do; `exact`
   when the port reads the names exactly as written) and how they reach it: a placeholder
   `{data:<id>}` in `args` when it takes a folder by its path, else an `overlay` with every path it
   writes in `own` (folders end with `/`).
4. Keep its saves and settings in its home: `{home}/...` in `args`, and `saves` relative to it.
5. Give it a `check` the nightly job can run without the game's files: `args` and the `expect`ed
   output (a regular expression), or `stay` seconds it keeps running.
6. Name its own settings file in `settings` (`{"file": "{home}/config/diablo.ini"}`, under
   `{home}` or `{install}`) and add a reader for its settings code to `tools/tune` with the port's
   id as its family, so Fuse Tune offers every setting the port reads.
7. Raise `revision`. Leave `verified` out: only the nightly job writes it.

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
