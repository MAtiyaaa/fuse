#!/usr/bin/env python3
"""Fuse Ports' nightly check, and the signing of the list Fuse believes.

check:   every port's build for this system is downloaded, compared with the list's size and
         SHA-256, unpacked the way Fuse unpacks it (nothing outside its folder, no links of its
         own), and started without the game's files, as its entry's "check" says: its version
         printed, or still running after some seconds (or ended by itself, never by a crash).
         The results go to a JSON file, one entry per port.
publish: the list with each build's "verified" set to the day it passed (and taken away from one
         that didn't), its revision raised, signed with Ed25519 by OpenSSL with the key in
         PORTS_SIGNING_KEY. Without the key nothing is signed, and the step says so.
report:  what to say in the issue the nightly run opens when a port broke (an empty file when
         every port started), from the results, or that the check didn't finish when there are none.

Only the Python standard library and the openssl command are used.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

MAX_BUILD = 2 << 30
SYSTEM = {"linux": "linux", "win32": "windows", "darwin": "macos"}.get(sys.platform, sys.platform)


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def download(url, size, sha256, into, allow_file=False):
    """Downloads url into the folder into; raises ValueError when it isn't the list's file."""
    if not (url.startswith("https://") or (allow_file and url.startswith("file://"))):
        raise ValueError("not offered over HTTPS")
    if size <= 0 or size > MAX_BUILD:
        raise ValueError("a size Fuse doesn't download")
    path = os.path.join(into, "download")
    digest = hashlib.sha256()
    written = 0
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(path, "wb") as out:
                written = 0
                digest = hashlib.sha256()
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > size:
                        raise ValueError("larger than the list says")
                    digest.update(chunk)
                    out.write(chunk)
            break
        except (OSError, urllib.error.URLError):
            if attempt == 3:
                raise
            time.sleep(2 ** (attempt + 1))
    if written != size:
        raise ValueError(f"{written} bytes, the list says {size}")
    if digest.hexdigest() != sha256:
        raise ValueError("its SHA-256 isn't the list's")
    return path


def _inside(base, name):
    target = os.path.realpath(os.path.join(base, name))
    return target == os.path.realpath(base) or target.startswith(os.path.realpath(base) + os.sep)


def unpack(archive, kind, name, into):
    """Unpacks as Fuse does: an AppImage or a program kept as it is, an archive without links and inside its folder."""
    os.makedirs(into)
    if kind in ("APPIMAGE", "FILE"):
        out = os.path.join(into, os.path.basename(name))
        shutil.copyfile(archive, out)
        os.chmod(out, 0o755)
    elif kind == "ZIP":
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                if not _inside(into, info.filename):
                    raise ValueError("the zip names a place outside its folder")
                mode = info.external_attr >> 16
                if stat.S_ISLNK(mode):
                    continue
                z.extract(info, into)
                if mode & 0o111 and not info.is_dir():
                    os.chmod(os.path.join(into, info.filename), 0o755)
    elif kind == "ZIP_TAR_GZ":
        # A zip holding one tarball (as a project's build artifacts wrap one): the tarball unpacked as one.
        with zipfile.ZipFile(archive) as z:
            files = [i for i in z.infolist() if not i.is_dir()]
            if len(files) != 1 or not (files[0].filename.endswith(".tar.gz") or files[0].filename.endswith(".tgz")):
                raise ValueError("the zip doesn't hold one tarball")
            inner = archive + ".tar.gz"
            with z.open(files[0]) as src, open(inner, "wb") as dst:
                shutil.copyfileobj(src, dst)
        try:
            os.rmdir(into)
            unpack(inner, "TAR_GZ", name, into)
        finally:
            os.remove(inner)
    elif kind in ("TAR_GZ", "TAR_XZ"):
        with tarfile.open(archive, "r:gz" if kind == "TAR_GZ" else "r:xz") as t:
            for m in t.getmembers():
                if m.issym() or m.islnk() or not (m.isfile() or m.isdir()):
                    continue
                if not _inside(into, m.name):
                    raise ValueError("the archive names a place outside its folder")
                t.extract(m, into, set_attrs=False)
                if m.isfile() and m.mode & 0o111:
                    os.chmod(os.path.join(into, m.name), 0o755)
    else:
        raise ValueError(f"a kind of file Fuse doesn't unpack ({kind})")


def started(program, check, cwd):
    """Starts program as its check says; returns (passed, what was seen)."""
    env = dict(os.environ, APPIMAGE_EXTRACT_AND_RUN="1")
    args = [program] + list(check.get("args", []))
    expect = check.get("expect")
    if expect is not None:
        try:
            r = subprocess.run(args, cwd=cwd, env=env, capture_output=True, timeout=120)
        except subprocess.TimeoutExpired:
            return False, "it didn't finish within two minutes"
        out = (r.stdout + r.stderr).decode("utf-8", "replace")
        if re.search(expect, out):
            return True, "printed " + re.search(expect, out).group(0)
        return False, f"exit {r.returncode}, printed: {out.strip()[:200]}"
    stay = int(check.get("stay", 5))
    p = subprocess.Popen(args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        code = p.wait(timeout=stay)
    except subprocess.TimeoutExpired:
        p.kill()
        p.wait()
        if p.stdout:
            p.stdout.close()
        return True, f"still running after {stay} s"
    out = p.stdout.read().decode("utf-8", "replace").strip()[:200] if p.stdout else ""
    if p.stdout:
        p.stdout.close()
    # A negative code is a signal: a crash, never an ending of its own.
    if code < 0:
        return False, f"crashed (signal {-code}): {out}"
    return True, f"ended by itself (exit {code}): {out}"


def check(index_path, out_path, system=SYSTEM, arch="x86_64", allow_file=False):
    index = read_json(index_path)
    results = {}
    today = datetime.date.today().isoformat()
    for port in index["ports"]:
        build = next((b for b in port["builds"] if b["os"] == system and b.get("arch", "x86_64") == arch and b.get("runner", "NATIVE") == "NATIVE"), None)
        if build is None:
            continue
        work = tempfile.mkdtemp(prefix=f"fuse-port-{port['id']}-")
        result = {"os": system, "sha256": build["sha256"], "at": today}
        try:
            archive = download(build["url"], build["size"], build["sha256"], work, allow_file)
            install = os.path.join(work, "build")
            unpack(archive, build["kind"], build["url"].rsplit("/", 1)[-1], install)
            program = os.path.join(install, build["run"])
            if not os.path.isfile(program):
                raise ValueError(f"no {build['run']} in it")
            if port.get("check") is None:
                result.update(passed=False, detail="it has no check")
            else:
                ok, seen = started(program, port["check"], install)
                result.update(passed=ok, detail=seen)
        except Exception as e:  # every failure is this port's result, never the job's end
            result.update(passed=False, detail=str(e))
        finally:
            shutil.rmtree(work, ignore_errors=True)
        results[port["id"]] = result
        print(f"{'pass' if result['passed'] else 'FAIL'}  {port['id']} ({system}): {result['detail']}")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, sort_keys=True)
    return results


def mark(index, results_list, revision):
    """The list with each build verified only when a result for exactly its file passed."""
    passed = {}
    for results in results_list:
        for pid, r in results.items():
            passed[(pid, r["os"], r["sha256"])] = r["at"] if r.get("passed") else None
    for port in index["ports"]:
        for b in port["builds"]:
            b.pop("verified", None)
            at = passed.get((port["id"], b["os"], b["sha256"]))
            if at:
                b["verified"] = at
    index["revision"] = max(int(index.get("revision", 0)) + 1, revision)
    index["generated"] = datetime.date.today().isoformat()
    return index


def publish(index_path, results_paths, out_dir, revision=None):
    index = read_json(index_path)
    results = [read_json(p) for p in results_paths if os.path.exists(p)]
    index = mark(index, results, revision or int(time.time()))
    os.makedirs(out_dir, exist_ok=True)
    text = json.dumps(index, ensure_ascii=False, separators=(",", ":"))
    out = os.path.join(out_dir, "index.json")
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    key = os.environ.get("PORTS_SIGNING_KEY", "").strip()
    if not key:
        print("PORTS_SIGNING_KEY isn't set: the list wasn't signed, and Fuse believes no list without its signature.")
        return False
    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as k:
        k.write(key + "\n")
    try:
        sig = subprocess.run(["openssl", "pkeyutl", "-sign", "-inkey", k.name, "-rawin", "-in", out], check=True, capture_output=True).stdout
    finally:
        os.unlink(k.name)
    import base64
    with open(out + ".sig", "w", encoding="ascii") as f:
        f.write(base64.b64encode(sig).decode("ascii"))
    print(f"Signed revision {index['revision']}.")
    return True


def report(results_path, run_url=None):
    """The issue's text when a port didn't start (or the check didn't finish), else None."""
    where = f"\n\nThe run: {run_url}" if run_url else ""
    if not os.path.exists(results_path):
        return "The nightly check of Fuse Ports didn't finish, so no port was checked." + where + "\n"
    results = read_json(results_path)
    broke = sorted((pid, r) for pid, r in results.items() if not r.get("passed"))
    if not broke:
        return None
    lines = [f"{len(broke)} of {len(results)} ports didn't start in the nightly check on {broke[0][1].get('at', 'its day')}, "
             "so the list marks them unverified until they do.", "", "| Port | System | What was seen |", "|---|---|---|"]
    lines += [f"| {pid} | {r.get('os', '')} | {str(r.get('detail', '')).replace('|', '/')} |" for pid, r in broke]
    return "\n".join(lines) + where + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check")
    c.add_argument("--index", default="ports/index.json")
    c.add_argument("--out", required=True)
    s = sub.add_parser("publish")
    s.add_argument("--index", default="ports/index.json")
    s.add_argument("--results", nargs="*", default=[])
    s.add_argument("--out", required=True)
    r = sub.add_parser("report")
    r.add_argument("--results", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--run-url")
    a = p.parse_args()
    if a.command == "check":
        results = check(a.index, a.out)
        print(f"{sum(r['passed'] for r in results.values())} of {len(results)} started.")
    elif a.command == "report":
        text = report(a.results, a.run_url)
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text or "")
        print(text or "Every port started.")
    else:
        publish(a.index, a.results, a.out)


if __name__ == "__main__":
    main()
