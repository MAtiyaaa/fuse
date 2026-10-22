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
from unittest import mock

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
        # As the dynamic loader ends a program whose library isn't on this system.
        unloadable = zip_with({"game": (b"#!/bin/sh\necho './game: error while loading shared libraries: libSDL2_mixer-2.0.so.0: cannot open shared object file' >&2\nexit 127\n", 0o755)})
        results = self.run_check([
            self.port("prints", prints, {"args": ["--version"], "expect": "SkyPort v2\\.1"}),
            self.port("wrong", prints, {"args": ["--version"], "expect": "SkyPort v9"}),
            self.port("stays", stays, {"stay": 1}),
            self.port("ends", ends, {"stay": 5}),
            self.port("crashes", crashes, {"stay": 5}),
            self.port("unloadable", unloadable, {"stay": 5}),
            self.port("unloadable-version", unloadable, {"args": ["--version"], "expect": "."}),
            self.port("tampered", prints, {"args": [], "expect": "."}, sha="0" * 64),
            self.port("missing", prints, {"stay": 1}, run="other"),
            self.port("unchecked", prints, None),
        ])
        passed = {k: v["passed"] for k, v in results.items()}
        self.assertEqual({
            "prints": True, "wrong": False, "stays": True, "ends": True, "crashes": False, "unloadable": False,
            "unloadable-version": False, "tampered": False, "missing": False, "unchecked": False,
        }, passed)
        self.assertIn("SHA-256", results["tampered"]["detail"])
        self.assertIn("signal", results["crashes"]["detail"])
        self.assertIn("can't run it", results["unloadable"]["detail"])
        self.assertIn("libSDL2_mixer", results["unloadable"]["detail"])
        self.assertIn("can't run it", results["unloadable-version"]["detail"], "a missing library isn't a version it printed")

    def test_the_lists_program_is_made_runnable_when_its_zip_kept_no_permissions(self):
        windows_made = zip_with({"game": (b"#!/bin/sh\necho 'SkyPort v2.1'\n", 0o644)})
        results = self.run_check([self.port("windows-made", windows_made, {"args": ["--version"], "expect": "SkyPort v2\\.1"})])
        self.assertTrue(results["windows-made"]["passed"], results["windows-made"]["detail"])

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


class EndingTest(unittest.TestCase):
    """What a program's ending says: started and chose to end, or never really started."""

    def test_on_linux_a_signal_or_the_loaders_word_is_never_an_ending_of_its_own(self):
        with mock.patch.object(check, "SYSTEM", "linux"):
            self.assertIsNone(check.failure_of(0, "DevilutionX v1.5.5"))
            self.assertIsNone(check.failure_of(1, "Couldn't find DIABDAT.MPQ: No such file or directory"))
            self.assertIn("signal 11", check.failure_of(-11, ""))
            self.assertIn("can't run it", check.failure_of(127, ""))
            self.assertIn("GLIBC", check.failure_of(1, "./game: /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_2.38' not found"))

    def test_on_windows_its_crash_and_loader_codes_are_crashes_and_exit_minus_one_isnt(self):
        with mock.patch.object(check, "SYSTEM", "windows"):
            self.assertIn("0xC0000005", check.failure_of(0xC0000005, ""))
            self.assertIn("0xC0000135", check.failure_of(0xC0000135, ""), "a missing DLL")
            self.assertIn("0xC0000409", check.failure_of(0xC0000409, ""))
            self.assertIsNone(check.failure_of(0xFFFFFFFF, "game files not found"), "exit(-1) is the program's own ending")
            self.assertIsNone(check.failure_of(1, ""))


def release(tag, *names, draft=False, prerelease=False):
    return {"tag": tag, "draft": draft, "prerelease": prerelease, "assets": [{"name": n, "url": f"https://github.com/o/r/releases/download/{tag}/{n}", "size": 10} for n in names]}


class AndroidTest(unittest.TestCase):
    """The Android check with GitHub, the download and Android's tools made up."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="fuse-ports-android-")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_the_release_is_the_newest_with_an_apk_fuse_takes(self):
        releases = [
            release("atlas-maps-2", "maps.zip"),
            release("v3-beta", "game-v3.apk", prerelease=True),
            release("v2", "game-v2-arm64-v8a.apk", "game-v2-armeabi-v7a.apk", "game-v2-x86_64.apk"),
            release("v1", "game-v1.apk"),
        ]
        chosen = check.release_of(releases, None)
        self.assertEqual("v2", chosen["tag"], "a data release and a pre-release on top are passed over")
        self.assertEqual("game-v2-arm64-v8a.apk", check.apk_of(chosen, None)[0]["name"], "the arm64 APK of several")
        self.assertEqual("v1", check.release_of(releases, r"^game-v1\.apk$")["tag"], "the entry's asset filter decides")
        universal = release("v4", "game-arm64-v8a.apk", "game-universal.apk", "game-x86.apk")
        self.assertEqual("game-arm64-v8a.apk", check.apk_of(universal, None)[0]["name"])
        both = release("v5", "game-universal.apk", "game-legacy.apk")
        self.assertEqual("game-universal.apk", check.apk_of(both, None)[0]["name"], "a universal one when the processor doesn't narrow it")
        bundle = release("v6", "game.apks")
        self.assertIsNotNone(check.release_of([bundle], None))
        self.assertIsNone(check.apk_of(bundle, None)[0], "a split bundle isn't installed")

    def run_android(self, ports, releases, facts, signed=(True, "ab" * 32)):
        index = os.path.join(self.dir, "index.json")
        with open(index, "w") as f:
            json.dump({"format": 1, "revision": 3, "ports": [], "android": ports}, f)

        def download(url, limit, into):
            path = os.path.join(into, "app.apk")
            with open(path, "wb") as f:
                f.write(url.encode())
            return path, 10, hashlib.sha256(url.encode()).hexdigest()

        return check.check_android(index, os.path.join(self.dir, "android.json"), releases=lambda o, n: releases[f"{o}/{n}"],
                                   download_apk=download, read=lambda p: facts[open(p).read().rsplit("/", 1)[-1]], verify=lambda p: signed)

    def test_each_apk_must_be_the_lists_app_signed_and_for_an_arm64_device(self):
        def port(pid, repo, package="org.example.game", asset=None):
            return {"id": pid, "repo": f"https://github.com/{repo}", "package": package, "asset": asset}
        releases = {
            "a/good": [release("v2", "good.apk")],
            "a/other": [release("v1", "other.apk")],
            "a/x86": [release("v1", "x86only.apk")],
            "a/none": [release("v1", "data.zip")],
            "a/pure": [release("v1", "pure.apk")],
        }
        facts = {
            "good.apk": {"package": "org.example.game", "versionName": "2.0", "abis": ["arm64-v8a", "x86_64"]},
            "other.apk": {"package": "org.someone.else", "versionName": "1.0", "abis": []},
            "x86only.apk": {"package": "org.example.game", "versionName": "1.0", "abis": ["x86_64"]},
            "pure.apk": {"package": "org.example.game", "versionName": "1.0", "abis": []},
        }
        results = self.run_android([
            port("good", "a/good"), port("other", "a/other"), port("x86", "a/x86"), port("none", "a/none"),
            port("pure", "a/pure"), port("unnamed", "a/good", package=None), {"id": "elsewhere", "repo": "https://gitlab.com/a/b", "package": "x"},
        ], releases, facts)
        passed = {k: v["passed"] for k, v in results.items()}
        self.assertEqual({"good": True, "other": False, "x86": False, "none": False, "pure": True, "unnamed": False, "elsewhere": False}, passed)
        self.assertEqual("2.0", results["good"]["version"])
        self.assertEqual("android", results["good"]["os"])
        self.assertEqual("v2", results["good"]["tag"])
        self.assertIn("org.someone.else", results["other"]["detail"])
        self.assertIn("arm64", results["x86"]["detail"])
        unsigned = self.run_android([port("good", "a/good")], releases, facts, signed=(False, "DOES NOT VERIFY"))
        self.assertFalse(unsigned["good"]["passed"])
        self.assertIn("wouldn't install it", unsigned["good"]["detail"])

    def test_an_android_port_is_verified_only_by_tonights_pass(self):
        index = {"format": 1, "revision": 3, "ports": [], "android": [
            {"id": "a", "package": "x", "verified": "2026-01-01", "verifiedVersion": "0.9"},
            {"id": "b", "package": "y", "verified": "2026-01-01"},
            {"id": "c", "package": "z"},
        ]}
        results = [{"a": {"os": "android", "passed": True, "at": "2026-10-22", "version": "1.0"}, "b": {"os": "android", "passed": False, "at": "2026-10-22"}}]
        marked = check.mark(index, results, revision=10)["android"]
        self.assertEqual(("2026-10-22", "1.0"), (marked[0]["verified"], marked[0]["verifiedVersion"]))
        self.assertNotIn("verified", marked[1], "a port that failed tonight isn't verified")
        self.assertNotIn("verified", marked[2], "nor one nobody checked")



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
        self.assertIn("1 of 2 ports didn't pass the nightly check on 2026-10-19", text)
        self.assertIn("| openrct2 | linux | ended with signal 11 / core |", text)
        self.assertNotIn("devilutionx", text)
        self.assertTrue(text.rstrip().endswith("https://github.com/o/r/actions/runs/1"))

    def test_nothing_to_say_when_every_port_started(self):
        path = self.results({"devilutionx": {"os": "linux", "sha256": "a", "at": "2026-10-19", "passed": True, "detail": "ok"}})
        self.assertIsNone(check.report(path))

    def test_a_check_that_didnt_finish_is_said(self):
        self.assertIn("didn't finish", check.report(os.path.join(self.dir, "missing.json")))

    def test_every_systems_results_are_reported_together(self):
        linux = self.results({"devilutionx": {"os": "linux", "sha256": "a", "at": "2026-10-19", "passed": True, "detail": "ok"}})
        android = os.path.join(self.dir, "results-android.json")
        with open(android, "w") as f:
            json.dump({"gen1recomp": {"os": "android", "at": "2026-10-19", "passed": False, "detail": "it is org.other, not the list's org.gen1"}}, f)
        text = check.report([linux, android, os.path.join(self.dir, "results-windows.json")])
        self.assertIn("The nightly check of Fuse Ports on Windows didn't finish", text)
        self.assertIn("1 of 2 ports didn't pass", text)
        self.assertIn("| gen1recomp | android | it is org.other, not the list's org.gen1 |", text)


if __name__ == "__main__":
    unittest.main()
