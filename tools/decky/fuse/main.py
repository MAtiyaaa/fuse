"""
The Fuse Decky plugin's backend: a thin, careful client of the Fuse Decky Bridge.

Fuse owns everything (the library, profiles, Fuse One, saves and transfers). This backend only
finds the bridge Fuse publishes on this device, asks it a short list of questions and passes on
a few requests the person could make in Fuse themselves. It never reads or writes saves, emulator
files, household secrets or Steam credentials, and it keeps no state of its own: when Fuse or
Decky restarts, the next call simply finds the bridge again.
"""

import asyncio
import json
import os
import urllib.error
import urllib.request

import decky

# The bridge protocol this plugin speaks. A Fuse speaking another one is reported, never guessed at.
PROTOCOL = 1
TIMEOUT_S = 4


def _discovery_candidates():
    """Where Fuse writes the bridge's port and token: its data folder in the user's home."""
    home = getattr(decky, "DECKY_USER_HOME", None) or os.path.expanduser("~")
    paths = []
    xdg = os.environ.get("XDG_DATA_HOME")
    if xdg:
        paths.append(os.path.join(xdg, "fuse", "decky-bridge.json"))
    paths.append(os.path.join(home, ".local", "share", "fuse", "decky-bridge.json"))
    return paths


def _read_discovery():
    for path in _discovery_candidates():
        try:
            with open(path, "r", encoding="utf-8") as f:
                found = json.load(f)
            port = int(found.get("port", 0))
            token = str(found.get("token", ""))
            if 0 < port < 65536 and token:
                return {"port": port, "token": token, "protocol": int(found.get("protocol", 0)), "version": str(found.get("version", ""))}
        except (OSError, ValueError, TypeError):
            continue
    return None


def _call(method, path, body=None):
    """One request to the bridge on this device's loopback address. Never raises: errors are answers."""
    found = _read_discovery()
    if found is None:
        return {"error": "not-running", "message": "Fuse isn't running. Open Fuse, then try again."}
    if found["protocol"] != PROTOCOL:
        return {
            "error": "incompatible",
            "message": "This Fuse plugin doesn't match Fuse " + found["version"] + ". Update the plugin from Fuse (Settings, Steam).",
        }
    data = None
    headers = {"Authorization": "Bearer " + found["token"]}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        "http://127.0.0.1:" + str(found["port"]) + path, data=data, method=method, headers=headers
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            return json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        return {"error": "http-" + str(e.code), "message": "Fuse refused that (" + str(e.code) + ")."}
    except (urllib.error.URLError, OSError, ValueError):
        return {"error": "not-running", "message": "Fuse isn't answering. Open Fuse, then try again."}


async def _ask(method, path, body=None):
    return await asyncio.to_thread(_call, method, path, body)


def _app_id(value):
    try:
        app_id = int(value)
    except (TypeError, ValueError):
        return None
    return app_id if app_id > 0 else None


class Plugin:
    async def hello(self):
        return await _ask("GET", "/v1/hello")

    async def status(self):
        return await _ask("GET", "/v1/status")

    async def steam_game(self, app_id):
        app_id = _app_id(app_id)
        if app_id is None:
            return {"error": "bad-request"}
        return await _ask("GET", "/v1/steam/" + str(app_id))

    async def add_game(self, app_id):
        app_id = _app_id(app_id)
        if app_id is None:
            return {"error": "bad-request"}
        return await _ask("POST", "/v1/steam/" + str(app_id) + "/add")

    async def remove_game(self, app_id):
        app_id = _app_id(app_id)
        if app_id is None:
            return {"error": "bad-request"}
        return await _ask("POST", "/v1/steam/" + str(app_id) + "/remove")

    async def open_game(self, app_id):
        app_id = _app_id(app_id)
        if app_id is None:
            return {"error": "bad-request"}
        return await _ask("POST", "/v1/steam/" + str(app_id) + "/open")

    async def import_games(self, app_ids):
        ids = [i for i in (_app_id(a) for a in (app_ids or [])) if i is not None][:500]
        if not ids:
            return {"error": "bad-request"}
        return await _ask("POST", "/v1/steam/import", {"appIds": ids})

    async def emulators(self):
        return await _ask("GET", "/v1/emulators")

    async def emulators_update_all(self):
        return await _ask("POST", "/v1/emulators/update-all")

    async def emulators_install_all(self):
        return await _ask("POST", "/v1/emulators/install-all")

    async def emulators_uninstall_all(self):
        return await _ask("POST", "/v1/emulators/uninstall-all")

    async def _main(self):
        decky.logger.info("Fuse plugin loaded; it talks to Fuse only while Fuse runs")

    async def _unload(self):
        decky.logger.info("Fuse plugin unloaded")
