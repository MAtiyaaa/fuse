#!/usr/bin/env python3
"""Scouts the projects of candidate ports for the facts Fuse's list of ports needs.

For each repository in candidates.json (owner/name and whether it is wanted for Android, a computer
or both), through GitHub's GraphQL API: its licence, whether it is archived, and its newest releases
with their files. Then, from those releases:

- android: the newest release with an APK, the APK for arm64 (or for every processor) downloaded
  once to take its SHA-256 and read by Android's own aapt2: its package, version, processors and
  the oldest Android it runs on;
- desktop: the Linux and Windows x86_64 files Fuse can unpack (an AppImage, a zip, a tarball),
  each downloaded once to take its SHA-256 and list what it holds, with the programs in it.

Writes report.json. Nothing is installed or run. Only the Python standard library, aapt2 from the
runner's Android SDK, and GITHUB_TOKEN for the API.
"""

import argparse
import glob
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

API = "https://api.github.com/graphql"
MAX_DOWNLOAD = 1536 << 20
BATCH = 20

ARCHIVES = (".appimage", ".zip", ".tar.gz", ".tgz", ".tar.xz", ".exe")
NOT_X64 = re.compile(r"(arm64|aarch64|armhf|armv7|\barm\b|i386|i686|\bx86\b(?!_64)|x86[-_]32|win32[-_]x86|32[-_]?bit|riscv|ppc|mips|universal2?)", re.I)
MAC = re.compile(r"(mac|darwin|osx|\.dmg$|apple)", re.I)
LINUX = re.compile(r"(linux|appimage|ubuntu|debian|steamos|steamdeck|\bdeck\b|lin64|\blnx\b|x86_64\.tar)", re.I)
WINDOWS = re.compile(r"(win(dows)?(64|-?x64)?|msvc|mingw|\bpc\b)", re.I)
X64 = re.compile(r"(x86[-_]?64|x64|amd64|win64|64[-_]?bit)", re.I)
NOT_GAME = re.compile(r"(symbols|debug|pdb|source|src|-dev\b|devel|headers|sdk|server|dedicated|installer|setup|portable-?data)", re.I)


def token():
    t = os.environ.get("GITHUB_TOKEN", "").strip()
    if not t:
        sys.exit("GITHUB_TOKEN isn't set")
    return t


def graphql(query, tok):
    body = json.dumps({"query": query}).encode()
    for attempt in range(6):
        req = urllib.request.Request(API, data=body, headers={"Authorization": f"bearer {tok}", "Content-Type": "application/json", "User-Agent": "Fuse ports scout"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.loads(r.read())
            if "errors" in data and not data.get("data"):
                raise RuntimeError(json.dumps(data["errors"])[:500])
            return data.get("data") or {}, data.get("errors") or []
        except urllib.error.HTTPError as e:
            if e.code in (403, 429, 502, 503):
                reset = e.headers.get("x-ratelimit-reset")
                wait = max(5, int(reset) - int(time.time()) + 2) if reset and e.headers.get("x-ratelimit-remaining") == "0" else 10 * (attempt + 1)
                print(f"  API said {e.code}; waiting {wait} s", flush=True)
                time.sleep(min(wait, 3700))
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("the API didn't answer")


def repo_query(batch):
    parts = []
    for i, full in enumerate(batch):
        owner, name = full.split("/", 1)
        parts.append(
            f'r{i}: repository(owner: {json.dumps(owner)}, name: {json.dumps(name)}) {{ nameWithOwner isArchived licenseInfo {{ spdxId }} '
            f'defaultBranchRef {{ name }} releases(first: 6, orderBy: {{field: CREATED_AT, direction: DESC}}) {{ nodes {{ tagName isPrerelease isDraft publishedAt '
            f'releaseAssets(first: 80) {{ nodes {{ name size downloadUrl }} }} }} }} }}'
        )
    return "query { " + " ".join(parts) + " rateLimit { remaining resetAt } }"


def download(url, into):
    path = os.path.join(into, "file")
    digest = hashlib.sha256()
    size = 0
    req = urllib.request.Request(url, headers={"User-Agent": "Fuse ports scout"})
    with urllib.request.urlopen(req, timeout=300) as r, open(path, "wb") as out:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_DOWNLOAD:
                raise ValueError("larger than the scout downloads")
            digest.update(chunk)
            out.write(chunk)
    return path, size, digest.hexdigest()


def kind_of(name):
    n = name.lower()
    if n.endswith(".appimage"):
        return "APPIMAGE"
    if n.endswith(".zip"):
        return "ZIP"
    if n.endswith(".tar.gz") or n.endswith(".tgz"):
        return "TAR_GZ"
    if n.endswith(".tar.xz"):
        return "TAR_XZ"
    if n.endswith(".exe"):
        return "FILE"
    return None


def is_program(name, head, system):
    n = name.lower()
    if system == "windows":
        return n.endswith(".exe") and head[:2] == b"MZ"
    return head[:4] == b"\x7fELF" and not re.search(r"\.so(\.\d+)*$", n)


def contents(path, kind, system):
    """What an archive holds (its first entries) and the programs in it (with their sizes)."""
    entries, programs = [], []
    if kind in ("APPIMAGE", "FILE"):
        return entries, programs
    if kind == "ZIP":
        with zipfile.ZipFile(path) as z:
            infos = [i for i in z.infolist() if not i.is_dir()]
            entries = [i.filename for i in infos][:400]
            for i in infos:
                low = i.filename.lower()
                if system == "windows" and not low.endswith(".exe"):
                    continue
                if system == "linux" and "." in os.path.basename(low) and not low.endswith((".x86_64", ".bin", ".appimage", ".sh")):
                    continue
                if i.file_size < 4096:
                    continue
                with z.open(i) as f:
                    head = f.read(4)
                if is_program(i.filename, head, system):
                    programs.append({"path": i.filename, "size": i.file_size, "mode": oct((i.external_attr >> 16) & 0o777)})
    else:
        with tarfile.open(path, "r:gz" if kind == "TAR_GZ" else "r:xz") as t:
            members = [m for m in t.getmembers() if m.isfile()]
            entries = [m.name for m in members][:400]
            for m in members:
                low = m.name.lower()
                if "." in os.path.basename(low) and not low.endswith((".x86_64", ".bin", ".appimage", ".exe")):
                    continue
                if m.size < 4096:
                    continue
                f = t.extractfile(m)
                head = f.read(4) if f else b""
                if is_program(m.name, head, system):
                    programs.append({"path": m.name, "size": m.size, "mode": oct(m.mode & 0o777)})
    return entries, programs[:40]


def score(name, system):
    """How likely a release's file is this system's x86_64 build Fuse can unpack; 0: it isn't."""
    n = name.lower()
    if not n.endswith(ARCHIVES) or NOT_GAME.search(n) or MAC.search(n) or "android" in n or n.endswith(".apk"):
        return 0
    if NOT_X64.search(n):
        return 0
    if system == "linux":
        if n.endswith(".exe") or WINDOWS.search(n) and not LINUX.search(n):
            return 0
        s = 3 if LINUX.search(n) else 0
        if s == 0:
            return 0
        if n.endswith(".appimage"):
            s += 2
    else:
        if LINUX.search(n) or n.endswith((".appimage", ".tar.gz", ".tgz", ".tar.xz")):
            return 0
        s = 3 if WINDOWS.search(n) else 0
        if s == 0:
            return 0
        if n.endswith(".exe"):
            s -= 1
    if X64.search(n):
        s += 2
    return s


def pick(releases, system, limit=2):
    """The newest release with files for [system], and its likeliest files."""
    for rel in releases:
        if rel.get("isDraft"):
            continue
        assets = rel.get("releaseAssets", {}).get("nodes", [])
        ranked = sorted(((score(a["name"], system), a) for a in assets), key=lambda x: (-x[0], x[1]["size"]))
        ranked = [a for s, a in ranked if s > 0]
        if ranked:
            return rel, ranked[:limit]
    return None, []


def aapt2():
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "/usr/local/lib/android/sdk"):
        if not root:
            continue
        found = sorted(glob.glob(os.path.join(root, "build-tools", "*", "aapt2")))
        if found:
            return found[-1]
    return None


def badging(tool, apk):
    out = subprocess.run([tool, "dump", "badging", apk], capture_output=True, timeout=120).stdout.decode("utf-8", "replace")
    def one(rx):
        m = re.search(rx, out)
        return m.group(1) if m else None
    return {
        "package": one(r"package: name='([^']+)'"),
        "versionCode": one(r"versionCode='([^']*)'"),
        "versionName": one(r"versionName='([^']*)'"),
        "minSdk": one(r"(?:minSdkVersion|sdkVersion):'(\d+)'"),
        "label": one(r"application-label:'([^']*)'"),
        "abis": re.findall(r"'([a-z0-9_-]+)'", one(r"native-code:(.*)") or ""),
    }


def apk_score(name):
    n = name.lower()
    if not n.endswith(".apk"):
        return 0
    if re.search(r"(x86|armeabi(?!-v8)|armv7|v7a|debug|unsigned)", n) and not re.search(r"(arm64|v8a|universal)", n):
        return 1
    return 3 + (2 if re.search(r"(arm64|v8a)", n) else 0) + (1 if "universal" in n else 0)


def scout_android(releases, work, tool):
    for rel in releases:
        if rel.get("isDraft"):
            continue
        apks = sorted((a for a in rel.get("releaseAssets", {}).get("nodes", []) if a["name"].lower().endswith(".apk")), key=lambda a: -apk_score(a["name"]))
        if not apks:
            continue
        a = apks[0]
        out = {"tag": rel["tagName"], "prerelease": rel["isPrerelease"], "published": rel["publishedAt"], "apks": [x["name"] for x in apks][:12],
               "apk": {"name": a["name"], "url": a["downloadUrl"], "size": a["size"]}}
        try:
            path, size, sha = download(a["downloadUrl"], work)
            out["apk"].update(size=size, sha256=sha)
            if tool:
                out["apk"].update(badging(tool, path))
        except Exception as e:
            out["apk"]["problem"] = str(e)[:200]
        finally:
            for f in os.listdir(work):
                os.remove(os.path.join(work, f))
        return out
    return None


def scout_desktop(releases, system, work):
    rel, assets = pick(releases, system)
    if not rel:
        return None
    out = {"tag": rel["tagName"], "prerelease": rel["isPrerelease"], "published": rel["publishedAt"], "files": []}
    for a in assets:
        f = {"name": a["name"], "url": a["downloadUrl"], "size": a["size"], "kind": kind_of(a["name"])}
        try:
            path, size, sha = download(a["downloadUrl"], work)
            f.update(size=size, sha256=sha)
            f["entries"], f["programs"] = contents(path, f["kind"], system)
        except Exception as e:
            f["problem"] = str(e)[:200]
        finally:
            for x in os.listdir(work):
                os.remove(os.path.join(work, x))
        out["files"].append(f)
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--candidates", default="tools/ports/scout/candidates.json")
    p.add_argument("--out", default="tools/ports/scout/report.json")
    p.add_argument("--only", choices=["android", "desktop"], default=None)
    a = p.parse_args()
    tok = token()
    cands = json.load(open(a.candidates, encoding="utf-8"))["repos"]
    if a.only:
        cands = [c for c in cands if a.only in c["want"]]
    report = {"generated": time.strftime("%Y-%m-%d"), "repos": {}}
    if os.path.exists(a.out):
        report = json.load(open(a.out, encoding="utf-8"))
    tool = aapt2()
    print(f"{len(cands)} repositories; aapt2: {tool}", flush=True)
    work = tempfile.mkdtemp(prefix="scout-")
    for start in range(0, len(cands), BATCH):
        batch = [c for c in cands[start:start + BATCH] if c["repo"] not in report["repos"]]
        if not batch:
            continue
        data, errors = graphql(repo_query([c["repo"] for c in batch]), tok)
        for i, c in enumerate(batch):
            r = data.get(f"r{i}")
            entry = {"want": c["want"], "listings": c["listings"]}
            if r is None:
                entry["problem"] = "not found"
                report["repos"][c["repo"]] = entry
                continue
            releases = r["releases"]["nodes"]
            entry.update(
                name=r["nameWithOwner"], archived=r["isArchived"], licence=(r.get("licenseInfo") or {}).get("spdxId"),
                branch=(r.get("defaultBranchRef") or {}).get("name"),
                releases=[
                    {"tag": x["tagName"], "prerelease": x["isPrerelease"], "published": x["publishedAt"], "files": [a["name"] for a in x["releaseAssets"]["nodes"]][:60]}
                    for x in releases[:3]
                ],
            )
            if "android" in c["want"]:
                entry["android"] = scout_android(releases, work, tool)
            if "desktop" in c["want"]:
                entry["linux"] = scout_desktop(releases, "linux", work)
                entry["windows"] = scout_desktop(releases, "windows", work)
            report["repos"][c["repo"]] = entry
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=0, sort_keys=True)
        print(f"{min(start + BATCH, len(cands))} of {len(cands)} scouted", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
