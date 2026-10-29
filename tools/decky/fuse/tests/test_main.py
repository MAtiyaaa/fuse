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
import time
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
        decky.DECKY_PLUGIN_SETTINGS_DIR = os.path.join(cls.home, "settings")
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
        try:
            os.remove(os.path.join(self.home, "settings", "played.json"))
        except OSError:
            pass
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

    def test_the_game_being_played_is_asked_about_closed_and_marked(self):
        plugin = self.main.Plugin()
        Bridge.answers[("GET", "/v1/playing")] = (200, {"playing": True, "id": 7, "name": "Metroid Fusion", "system": "GBA", "canQuit": True})
        Bridge.answers[("POST", "/v1/playing/quit")] = (200, {"quit": True})
        Bridge.answers[("POST", "/v1/playing/journal")] = (200, {"set": True})
        self.assertEqual("Metroid Fusion", self.run_(plugin.playing())["name"])
        self.assertTrue(self.run_(plugin.quit_playing())["quit"])
        self.assertTrue(self.run_(plugin.set_playing_status("BEATEN"))["set"])
        self.assertEqual({"status": "BEATEN"}, Bridge.seen[-1][3])
        # A status Fuse doesn't have never reaches it.
        seen = len(Bridge.seen)
        self.assertEqual("bad-request", self.run_(plugin.set_playing_status("beaten; rm -rf"))["error"])
        self.assertEqual(seen, len(Bridge.seen))

    def test_fuses_own_refusal_is_passed_on(self):
        Bridge.answers[("GET", "/v1/steam/changes")] = (403, {"error": "not-allowed", "message": "Allow it in Fuse: Settings, Library, Decky."})
        answer = self.run_(self.main.Plugin().steam_changes())
        self.assertEqual("not-allowed", answer["error"])
        self.assertIn("Allow it in Fuse", answer["message"])

    def test_a_wrong_token_is_refused(self):
        self.publish(self.main.PROTOCOL, token="stale")
        answer = self.run_(self.main.Plugin().steam_changes())
        self.assertEqual("http-401", answer["error"])


    # Time in Fuse's Direct entries: noted only for the entries Fuse names, kept until Fuse has it.

    def watch(self, *app_ids):
        Bridge.answers[("GET", "/v1/steam/changes")] = (200, {"changes": [], "watch": list(app_ids)})
        self.run_(self.main.Plugin().steam_changes())

    def test_only_the_entries_fuse_names_are_noted(self):
        self.watch(3045678901)
        self.assertEqual({"watch": [3045678901]}, self.run_(self.main.Plugin().watched()))
        self.assertEqual({"noted": False}, self.run_(self.main.Plugin().played(1234, True)))
        self.assertEqual({"noted": True}, self.run_(self.main.Plugin().played(3045678901, True)))

    def test_a_session_goes_to_fuse_once_it_ends(self):
        self.watch(3045678901)
        Bridge.answers[("POST", "/v1/steam/played")] = (200, {"recorded": 1, "unknown": 0})
        plugin = self.main.Plugin()
        self.run_(plugin.played(3045678901, True))
        # A session lasts at least a moment; one of no length isn't counted.
        time.sleep(0.01)
        self.run_(plugin.played(3045678901, False))
        posted = [b for (m, p, _, b) in Bridge.seen if p == "/v1/steam/played"]
        self.assertEqual(1, len(posted))
        session = posted[0]["sessions"][0]
        self.assertEqual(3045678901, session["appId"])
        self.assertLessEqual(session["start"], session["end"])
        # Fuse has it: not sent again.
        self.watch(3045678901)
        self.assertEqual(1, len([1 for (m, p, _, b) in Bridge.seen if p == "/v1/steam/played"]))

    def test_a_session_played_without_fuse_waits_for_it(self):
        self.watch(3045678901)
        plugin = self.main.Plugin()
        self.run_(plugin.played(3045678901, True))
        time.sleep(0.01)
        os.remove(self.discovery)
        # Played from Steam with Fuse closed: kept.
        self.run_(plugin.played(3045678901, False))
        self.assertEqual([], [p for (m, p, _, b) in Bridge.seen if p == "/v1/steam/played"])
        # Fuse is back: it gets the session with its next question.
        self.publish(self.main.PROTOCOL)
        Bridge.answers[("POST", "/v1/steam/played")] = (200, {"recorded": 1, "unknown": 0})
        self.watch(3045678901)
        posted = [b for (m, p, _, b) in Bridge.seen if p == "/v1/steam/played"]
        self.assertEqual(1, len(posted[0]["sessions"]))

    def test_a_stop_without_a_start_is_no_session(self):
        self.watch(3045678901)
        self.run_(self.main.Plugin().played(3045678901, False))
        self.assertEqual([], [p for (m, p, _, b) in Bridge.seen if p == "/v1/steam/played"])


if __name__ == "__main__":
    unittest.main()
