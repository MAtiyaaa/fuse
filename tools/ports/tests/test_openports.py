"""The Android snapshot of Open Ports Games' catalogue, from a made-up catalogue."""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import openports  # noqa: E402


def port(pid, **extra):
    base = {
        "schema": "port", "id": pid, "kind": "game", "title": pid.title(), "game": "Game " + pid,
        "developers": ["someone"], "portType": "recompilation", "platforms": ["windows", "android"],
        "status": "beta", "sources": ["https://github.com/someone/" + pid], "license": {"spdx": "MIT", "note": "x"},
        "verified": False, "notes": "Notes.", "notesEs": "Notas.", "originalSystems": ["Nintendo 64"],
        "publisher": "Someone", "originalYear": 1998, "genre": "platformer",
        "cover": {"src": "https://example.org/c.png", "alt": "Box art", "credit": "Wikipedia"},
    }
    base.update(extra)
    return base


FEED = {
    "schema": "openportsgames/catalog", "version": 2, "generatedAt": "2026-10-18", "site": "https://openportsgames.com/",
    "hardware": [], "tests": [],
    "ports": [
        port("zeta"),
        port("alpha"),
        port("desktop-only", platforms=["windows", "linux"]),
        port("a-launcher", kind="tool"),
        port("gone", sourceDead=True),
        port("ships-files", warning="This Android port distributes the game's own files."),
        port("no-https", sources=["http://example.org/x"]),
    ],
}


class SnapshotTest(unittest.TestCase):
    def test_only_android_games_with_a_source_and_no_warning_are_kept_and_trimmed(self):
        out = openports.snapshot(FEED)
        self.assertEqual(["alpha", "zeta"], [p["id"] for p in out["ports"]])
        first = out["ports"][0]
        self.assertEqual(["android"], first["platforms"])
        self.assertEqual({"spdx": "MIT"}, first["license"])
        self.assertNotIn("notesEs", first)
        self.assertNotIn("developers", first)
        self.assertEqual({"src": "https://example.org/c.png", "credit": "Wikipedia"}, first["cover"])
        self.assertEqual(("Someone", 1998, "platformer"), (first["publisher"], first["originalYear"], first["genre"]))
        self.assertEqual("2026-10-18", out["generatedAt"])
        self.assertEqual("CC BY 4.0", out["licence"])

    def test_a_listing_without_box_art_takes_another_listings_of_its_game(self):
        feed = dict(FEED, ports=[
            port("bare", game="Star Fox 64", cover=None, screenshots=[{"src": "http://example.org/s.png"}, {"src": "https://example.org/s.png", "credit": "Its project"}]),
            port("desktop", game="Star Fox 64", platforms=["windows"], cover={"src": "https://example.org/sf.png", "credit": "Box art"}),
            port("insecure", game="Another Game", cover={"src": "http://example.org/a.png"}),
        ])
        out = {p["id"]: p for p in openports.snapshot(feed)["ports"]}
        self.assertEqual({"src": "https://example.org/sf.png", "credit": "Box art"}, out["bare"]["cover"])
        self.assertEqual([{"src": "https://example.org/s.png", "credit": "Its project"}], out["bare"]["screenshots"])
        self.assertNotIn("cover", out["insecure"], "a picture not over HTTPS is left out")
        self.assertNotIn("desktop", out)

    def test_dashes_in_a_listings_text_become_commas_or_hyphens(self):
        self.assertEqual("Undead Neighbor Eaters, Android Native Port", openports.plain("Undead Neighbor Eaters \u2014 Android Native Port"))
        self.assertEqual("Mission Pack, Portal of Praevus", openports.plain("Mission Pack \u2013 Portal of Praevus\u2060"))
        self.assertEqual("Released 1996-1998", openports.plain("Released 1996\u20131998"))
        out = openports.snapshot(dict(FEED, ports=[port("dash", title="A \u2014 B")]))
        self.assertEqual("A, B", out["ports"][0]["title"])

    def test_another_catalogue_or_a_newer_form_is_refused(self):
        with self.assertRaises(ValueError):
            openports.snapshot(dict(FEED, schema="something/else"))
        with self.assertRaises(ValueError):
            openports.snapshot(dict(FEED, version=3))

    def test_written_from_a_file_and_read_only_over_https(self):
        with tempfile.TemporaryDirectory() as d:
            feed = os.path.join(d, "feed.json")
            out = os.path.join(d, "out.json")
            with open(feed, "w", encoding="utf-8") as f:
                json.dump(FEED, f)
            self.assertEqual(0, openports.main(["snapshot", "--feed", feed, "--out", out]))
            with open(out, encoding="utf-8") as f:
                self.assertEqual(2, len(json.load(f)["ports"]))
        with self.assertRaises(ValueError):
            openports.read_feed("http://openportsgames.com/api/ports.json")


if __name__ == "__main__":
    unittest.main()
