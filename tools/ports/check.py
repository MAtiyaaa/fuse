#!/usr/bin/env python3
"""Fuse Ports' nightly check, and the signing of the list Fuse believes.

check:   every port's build for this system (Linux or Windows) is downloaded, compared with the
         list's size and SHA-256, unpacked the way Fuse unpacks it (nothing outside its folder,
         no links of its own), and started without the game's files, as its entry's "check"
         says: its version printed, or still running after some seconds (or ended by itself,
         never by a crash, a missing library or a program the system can't run).
         The results go to a JSON file, one entry per port.
android: every Android port's APK, the one Fuse installs (the newest release with an APK for an
         arm64 device, by the entry's "asset"), is downloaded and read by Android's own tools:
         Android must be able to install it (apksigner verifies its signature), it must be the
         list's app ("package", by aapt2) and run on an arm64 device (its native code).
publish: the list with each build's "verified" set to the day it passed (and taken away from one
         that didn't), each Android port's "verified" and "verifiedVersion" the same way, its
         revision raised, signed with Ed25519 by OpenSSL with the key in PORTS_SIGNING_KEY.
         Without the key nothing is signed, and the step says so.
report:  what to say in the issue the nightly run opens when a port broke (an empty file when
         every port passed), from each system's results, or which check didn't finish.
gate:    on a pull request, a failure when any port in the results didn't pass (or a check didn't
         finish), each said, so a broken port never merges behind a green check.

Only the Python standard library, the openssl command and (for android) the Android SDK's
build tools are used.
"""

import argparse
import datetime
import glob
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
import urllib.error
import urllib.request
import zipfile

MAX_BUILD = 2 << 30
SYSTEM = {"linux": "linux", "win32": "windows", "darwin": "macos"}.get(sys.platform, sys.platform)

# What the dynamic loader (or the shell) says when a program can't start at all on this system.
LOADER = re.compile(
    r"(error while loading shared libraries|cannot open shared object file|version `GLIBC(XX)?_[0-9.]+' not found"
    r"|cannot execute binary file|Exec format error|required file not found)"
)
# Windows' own status codes for a program that crashed or couldn't be loaded (NTSTATUS errors, 0xC0000xxx):
# an access violation (0xC0000005), a missing DLL (0xC0000135), a stack overrun (0xC0000409) and the rest.
NT_ERRORS = range(0xC0000000, 0xC1000000)


def failure_of(code, out):
    """Why a program that ended by itself never really started, or None when it did (it ran and chose to end)."""
    if code < 0:
        return f"crashed (signal {-code})"
    if SYSTEM == "windows" and code in NT_ERRORS:
        return f"crashed or couldn't be loaded (0x{code:08X})"
    if code in (126, 127) or LOADER.search(out):
        found = LOADER.search(out)
        return "this system can't run it" + (f" ({found.group(0)})" if found else f" (exit {code})")
    return None


def quiet_errors():
    """On Windows, no dialog stops a program that crashed or misses a DLL: it ends with its status code instead."""
    if SYSTEM == "windows":
        import ctypes
        # SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX, inherited by every program started.
        ctypes.windll.kernel32.SetErrorMode(0x0001 | 0x0002 | 0x8000)


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


def lend_gl(program, folder=None):
    """On Windows, the DLLs of the OpenGL in FUSE_PORTS_GL put beside the program, where it brings none of its own.

    A runner with no graphics card has only Windows' own OpenGL 1.1, which a port drawing with OpenGL 3
    can't start on; the workflow lends it Mesa's software OpenGL instead, as the virtual screen does on
    Linux. A person's computer has its graphics card's own.
    """
    folder = folder if folder is not None else os.environ.get("FUSE_PORTS_GL")
    if SYSTEM != "windows" or not folder:
        return []
    here = os.path.dirname(program)
    lent = []
    for name in sorted(os.listdir(folder)):
        if name.lower().endswith(".dll") and not os.path.exists(os.path.join(here, name)):
            shutil.copyfile(os.path.join(folder, name), os.path.join(here, name))
            lent.append(name)
    return lent


def stop(p):
    """Ends p and, on Windows, what it started (a launcher's game), so nothing of it outlives its check."""
    if SYSTEM == "windows":
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)
    if p.poll() is None:
        p.kill()
    p.wait()


def started(program, check, cwd):
    """Starts program as its check says; returns (passed, what was seen).

    What it prints goes to a file, never a pipe: a program it starts (a launcher's game) that outlives
    it would hold a pipe open, and reading it would wait for as long as that one runs.
    """
    env = dict(os.environ, APPIMAGE_EXTRACT_AND_RUN="1")
    args = [program] + list(check.get("args", []))
    expect = check.get("expect")
    wait = 120 if expect is not None else int(check.get("stay", 5))
    with tempfile.TemporaryFile() as log:
        p = subprocess.Popen(args, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            code = p.wait(timeout=wait)
        except subprocess.TimeoutExpired:
            stop(p)
            if expect is not None:
                return False, "it didn't finish within two minutes"
            return True, f"still running after {wait} s"
        log.seek(0)
        out = log.read(1 << 20).decode("utf-8", "replace").strip()
    # A signal, a Windows crash code or the loader's word is never an ending of its own.
    broke = failure_of(code, out)
    if broke:
        return False, f"{broke}: {out[:200]}"
    if expect is None:
        return True, f"ended by itself (exit {code}): {out[:200]}"
    if re.search(expect, out):
        return True, "printed " + re.search(expect, out).group(0)
    return False, f"exit {code}, printed: {out[:200]}"


def check(index_path, out_path, system=SYSTEM, arch="x86_64", allow_file=False):
    index = read_json(index_path)
    results = {}
    quiet_errors()
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
            # The list names the program, so it is made runnable as Fuse makes it, whatever the archive said
            # (a zip made on Windows keeps no Unix permissions).
            if SYSTEM != "windows":
                os.chmod(program, os.stat(program).st_mode | 0o755)
            how = port.get("check")
            # A system's own way where it differs (a Windows build that prints nothing to a console).
            if isinstance(how, dict) and isinstance(how.get(system), dict):
                how = how[system]
            if how is None:
                result.update(passed=False, detail="it has no check")
            else:
                lend_gl(program)
                ok, seen = started(program, how, install)
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


# Android: the device a port's APK is checked for, an arm64 handheld (its processor types, best first).
DEVICE_ABIS = ["arm64-v8a", "armeabi-v7a"]
# Fuse's ApkPicker: the names each processor type goes by in a file's name, and what counts as an app file.
ABI_ALIASES = {"arm64-v8a": ["aarch64", "arm64"], "armeabi-v7a": ["armv7", "armeabi"], "x86_64": ["x64"]}
APP_FILES = (".apk", ".xapk", ".apkm", ".apks")
GITHUB_REPO = re.compile(r"^https://github\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})/?$")


def app_files(release, asset):
    """A release's app files that the entry's asset filter (a regular expression over the name) takes."""
    files = [a for a in release.get("assets", []) if a["name"].lower().endswith(APP_FILES)]
    if asset:
        files = [a for a in files if re.search(asset, a["name"])]
    return files


def release_of(releases, asset):
    """The release Fuse installs a port from: as GitHub lists them, newest first, the first that isn't a
    draft or a pre-release and has an app file the asset filter takes (older ones when the newest has none)."""
    for r in releases:
        if r.get("draft") or r.get("prerelease"):
            continue
        if app_files(r, asset):
            return r
    return None


def by_arch(files, abis):
    """ApkPicker.byArch: the files for the first of abis that narrows the list; a list it can't narrow as it is."""
    if len(files) <= 1:
        return files
    for abi in abis:
        words = re.compile("|".join(re.escape(w) for w in [abi] + ABI_ALIASES.get(abi, [])), re.I)
        narrowed = [f for f in files if words.search(f["name"])]
        if narrowed and len(narrowed) < len(files):
            return narrowed
    return files


def apk_of(release, asset):
    """ApkPicker.choose for a port: its one APK, else the one for the device, a universal one, the first; or why none."""
    files = app_files(release, asset)
    apks = [f for f in files if f["name"].lower().endswith(".apk")]
    if not apks:
        return None, "its release has only a split bundle or a zip, which Fuse doesn't install"
    if len(apks) == 1:
        return apks[0], None
    narrowed = by_arch(apks, DEVICE_ABIS)
    if len(narrowed) == 1:
        return narrowed[0], None
    return next((f for f in narrowed if "universal" in f["name"].lower()), narrowed[0]), None


def github_releases(owner, name):
    """The repository's releases, newest first, through GitHub's API (with GITHUB_TOKEN when it is set)."""
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "Fuse ports check"}
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        headers["Authorization"] = f"Bearer {tok}"
    url = f"https://api.github.com/repos/{owner}/{name}/releases?per_page=50"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                return [
                    {"tag": x.get("tag_name", ""), "draft": x.get("draft", False), "prerelease": x.get("prerelease", False),
                     "assets": [{"name": a["name"], "url": a["browser_download_url"], "size": a.get("size", 0), "digest": a.get("digest")} for a in x.get("assets", [])]}
                    for x in json.loads(r.read())
                ]
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise ValueError("GitHub has no such repository")
            if attempt == 3:
                raise
        except (OSError, urllib.error.URLError):
            if attempt == 3:
                raise
        time.sleep(2 ** (attempt + 1))
    return []


def fetch(url, limit, into):
    """Downloads url (HTTPS) into the folder into: its path, size and SHA-256."""
    if not url.startswith("https://"):
        raise ValueError("not offered over HTTPS")
    path = os.path.join(into, "app.apk")
    for attempt in range(4):
        try:
            digest, written = hashlib.sha256(), 0
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Fuse ports check"}), timeout=300) as r, open(path, "wb") as out:
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > limit:
                        raise ValueError("larger than Fuse downloads")
                    digest.update(chunk)
                    out.write(chunk)
            return path, written, digest.hexdigest()
        except (OSError, urllib.error.URLError):
            if attempt == 3:
                raise
            time.sleep(2 ** (attempt + 1))


def build_tool(name):
    """The newest copy of one of the Android SDK's build tools (aapt2, apksigner), or None."""
    def version(path):
        return [int(n) for n in re.findall(r"\d+", os.path.basename(os.path.dirname(path)))]
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "/usr/local/lib/android/sdk"):
        if root:
            found = sorted(glob.glob(os.path.join(root, "build-tools", "*", name)), key=version)
            if found:
                return found[-1]
    return None


def badging(apk):
    """What aapt2 reads in the APK's manifest: its package, version, oldest Android and processor types."""
    tool = build_tool("aapt2")
    if not tool:
        raise ValueError("aapt2 isn't installed here")
    r = subprocess.run([tool, "dump", "badging", apk], capture_output=True, timeout=180)
    out = r.stdout.decode("utf-8", "replace")
    if r.returncode != 0 or "package: name=" not in out:
        raise ValueError("Android's aapt2 can't read it as an app: " + r.stderr.decode("utf-8", "replace").strip()[:160])

    def one(rx):
        m = re.search(rx, out)
        return m.group(1) if m else None
    return {
        "package": one(r"package: name='([^']+)'"),
        "versionCode": one(r"versionCode='([^']*)'"),
        "versionName": one(r"versionName='([^']*)'"),
        "minSdk": one(r"(?:minSdkVersion|sdkVersion):'(\d+)'"),
        "abis": re.findall(r"'([a-z0-9_-]+)'", one(r"native-code:(.*)") or ""),
    }


def signer(apk):
    """apksigner's word on the APK: (True, its signer's SHA-256) when Android would accept its signature, else (False, why)."""
    tool = build_tool("apksigner")
    if not tool:
        raise ValueError("apksigner isn't installed here")
    r = subprocess.run([tool, "verify", "--print-certs", apk], capture_output=True, timeout=180)
    out = (r.stdout + r.stderr).decode("utf-8", "replace")
    if r.returncode != 0:
        return False, out.strip().splitlines()[0][:160] if out.strip() else f"exit {r.returncode}"
    m = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)
    return True, m.group(1) if m else ""


def check_android(index_path, out_path, releases=github_releases, download_apk=fetch, read=badging, verify=signer):
    """Each Android port's APK, the one Fuse installs, read as an app Android installs on an arm64 device."""
    index = read_json(index_path)
    results = {}
    today = datetime.date.today().isoformat()
    for port in index.get("android", []):
        result = {"os": "android", "at": today}
        work = tempfile.mkdtemp(prefix=f"fuse-port-{port['id']}-")
        try:
            repo = GITHUB_REPO.match(port.get("repo", ""))
            if not repo:
                raise ValueError("its project isn't on GitHub, where Fuse installs from")
            if not port.get("package"):
                raise ValueError("the list doesn't say its package")
            release = release_of(releases(repo.group(1), repo.group(2).removesuffix(".git")), port.get("asset"))
            if release is None:
                raise ValueError("no release has an APK Fuse would take")
            apk, why = apk_of(release, port.get("asset"))
            if apk is None:
                raise ValueError(why)
            if apk.get("size", 0) > MAX_BUILD:
                raise ValueError("larger than Fuse downloads")
            path, size, sha = download_apk(apk["url"], MAX_BUILD, work)
            if apk.get("digest") and apk["digest"] != f"sha256:{sha}":
                raise ValueError("the download isn't the file GitHub says it is")
            result.update(tag=release["tag"], apk=apk["name"], size=size, sha256=sha)
            facts = read(path)
            result.update(version=facts.get("versionName"), versionCode=facts.get("versionCode"), minSdk=facts.get("minSdk"))
            if facts.get("package") != port["package"]:
                raise ValueError(f"it is {facts.get('package')}, not the list's {port['package']}")
            abis = facts.get("abis") or []
            if abis and "arm64-v8a" not in abis:
                raise ValueError(f"it has no code for an arm64 device, only {', '.join(abis)}")
            ok, seen = verify(path)
            if not ok:
                raise ValueError(f"Android wouldn't install it: {seen}")
            result.update(signer=seen, passed=True, detail=f"{apk['name']} ({release['tag']}): {port['package']} {facts.get('versionName') or ''}".strip())
        except Exception as e:  # every failure is this port's result, never the job's end
            result.update(passed=False, detail=str(e)[:300])
        finally:
            shutil.rmtree(work, ignore_errors=True)
        results[port["id"]] = result
        print(f"{'pass' if result['passed'] else 'FAIL'}  {port['id']} (android): {result['detail']}", flush=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, sort_keys=True)
    return results


# A published list's revision: the list's own revision times this, plus when it was checked. Fuse
# orders lists by the list's own revision first, so a list published before the one inside a newer
# Fuse never stands in for it, however recently it was checked.
PUBLISHED = 10 ** 10


def published_revision(base, stamp):
    """The revision a list of revision [base], checked at [stamp] (seconds), is published as."""
    if base >= PUBLISHED:
        base //= PUBLISHED
    return base * PUBLISHED + stamp % PUBLISHED


def mark(index, results_list, revision):
    """The list with each build verified only when a result for exactly its file passed, and each
    Android port only when tonight's check of its APK did."""
    passed = {}
    android = {}
    for results in results_list:
        for pid, r in results.items():
            if r.get("os") == "android":
                android[pid] = r if r.get("passed") else None
            else:
                passed[(pid, r["os"], r["sha256"])] = r["at"] if r.get("passed") else None
    for port in index["ports"]:
        for b in port["builds"]:
            b.pop("verified", None)
            at = passed.get((port["id"], b["os"], b["sha256"]))
            if at:
                b["verified"] = at
    for port in index.get("android", []):
        port.pop("verified", None)
        port.pop("verifiedVersion", None)
        r = android.get(port["id"])
        if r:
            port["verified"] = r["at"]
            if r.get("version"):
                port["verifiedVersion"] = r["version"]
    index["revision"] = published_revision(int(index.get("revision", 0)), revision)
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


SYSTEM_NAMES = {"linux": "Linux", "windows": "Windows", "macos": "macOS", "android": "Android"}


def gate(results_paths):
    """What didn't pass in results_paths, one line each (a missing file is a check that didn't finish).

    A pull request's check fails on any, so a port that doesn't pass is fixed or taken out before the
    list is merged; each night the results stand as they are, and only what passed is verified.
    """
    broken = []
    for path in results_paths:
        if not os.path.exists(path):
            broken.append(f"{os.path.basename(path)}: the check didn't finish")
            continue
        for pid, r in sorted(read_json(path).items()):
            if not r.get("passed"):
                broken.append(f"{pid} ({r.get('os', '')}): {r.get('detail', '')}")
    return broken


def report(results_paths, run_url=None):
    """The issue's text when a port didn't pass (or a system's check didn't finish), else None."""
    if isinstance(results_paths, str):
        results_paths = [results_paths]
    where = f"\n\nThe run: {run_url}" if run_url else ""
    lines = []
    for path in results_paths:
        if not os.path.exists(path):
            m = re.search(r"results-([a-z]+)\.json$", path)
            system = SYSTEM_NAMES.get(m.group(1), m.group(1)) if m else None
            lines.append(f"The nightly check of Fuse Ports{f' on {system}' if system else ''} didn't finish, so none of its ports was checked.")
    rows = []
    total = 0
    at = None
    for path in results_paths:
        if os.path.exists(path):
            results = read_json(path)
            total += len(results)
            for pid, r in sorted(results.items()):
                if not r.get("passed"):
                    rows.append((pid, r))
                    at = at or r.get("at")
    if not rows and not lines:
        return None
    if rows:
        if lines:
            lines.append("")
        lines += [f"{len(rows)} of {total} ports didn't pass the nightly check on {at or 'its day'}, "
                  "so the list marks them unverified until they do.", "", "| Port | System | What was seen |", "|---|---|---|"]
        lines += [f"| {pid} | {r.get('os', '')} | {str(r.get('detail', '')).replace('|', '/')} |" for pid, r in rows]
    return "\n".join(lines) + where + "\n"


def main():
    # What a program printed can be in any language; Windows' console code page holds little of it.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check")
    c.add_argument("--index", default="ports/index.json")
    c.add_argument("--out", required=True)
    d = sub.add_parser("android")
    d.add_argument("--index", default="ports/index.json")
    d.add_argument("--out", required=True)
    s = sub.add_parser("publish")
    s.add_argument("--index", default="ports/index.json")
    s.add_argument("--results", nargs="*", default=[])
    s.add_argument("--out", required=True)
    g = sub.add_parser("gate")
    g.add_argument("--results", nargs="+", required=True)
    r = sub.add_parser("report")
    r.add_argument("--results", nargs="+", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--run-url")
    a = p.parse_args()
    if a.command == "check":
        results = check(a.index, a.out)
        print(f"{sum(r['passed'] for r in results.values())} of {len(results)} started.")
    elif a.command == "android":
        results = check_android(a.index, a.out)
        print(f"{sum(r['passed'] for r in results.values())} of {len(results)} passed.")
    elif a.command == "gate":
        broken = gate(a.results)
        for line in broken:
            print(line)
        if broken:
            sys.exit(f"{len(broken)} didn't pass, and on a pull request every port must.")
        print("Every port passed.")
    elif a.command == "report":
        text = report(a.results, a.run_url)
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text or "")
        print(text or "Every port passed.")
    else:
        publish(a.index, a.results, a.out)


if __name__ == "__main__":
    main()
