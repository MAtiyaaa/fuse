"""The nightly check with made-up ports: shell scripts standing in for programs, served from files."""

import base64
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import check  # noqa: E402


def zip_with(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, (body, mode) in files.items():
            info = zipfile.ZipInfo(name)
            info.external_attr = (0o100000 | mode) << 16
            z.writestr(info, body)
    return buf.getvalue()


@unittest.skipIf(sys.platform == "win32", "the made-up programs are shell scripts")
class CheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="fuse-ports-check-")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def port(self, pid, body, check_entry, sha=None, run="game"):
        path = os.path.join(self.dir, f"{pid}.zip")
        with open(path, "wb") as f:
            f.write(body)
        return {
            "id": pid, "name": pid, "game": pid, "about": "", "homepage": "https://example.org", "version": "1",
            "builds": [{"os": check.SYSTEM, "url": "file://" + path, "size": len(body), "sha256": sha or hashlib.sha256(body).hexdigest(), "kind": "ZIP", "run": run}],
            "check": check_entry, "source": "made up",
        }

    def run_check(self, ports):
        index = os.path.join(self.dir, "index.json")
        with open(index, "w") as f:
            json.dump({"format": 1, "revision": 3, "ports": ports}, f)
        return check.check(index, os.path.join(self.dir, "results.json"), allow_file=True)

    def test_each_port_is_downloaded_checked_unpacked_and_started(self):
        prints = zip_with({"game": (b"#!/bin/sh\necho 'SkyPort v2.1'\n", 0o755)})
        stays = zip_with({"game": (b"#!/bin/sh\nsleep 30\n", 0o755)})
        ends = zip_with({"game": (b"#!/bin/sh\necho no game files\nexit 1\n", 0o755)})
        crashes = zip_with({"game": (b"#!/bin/sh\nkill -SEGV $$\n", 0o755)})
        results = self.run_check([
            self.port("prints", prints, {"args": ["--version"], "expect": "SkyPort v2\\.1"}),
            self.port("wrong", prints, {"args": ["--version"], "expect": "SkyPort v9"}),
            self.port("stays", stays, {"stay": 1}),
            self.port("ends", ends, {"stay": 5}),
            self.port("crashes", crashes, {"stay": 5}),
            self.port("tampered", prints, {"args": [], "expect": "."}, sha="0" * 64),
            self.port("missing", prints, {"stay": 1}, run="other"),
            self.port("unchecked", prints, None),
        ])
        passed = {k: v["passed"] for k, v in results.items()}
        self.assertEqual({"prints": True, "wrong": False, "stays": True, "ends": True, "crashes": False, "tampered": False, "missing": False, "unchecked": False}, passed)
        self.assertIn("SHA-256", results["tampered"]["detail"])
        self.assertIn("signal", results["crashes"]["detail"])

    def test_an_archive_never_lands_outside_its_folder(self):
        sly = zip_with({"../../escaped": (b"x", 0o644)})
        results = self.run_check([self.port("sly", sly, {"stay": 1})])
        self.assertFalse(results["sly"]["passed"])
        self.assertFalse(os.path.exists(os.path.join(self.dir, "escaped")))

    def test_a_zip_holding_a_tarball_is_unpacked_as_the_tarball(self):
        import tarfile
        tar = io.BytesIO()
        with tarfile.open(fileobj=tar, mode="w:gz") as t:
            body = b"#!/bin/sh\necho 'Recompiled 1.2'\n"
            info = tarfile.TarInfo("game")
            info.size = len(body)
            info.mode = 0o755
            t.addfile(info, io.BytesIO(body))
        wrapped = zip_with({"game.tar.gz": (tar.getvalue(), 0o644)})
        port = self.port("wrapped", wrapped, {"args": [], "expect": "Recompiled 1\\.2"})
        port["builds"][0]["kind"] = "ZIP_TAR_GZ"
        self.assertTrue(self.run_check([port])["wrapped"]["passed"])
        two = zip_with({"a.tar.gz": (tar.getvalue(), 0o644), "b.txt": (b"x", 0o644)})
        odd = self.port("odd", two, {"stay": 1})
        odd["builds"][0]["kind"] = "ZIP_TAR_GZ"
        self.assertFalse(self.run_check([odd])["odd"]["passed"])

    def test_only_a_build_that_passed_is_verified_and_the_list_is_signed(self):
        index = {"format": 1, "revision": 3, "ports": [
            {"id": "a", "builds": [{"os": "linux", "sha256": "1" * 64, "verified": "2026-01-01"}, {"os": "windows", "sha256": "2" * 64, "verified": "2026-01-01"}]},
            {"id": "b", "builds": [{"os": "linux", "sha256": "3" * 64}]},
        ]}
        results = [{"a": {"os": "linux", "sha256": "1" * 64, "passed": True, "at": "2026-10-18"}, "b": {"os": "linux", "sha256": "9" * 64, "passed": True, "at": "2026-10-18"}}]
        marked = check.mark(json.loads(json.dumps(index)), results, revision=10)
        self.assertEqual("2026-10-18", marked["ports"][0]["builds"][0]["verified"])
        self.assertNotIn("verified", marked["ports"][0]["builds"][1], "a build nobody checked tonight isn't verified")
        self.assertNotIn("verified", marked["ports"][1]["builds"][0], "a pass for another file isn't this one's")
        self.assertEqual(10, marked["revision"])
        self.assertEqual(4, check.mark(json.loads(json.dumps(index)), [], revision=0)["revision"], "the revision always grows")

        if shutil.which("openssl") is None:
            self.skipTest("no openssl here")
        key = os.path.join(self.dir, "key.pem")
        subprocess.run(["openssl", "genpkey", "-algorithm", "ed25519", "-out", key], check=True, capture_output=True)
        src = os.path.join(self.dir, "in.json")
        with open(src, "w") as f:
            json.dump(index, f)
        out = os.path.join(self.dir, "out")
        os.environ["PORTS_SIGNING_KEY"] = check.read_text(key)
        try:
            self.assertTrue(check.publish(src, [], out, revision=20))
        finally:
            del os.environ["PORTS_SIGNING_KEY"]
        sig = os.path.join(self.dir, "sig.bin")
        with open(sig, "wb") as f:
            f.write(base64.b64decode(check.read_text(os.path.join(out, "index.json.sig"))))
        pub = os.path.join(self.dir, "pub.pem")
        subprocess.run(["openssl", "pkey", "-in", key, "-pubout", "-out", pub], check=True)
        ok = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", pub, "-rawin", "-in", os.path.join(out, "index.json"), "-sigfile", sig], capture_output=True)
        self.assertEqual(0, ok.returncode, ok.stdout + ok.stderr)
        self.assertEqual(20, check.read_json(os.path.join(out, "index.json"))["revision"])
        # Without the key nothing is signed.
        self.assertFalse(check.publish(src, [], os.path.join(self.dir, "unsigned")))
        self.assertFalse(os.path.exists(os.path.join(self.dir, "unsigned", "index.json.sig")))


if __name__ == "__main__":
    unittest.main()


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="fuse-ports-report-")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def results(self, r):
        path = os.path.join(self.dir, "results.json")
        with open(path, "w") as f:
            json.dump(r, f)
        return path

    def test_a_port_that_broke_is_said_and_one_that_started_isnt(self):
        path = self.results({
            "devilutionx": {"os": "linux", "sha256": "a", "at": "2026-10-19", "passed": True, "detail": "still running after 8 s"},
            "openrct2": {"os": "linux", "sha256": "b", "at": "2026-10-19", "passed": False, "detail": "ended with signal 11 | core"},
        })
        text = check.report(path, "https://github.com/o/r/actions/runs/1")
        self.assertIn("1 of 2 ports didn't start in the nightly check on 2026-10-19", text)
        self.assertIn("| openrct2 | linux | ended with signal 11 / core |", text)
        self.assertNotIn("devilutionx", text)
        self.assertTrue(text.rstrip().endswith("https://github.com/o/r/actions/runs/1"))

    def test_nothing_to_say_when_every_port_started(self):
        path = self.results({"devilutionx": {"os": "linux", "sha256": "a", "at": "2026-10-19", "passed": True, "detail": "ok"}})
        self.assertIsNone(check.report(path))

    def test_a_check_that_didnt_finish_is_said(self):
        self.assertIn("didn't finish", check.report(os.path.join(self.dir, "missing.json")))
