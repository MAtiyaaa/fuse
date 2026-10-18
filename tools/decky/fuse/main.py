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
# 2: Fuse's changes to Steam's library go through Steam's running client (steam_changes).
PROTOCOL = 2
TIMEOUT_S = 4

# Steam's picture kinds for an entry, as Fuse names them in a change (ELibraryAssetType in @decky/ui).
ART_KINDS = ("capsule", "hero", "logo", "header", "icon")
# The most of a failure's words sent back to Fuse.
MESSAGE_CHARS = 300


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
        if found["protocol"] < PROTOCOL:
            words = "This Fuse plugin is newer than Fuse " + found["version"] + ". Update Fuse, then try again."
        else:
            words = "This Fuse plugin is older than Fuse " + found["version"] + ". Update the plugin from Fuse (Settings, Steam)."
        return {"error": "incompatible", "message": words}
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
        # Fuse says why it refused (something the person hasn't allowed the plugin): pass that on.
        try:
            answer = json.loads(e.read().decode("utf-8") or "{}")
            if isinstance(answer, dict) and isinstance(answer.get("message"), str):
                return {"error": answer.get("error") or "http-" + str(e.code), "message": answer["message"]}
        except (ValueError, OSError):
            pass
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


def _menu_off():
    """True when the person turned off Fuse's entries in Steam's menu (Fuse, Settings, Steam), or Fuse did."""
    for path in _discovery_candidates():
        if os.path.exists(os.path.join(os.path.dirname(path), "decky-menu-off")):
            return True
    return False


class Plugin:
    async def menu_off(self):
        """Read once when the plugin loads: whether to leave Steam's menu exactly as Steam made it."""
        try:
            return _menu_off()
        except Exception:
            return True

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

    async def library(self):
        return await _ask("GET", "/v1/library")

    async def open_fuse_game(self, game_id):
        game_id = _app_id(game_id)
        if game_id is None:
            return {"error": "bad-request"}
        return await _ask("POST", "/v1/game/" + str(game_id) + "/open")

    async def emulators(self):
        return await _ask("GET", "/v1/emulators")

    async def emulators_update_all(self):
        return await _ask("POST", "/v1/emulators/update-all")

    async def emulators_install_all(self):
        return await _ask("POST", "/v1/emulators/install-all")

    async def emulators_uninstall_all(self):
        return await _ask("POST", "/v1/emulators/uninstall-all")

    async def steam_changes(self):
        """The changes Fuse asks Steam's running client to make; asking also tells Fuse the plugin is here."""
        return await _ask("GET", "/v1/steam/changes")

    async def change_art(self, change_id, kind):
        """One picture for a change, as Fuse sends it (base64): only the kinds Steam has, only by the change's id."""
        change_id = _app_id(change_id)
        if change_id is None or kind not in ART_KINDS:
            return {"error": "bad-request"}
        return await _ask("GET", "/v1/steam/changes/" + str(change_id) + "/art/" + kind)

    async def change_done(self, change_id, ok, app_id=None, message=None):
        """How a change went in Steam: made (with the entry's id in Steam) or not, and why."""
        change_id = _app_id(change_id)
        if change_id is None:
            return {"error": "bad-request"}
        body = {"ok": bool(ok)}
        app_id = _app_id(app_id)
        if app_id is not None:
            body["appId"] = app_id
        if isinstance(message, str) and message:
            body["message"] = message[:MESSAGE_CHARS]
        return await _ask("POST", "/v1/steam/changes/" + str(change_id) + "/done", body)

    async def _main(self):
        decky.logger.info("Fuse plugin loaded; it talks to Fuse only while Fuse runs")

    async def _unload(self):
        decky.logger.info("Fuse plugin unloaded")
