"""
The plugin backend against a stand-in Fuse Decky Bridge on the loopback address: finding it, the
protocol check, the token on every request, and the routes for Steam's live changes.

    python3 -m unittest discover -s tools/decky/fuse/tests
"""

import asyncio
import http.server
import importlib
import json
import os
import sys
import tempfile
import threading
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class Bridge(http.server.BaseHTTPRequestHandler):
    """Answers as Fuse's bridge does, and notes each request."""

    token = "secret"
    seen = []
    answers = {}

    def _answer(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length).decode("utf-8")) if length else None
        Bridge.seen.append((method, self.path, self.headers.get("Authorization"), body))
        if self.headers.get("Authorization") != "Bearer " + Bridge.token:
            self._send(401, {"error": "Not allowed"})
            return
        status, answer = Bridge.answers.get((method, self.path), (404, {"error": "Not found"}))
        self._send(status, answer)

    def _send(self, status, answer):
        data = json.dumps(answer).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._answer("GET")

    def do_POST(self):
        self._answer("POST")

    def log_message(self, *args):
        pass


class BackendTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.home = tempfile.mkdtemp(prefix="fuse-decky-")
        # Decky's own module, as the backend uses it.
        decky = types.ModuleType("decky")
        decky.DECKY_USER_HOME = cls.home
        decky.logger = types.SimpleNamespace(info=lambda *a: None, warning=lambda *a: None)
        sys.modules["decky"] = decky
        sys.path.insert(0, os.path.dirname(HERE))
        os.environ.pop("XDG_DATA_HOME", None)
        cls.main = importlib.import_module("main")
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Bridge)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        Bridge.seen = []
        Bridge.answers = {}
        self.discovery = os.path.join(self.home, ".local", "share", "fuse", "decky-bridge.json")
        os.makedirs(os.path.dirname(self.discovery), exist_ok=True)
        self.publish(self.main.PROTOCOL)

    def publish(self, protocol, token="secret"):
        with open(self.discovery, "w", encoding="utf-8") as f:
            json.dump({"port": self.server.server_address[1], "token": token, "protocol": protocol, "version": "0.4.4"}, f)

    def run_(self, coroutine):
        return asyncio.run(coroutine)

    def test_without_fuse_running_it_says_so(self):
        os.remove(self.discovery)
        answer = self.run_(self.main.Plugin().hello())
        self.assertEqual("not-running", answer["error"])
        self.assertEqual([], Bridge.seen)

    def test_another_protocol_is_reported_never_guessed_at(self):
        self.publish(self.main.PROTOCOL - 1)
        older = self.run_(self.main.Plugin().steam_changes())
        self.assertEqual("incompatible", older["error"])
        self.assertIn("Update Fuse", older["message"])
        self.publish(self.main.PROTOCOL + 1)
        newer = self.run_(self.main.Plugin().steam_changes())
        self.assertIn("Update the plugin", newer["message"])
        self.assertEqual([], Bridge.seen)

    def test_steam_changes_carry_the_token(self):
        change = {"id": 4, "op": "ADD", "gameId": 12, "name": "Metroid Fusion", "exe": "\"/a/Fuse.AppImage\"", "startDir": "\"/a\"", "launchOptions": "--run 12", "art": {"capsule": "/art/12.png"}}
        Bridge.answers[("GET", "/v1/steam/changes")] = (200, {"changes": [change]})
        answer = self.run_(self.main.Plugin().steam_changes())
        self.assertEqual([change], answer["changes"])
        self.assertEqual([("GET", "/v1/steam/changes", "Bearer secret", None)], Bridge.seen)

    def test_a_change_is_reported_with_its_entry_and_short_words(self):
        Bridge.answers[("POST", "/v1/steam/changes/4/done")] = (200, {"ok": True})
        self.run_(self.main.Plugin().change_done(4, True, 3045678901))
        self.run_(self.main.Plugin().change_done("4", False, None, "x" * 1000))
        self.assertEqual({"ok": True, "appId": 3045678901}, Bridge.seen[0][3])
        self.assertEqual(False, Bridge.seen[1][3]["ok"])
        self.assertEqual(self.main.MESSAGE_CHARS, len(Bridge.seen[1][3]["message"]))
        self.assertNotIn("appId", Bridge.seen[1][3])

    def test_bad_ids_and_unknown_pictures_never_reach_fuse(self):
        plugin = self.main.Plugin()
        self.assertEqual("bad-request", self.run_(plugin.change_done("../x", True))["error"])
        self.assertEqual("bad-request", self.run_(plugin.change_done(-3, True))["error"])
        self.assertEqual("bad-request", self.run_(plugin.change_art(4, "../../etc/passwd"))["error"])
        self.assertEqual([], Bridge.seen)
        Bridge.answers[("GET", "/v1/steam/changes/4/art/hero")] = (200, {"format": "png", "data": "iVBORw0K"})
        self.assertEqual("png", self.run_(plugin.change_art(4, "hero"))["format"])

    def test_fuses_own_refusal_is_passed_on(self):
        Bridge.answers[("GET", "/v1/steam/changes")] = (403, {"error": "not-allowed", "message": "Allow it in Fuse: Settings, Library, Decky."})
        answer = self.run_(self.main.Plugin().steam_changes())
        self.assertEqual("not-allowed", answer["error"])
        self.assertIn("Allow it in Fuse", answer["message"])

    def test_a_wrong_token_is_refused(self):
        self.publish(self.main.PROTOCOL, token="stale")
        answer = self.run_(self.main.Plugin().steam_changes())
        self.assertEqual("http-401", answer["error"])


if __name__ == "__main__":
    unittest.main()
