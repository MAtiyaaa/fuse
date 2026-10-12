#!/bin/sh
# Fuse One Host installer: makes this computer a Fuse One household host with Docker.
#
#   curl -fsSL https://raw.githubusercontent.com/matiyaaa/fuse/main/tools/host/install.sh | sh
#
# What it does, and nothing else:
#   1. Checks the system (Linux, a 64-bit PC or ARM board) and that Docker and Docker Compose are here.
#   2. Writes a compose file to ~/fuse-one-host (or FUSE_ONE_DIR) and starts the official image from
#      ghcr.io, restarting with the computer.
#   3. Waits for the host to answer, then shows its address on your network and a pairing code.
#
# It never installs Docker for you, never asks for sudo itself, never opens the host to the internet,
# and running it again only updates and restarts the container: the household's data, in the
# fuse-one-data volume, is kept. Set FUSE_ONE_VERSION to pin a release (default: latest), FUSE_ONE_NAME
# to name the host, FUSE_ONE_PORT to change its port.
set -eu

DIR="${FUSE_ONE_DIR:-$HOME/fuse-one-host}"
VERSION="${FUSE_ONE_VERSION:-latest}"
IMAGE="${FUSE_ONE_IMAGE:-ghcr.io/matiyaaa/fuse-one-host:$VERSION}"
PORT="${FUSE_ONE_PORT:-47311}"
NAME="${FUSE_ONE_NAME:-$(hostname 2>/dev/null || echo 'Fuse One Host')}"

say() { printf '%s\n' "$*"; }
fail() { printf 'Fuse One Host: %s\n' "$*" >&2; exit 1; }

# 1. The system.
[ "$(uname -s)" = "Linux" ] || fail "this installer is for Linux (a PC, home server or NAS). On another system, run the image with Docker yourself: see tools/host/README.md."
case "$(uname -m)" in
  x86_64|amd64|aarch64|arm64) ;;
  *) fail "$(uname -m) isn't supported. Fuse One Host runs on 64-bit PCs (x86_64) and 64-bit ARM (aarch64)." ;;
esac
command -v docker >/dev/null 2>&1 || fail "Docker isn't installed. Install it from https://docs.docker.com/engine/install/ (or your NAS's container app), then run this again."
docker info >/dev/null 2>&1 || fail "Docker is installed but this user can't use it. Start Docker, or add yourself to the docker group (then sign out and in), and run this again."
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  fail "Docker Compose isn't installed. Install the compose plugin (https://docs.docker.com/compose/install/), then run this again."
fi
case "$PORT" in
  ''|*[!0-9]*) fail "FUSE_ONE_PORT must be a number." ;;
esac

# 2. The compose file and its settings. Only these two files are written.
mkdir -p "$DIR"
cat > "$DIR/compose.yaml" <<'COMPOSE'
services:
  fuse-one-host:
    image: ${FUSE_ONE_IMAGE}
    container_name: fuse-one-host
    restart: unless-stopped
    network_mode: host
    environment:
      FUSE_ONE_NAME: ${FUSE_ONE_NAME}
      FUSE_ONE_PORT: ${FUSE_ONE_PORT}
    volumes:
      - fuse-one-data:/data
    read_only: true
    tmpfs:
      - /tmp:exec,mode=1777
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL

volumes:
  fuse-one-data:
    name: fuse-one-data
COMPOSE
{
  printf 'FUSE_ONE_IMAGE=%s\n' "$IMAGE"
  printf 'FUSE_ONE_NAME=%s\n' "$(printf '%s' "$NAME" | tr -d '\n\r' | cut -c1-40)"
  printf 'FUSE_ONE_PORT=%s\n' "$PORT"
} > "$DIR/.env"

say "Starting Fuse One Host ($IMAGE)..."
cd "$DIR"
# A network hiccup isn't fatal when the image is already here (an update that couldn't download).
if ! $COMPOSE pull --quiet; then
  docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "couldn't download $IMAGE. Check this computer's internet connection and try again."
  say "Couldn't download a newer image; starting the one already here."
fi
$COMPOSE up -d

# 3. Ready: the address others reach it on, and a code for the first device.
tries=0
until docker exec fuse-one-host fuse-one-host status >/dev/null 2>&1; do
  tries=$((tries + 1))
  [ "$tries" -lt 60 ] || fail "the host didn't start. See what it says with: docker logs fuse-one-host"
  sleep 1
done

ADDRESS="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit }}')"
[ -n "$ADDRESS" ] || ADDRESS="$(hostname -I 2>/dev/null | awk '{print $1}')"
CODE="$(docker exec fuse-one-host fuse-one-host pair 2>/dev/null | sed -n 's/^Pairing code: //p')"

say ""
say "Fuse One Host is ready"
say "  Local address: ${ADDRESS:-this computer's address}:$PORT"
say "  Status:        running, and starts again with this computer"
say "  Household:     ready to set up"
[ -n "$CODE" ] && say "  Pairing code:  $CODE (works once, for a few minutes)"
say ""
say "On your device: Fuse, Settings, Fuse One, Connect to a Host. On this network it finds the host"
say "by itself; otherwise type the address above. Already using Fuse One on a device? Connecting"
say "brings its profiles and saves along, nothing is replaced."
say ""
say "Another code:  docker exec fuse-one-host fuse-one-host pair"
say "Status:        docker exec fuse-one-host fuse-one-host status"
say "Logs:          docker logs fuse-one-host"
say "Update:        cd $DIR && $COMPOSE pull && $COMPOSE up -d"
say "Stop:          cd $DIR && $COMPOSE down   (your household's data stays in the fuse-one-data volume)"
say "From outside home, use a VPN such as Tailscale or WireGuard: never forward this port to the internet."
