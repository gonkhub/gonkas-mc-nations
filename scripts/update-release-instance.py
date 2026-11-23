#!/usr/bin/env python3
"""Keep a local Prism instance on the latest published release of this pack.

Checks the repo's latest GitHub release. If it is newer than the version in the
instance's pack.json, it downloads the release zip, checks it against the release's
.sha1 asset, and applies it to the instance the way a fresh "Import from zip" would:

  * every file in the zip overwrites its copy in the instance;
  * jars in minecraft/mods/ that the new release does not ship are deleted, so a
    removed mod cannot keep loading;
  * worlds, waypoints, screenshots, logs and anything else not in the zip are left alone;
  * options.txt is kept if the instance already has one (key binds, video settings);
  * instance.cfg takes the release's pack settings but keeps this machine's instance
    name, Java choice, memory, account binding and playtime, and its notes= line ends
    with "Version X.Y.Z (updated YYYY-MM-DD)." so the installed release is visible.

Before changing anything it copies the files it is about to overwrite or delete into a
backup folder (one backup kept: the version being replaced). It does nothing while that
instance's game is running; the next scheduled run picks the update up.

Usage:
    python scripts/update-release-instance.py              # update if a release is newer
    python scripts/update-release-instance.py --check      # only report
    python scripts/update-release-instance.py --force      # re-apply the latest release
    python scripts/update-release-instance.py --instance NAME --prism PATH

Meant to run from Windows Task Scheduler; output also goes to update-release.log in the
state folder (%LOCALAPPDATA%\\GonkasMCNations-updater).
"""

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile

REPO = "gonkhub/gonkas-mc-nations"
DEFAULT_INSTANCE = "GonkasMCNations"
STATE = pathlib.Path(os.environ.get("LOCALAPPDATA", pathlib.Path.home())) \
    / "GonkasMCNations-updater"
LOG = STATE / "update-release.log"

SEED_ONLY = {"minecraft/options.txt"}
# instance.cfg keys that belong to this machine/player, not to the release.
LOCAL_KEYS = {
    "name", "iconKey", "JavaPath", "JavaSignature", "JavaVendor", "JavaVersion",
    "JavaArchitecture", "JavaRealArchitecture", "OverrideJavaLocation", "AutomaticJava",
    "lastLaunchTime", "lastTimePlayed", "totalTimePlayed", "InstanceAccountId",
    "UseAccountForInstance", "OverrideMemory", "MinMemAlloc", "MaxMemAlloc",
}


def log(msg):
    line = "%s  %s" % (datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg)
    try:
        print(line)
    except Exception:  # pythonw has no console
        pass
    STATE.mkdir(parents=True, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def http(url, accept="application/vnd.github+json"):
    req = urllib.request.Request(url, headers={
        "Accept": accept, "User-Agent": "pack-release-updater"})
    return urllib.request.urlopen(req, timeout=60)


def latest_release():
    with http("https://api.github.com/repos/%s/releases/latest" % REPO) as r:
        rel = json.load(r)
    tag = rel["tag_name"]
    version = tag.lstrip("v")
    assets = {a["name"]: a["browser_download_url"] for a in rel["assets"]}
    zip_name = next((n for n in assets if n.endswith(".zip")), None)
    if not zip_name or zip_name + ".sha1" not in assets:
        raise SystemExit("release %s has no zip + .sha1 pair: %s" % (tag, list(assets)))
    return version, zip_name, assets[zip_name], assets[zip_name + ".sha1"]


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def prism_instances(arg):
    prism = pathlib.Path(arg) if arg else pathlib.Path(os.environ["APPDATA"]) / "PrismLauncher"
    inst = "instances"
    cfg = prism / "prismlauncher.cfg"
    if cfg.is_file():
        m = re.search(r"^InstanceDir=(.+)$", cfg.read_text(encoding="utf-8"), re.M)
        if m:
            inst = m.group(1).strip()
    p = pathlib.Path(inst)
    return p if p.is_absolute() else prism / p


def instance_version(inst):
    pj = inst / "pack.json"
    if pj.is_file():
        try:
            return json.loads(pj.read_text(encoding="utf-8")).get("version", "0")
        except ValueError:
            pass
    m = re.search(r"^ExportVersion=(.+)$",
                  (inst / "instance.cfg").read_text(encoding="utf-8"), re.M)
    return m.group(1).strip() if m else "0"


def game_running(inst):
    """True if a java process has this instance's folder on its command line."""
    ps = ("Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | "
          "ForEach-Object { $_.CommandLine }")
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                             capture_output=True, text=True, timeout=60,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
    except Exception:
        return False
    needle = str(inst).replace("\\", "/").lower()
    return any(needle in line.replace("\\", "/").lower() for line in out.splitlines())


def download(url, dest):
    tmp = dest.with_suffix(dest.suffix + ".part")
    with http(url, accept="application/octet-stream") as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    tmp.replace(dest)


def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def writable(p):
    try:
        os.chmod(p, stat.S_IWRITE | stat.S_IREAD)
    except OSError:
        pass


def merge_cfg(release_text, existing_text):
    local, ui = {}, ""
    head, sep, tail = existing_text.partition("\n[UI]")
    if sep:
        ui = "[UI]" + tail
    for line in head.splitlines():
        k = line.split("=", 1)[0]
        if "=" in line and k in LOCAL_KEYS:
            local[k] = line
    out = []
    for line in release_text.splitlines():
        k = line.split("=", 1)[0]
        out.append(local.pop(k, line) if "=" in line else line)
    out += local.values()
    text = "\n".join(out).rstrip("\n") + "\n"
    return text + ui if ui else text


def with_version_note(cfg_text, version):
    """Append the release version to the end of instance.cfg's notes= line."""
    stamp = "Version %s (updated %s)." % (version, datetime.date.today().isoformat())
    m = re.search(r"^notes=(.*)$", cfg_text, re.M)
    if not m:
        return cfg_text.rstrip("\n") + "\nnotes=" + stamp + "\n"
    # Drop a stamp from an earlier run, wherever it sits, so stamps never pile up.
    rest = re.sub(r"\s*Version \S+ \(updated [\d-]+\)\.\s*", " ", m.group(1)).strip()
    note = (rest + " " if rest else "") + stamp
    return cfg_text[:m.start()] + "notes=" + note + cfg_text[m.end():]


def apply(zpath, inst, old_version, new_version):
    backup = STATE / "backup" / old_version
    # A forced re-apply of the same version keeps the backup from the real update.
    if backup.parent.exists() and old_version != new_version:
        shutil.rmtree(backup.parent, onerror=lambda f, p, e: (writable(p), f(p)))
    with zipfile.ZipFile(zpath) as z:
        members = [m for m in z.infolist() if not m.is_dir()]
        names = set()
        for m in members:
            n = m.filename.replace("\\", "/")
            if n.startswith("/") or ".." in n.split("/") or ":" in n:
                raise SystemExit("unsafe path in zip: %r" % n)
            names.add(n)
        if "instance.cfg" not in names or "mmc-pack.json" not in names:
            raise SystemExit("zip does not look like a Prism instance export")

        def back_up(rel):
            src = inst / rel
            if src.is_file():
                dst = backup / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dst)

        # mods the new release no longer ships
        new_jars = {n.split("/")[-1] for n in names
                    if n.startswith("minecraft/mods/") and n.endswith(".jar")}
        mods = inst / "minecraft/mods"
        removed = []
        if mods.is_dir():
            for p in mods.glob("*.jar"):
                if p.name not in new_jars:
                    back_up("minecraft/mods/" + p.name)
                    writable(p)
                    p.unlink()
                    removed.append(p.name)

        changed = 0
        for m in members:
            rel = m.filename.replace("\\", "/")
            dst = inst / rel
            if rel in SEED_ONLY and dst.exists():
                continue
            data = z.read(m)
            if rel == "instance.cfg":
                text = data.decode("utf-8")
                if dst.exists():
                    text = merge_cfg(text, dst.read_text(encoding="utf-8"))
                data = with_version_note(text, new_version).encode("utf-8")
            if dst.is_file() and dst.stat().st_size == len(data) \
                    and dst.read_bytes() == data:
                continue
            back_up(rel)
            dst.parent.mkdir(parents=True, exist_ok=True)
            if dst.exists():
                writable(dst)
            dst.write_bytes(data)
            changed += 1
    for n in removed:
        log("  removed mod %s" % n)
    log("  %d files written, %d mods removed, backup of replaced files in %s"
        % (changed, len(removed), backup))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--instance", default=DEFAULT_INSTANCE)
    ap.add_argument("--prism")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    inst = prism_instances(args.prism) / args.instance
    if not (inst / "instance.cfg").is_file():
        log("instance not found: %s" % inst)
        return 2
    have = instance_version(inst)
    try:
        version, zip_name, zip_url, sha_url = latest_release()
    except Exception as e:
        log("could not read latest release: %s" % e)
        return 3
    if vkey(version) <= vkey(have) and not args.force:
        log("%s is on %s, latest release is %s - nothing to do" % (args.instance, have, version))
        return 0
    log("%s is on %s, latest release is %s" % (args.instance, have, version))
    if args.check:
        return 0
    if game_running(inst):
        log("  the game is running from this instance - skipping, will retry next run")
        return 0

    STATE.mkdir(parents=True, exist_ok=True)
    zpath = STATE / zip_name
    with http(sha_url, accept="application/octet-stream") as r:
        want = r.read().decode("utf-8").split()[0].lower()
    if not (zpath.is_file() and sha1(zpath) == want):
        log("  downloading %s" % zip_name)
        download(zip_url, zpath)
    got = sha1(zpath)
    if got != want:
        zpath.unlink()
        log("  checksum mismatch (got %s, release says %s) - not applied" % (got, want))
        return 4
    apply(zpath, inst, have, version)
    for old in STATE.glob("*.zip"):
        if old != zpath:
            old.unlink()
    log("  %s updated to %s" % (args.instance, version))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as e:
        log("failed: %r" % e)
        sys.exit(1)
