# Fuse One Host

The Fuse One household host on its own: profiles, Fuse Save Sync, Fuse Library Sync, transfers and
the household's shared settings, for every device running Fuse at home. It has no screen and no game
library, and needs neither RomM nor Syncthing. Any Linux computer with Docker can run it: a mini PC,
a home server, a NAS.

## Install

```sh
curl -fsSL https://raw.githubusercontent.com/matiyaaa/fuse/main/tools/host/install.sh | sh
```

The installer checks the system, starts the official image (`ghcr.io/matiyaaa/fuse-one-host`,
published with each Fuse release for 64-bit PCs and ARM boards), and shows the host's address on
your network and a pairing code. It never installs Docker or asks for `sudo` itself. Read it first if
you like: it is [one short script](install.sh).

Then on each device: Fuse, Settings, Fuse One, Connect to a Host. On the same network Fuse finds the
host by itself. A device already using Fuse One on its own brings its profiles and saves along.

Prefer Compose directly? [compose.yaml](compose.yaml) is the same setup: `docker compose up -d`.

## Everyday commands

| What | Command |
| --- | --- |
| Pairing code | `docker exec fuse-one-host fuse-one-host pair` |
| Status | `docker exec fuse-one-host fuse-one-host status` |
| Logs | `docker logs fuse-one-host` |
| Start, stop, restart | `docker start fuse-one-host`, `docker stop fuse-one-host`, `docker restart fuse-one-host` |
| Update | `cd ~/fuse-one-host && docker compose pull && docker compose up -d` |
| Pin a version | set `FUSE_ONE_IMAGE=ghcr.io/matiyaaa/fuse-one-host:0.4.2` in `~/fuse-one-host/.env` |
| Host account (for joining by name and the Hub from away) | `docker exec -it fuse-one-host fuse-one-host account <name>` |
| Backup hosts, and computers offering to be one | `docker exec fuse-one-host fuse-one-host backups` |
| Accept a computer as a backup host | `docker exec fuse-one-host fuse-one-host accept <name>` (or Accept on the Hub) |
| Make this host a backup of another | stop it, then `docker run --rm -v fuse-one-data:/data --network host ghcr.io/matiyaaa/fuse-one-host backup-of <address> <pairing code>`, then start it |
| Make a backup the host (the old one is gone for good) | `docker exec fuse-one-host fuse-one-host take-over` |
| Back up | `docker stop fuse-one-host && docker run --rm -v fuse-one-data:/data:ro -v "$PWD":/backup --entrypoint tar ghcr.io/matiyaaa/fuse-one-host -czf /backup/fuse-one-backup.tgz -C /data . && docker start fuse-one-host` |
| Restore | `docker stop fuse-one-host && docker run --rm --user 0 -v fuse-one-data:/data -v "$PWD":/backup --entrypoint tar ghcr.io/matiyaaa/fuse-one-host -xzf /backup/fuse-one-backup.tgz -C /data && docker start fuse-one-host` |
| Uninstall | `cd ~/fuse-one-host && docker compose down` (the data stays in the `fuse-one-data` volume until you remove it with `docker volume rm fuse-one-data`) |

Updating or removing the container never deletes the household's data: it lives in the
`fuse-one-data` volume. To roll an update back, pin the previous version and run `up -d` again.

## What it is, and is not

- One small Java service (about 65 MB of memory when idle in our test), running as an unprivileged
  user with a read-only filesystem except its data volume.
- It uses host networking so devices at home can find it, on port 47311 (and 47310 for discovery).
- It is the same host Fuse runs when a computer hosts from Settings, with the same protocol and
  identity, so a household can move between the two.
- From outside home, reach it through a VPN (Tailscale, WireGuard) or an authenticated tunnel. Never
  forward its port to the internet.
- The Hub (`http://<host>:47311/hub`) shows what the host keeps. From another computer it asks for
  the host account.
- Backup hosts: another computer running Fuse (Settings, Fuse One, Be a Backup Host) or another
  Fuse One Host (`backup-of`) can keep a copy of the household, sealed with its own key. With two or
  more backups, a majority of the hosts chooses a new host when this one is gone; with one, a person
  does (`take-over`). A host that comes back after another took over takes no more changes, hands
  over what it kept meanwhile (kept as versions, never put over newer saves) and becomes a backup.

## Building it yourself

```sh
./gradlew :app:host:installDist          # the Android SDK is needed to configure Fuse's modules
docker build -f tools/host/Dockerfile -t fuse-one-host .
FUSE_ONE_IMAGE=fuse-one-host sh tools/host/install.sh
```

Without Docker, `app/host/build/install/fuse-one-host/bin/fuse-one-host` runs it directly; set
`FUSE_ONE_DATA` to the folder it keeps the household in.
