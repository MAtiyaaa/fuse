"""The nightly check of the native PlayStation ports, with made-up releases and zips read by ranges."""

import io
import json
import os
import sys
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import native_check  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "..")


def zip_of(names):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for n in names:
            z.writestr(n, b"x" * 64)
    return buf.getvalue()


class NativeCheckTest(unittest.TestCase):
    port = {
        "id": "spartanport",
        "repo": "FelipePrado0/SpartanPort",
        "builds": [
            {"os": "windows", "asset": "^spartanport-windows\\.zip$", "has": ["SpartanPort.exe", "Play SpartanPort.exe"]},
            {"os": "linux", "asset": "^spartanport-x86_64\\.AppImage$"},
        ],
    }

    def release(self, *names):
        return {"tag_name": "windows-v2.6.0", "assets": [{"name": n, "size": 10, "browser_download_url": f"https://example.org/{n}"} for n in names]}

    def test_a_release_with_its_files_passes(self):
        ok, detail = native_check.check_port(
            self.port,
            latest=lambda repo: self.release("spartanport-windows.zip", "spartanport-x86_64.AppImage"),
            listing=lambda asset: ["spartanport-windows/SpartanPort.exe", "spartanport-windows/Play SpartanPort.exe", "spartanport-windows/user/"],
        )
        self.assertTrue(ok, detail)
        self.assertIn("windows-v2.6.0", detail)
        # As SpartanPort's own zip names them: made on Windows, with its separator.
        self.assertTrue(native_check.has_files(["spartanport-windows\\SpartanPort.exe", "spartanport-windows\\Play SpartanPort.exe"], ["SpartanPort.exe", "Play SpartanPort.exe"]))

    def test_a_renamed_build_or_a_moved_program_fails_by_name(self):
        ok, detail = native_check.check_port(self.port, latest=lambda repo: self.release("spartanport-x86_64.AppImage"), listing=lambda a: [])
        self.assertFalse(ok)
        self.assertIn("no windows file", detail)
        ok, detail = native_check.check_port(
            self.port,
            latest=lambda repo: self.release("spartanport-windows.zip", "spartanport-x86_64.AppImage"),
            listing=lambda asset: ["a/SpartanPort.exe", "b/Play SpartanPort.exe"],
        )
        self.assertFalse(ok, "the files Fuse finds it by must be in one folder")
        self.assertIn("no longer holds", detail)

    def test_github_not_answering_is_said(self):
        def fail(repo):
            raise OSError("timed out")
        ok, detail = native_check.check_port(self.port, latest=fail)
        self.assertFalse(ok)
        self.assertIn("didn't answer", detail)

    def test_a_zip_is_listed_from_its_last_bytes_only(self):
        body = zip_of(["pt-port-1.0.7/pt.exe", "pt-port-1.0.7/shaders/a.spv"] + [f"pt-port-1.0.7/data/{i}.bin" for i in range(50)])
        asked = []

        def fetch(start, end):
            asked.append((start, end))
            return body[start : end + 1]

        f = native_check.RangeFile("https://example.org/pt.zip", len(body), fetch=fetch)
        names = zipfile.ZipFile(io.BufferedReader(f, buffer_size=256)).namelist()
        self.assertIn("pt-port-1.0.7/pt.exe", names)
        self.assertTrue(native_check.has_files(names, ["pt.exe"]))
        self.assertLess(sum(e - s + 1 for s, e in asked), len(body) * 2, "never much more than the listing")

    def test_the_list_names_every_port_fuse_finds(self):
        with open(os.path.join(ROOT, "ports", "native.json"), encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(1, data["format"])
        ids = [p["id"] for p in data["ports"]]
        self.assertEqual(["bloodborne-pc", "spartanport", "uncharted-ndc-pc", "pt-pc"], ids)
        for p in data["ports"]:
            self.assertRegex(p["repo"], r"^[\w.-]+/[\w.-]+$")
            for b in p["builds"]:
                self.assertIn(b["os"], ("windows", "linux"))


if __name__ == "__main__":
    unittest.main()
