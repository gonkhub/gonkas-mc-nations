#!/usr/bin/env python3
"""Mirror this repo into a Prism "dev" instance so changes can be play-tested.

The repo is the pack: configs, scripts, the pack-built jars and the packwiz index. It
is not a runnable instance on its own, because third-party jars are not tracked - each
one is a mods/.index/*.pw.toml entry with a CurseForge file id and a hash, and most have
no direct download URL. So this script:

  1. copies every file git knows about (tracked, plus new files not yet committed and
     not ignored) into the dev instance, so uncommitted work is what gets tested;
  2. fills in each indexed third-party jar from any other Prism instance on this machine
     that already has the exact same file (checked against the index hash), so nothing
     is downloaded;
  3. deletes jars and index entries in the dev instance's mods/ that the pack no longer
     has, so a removed mod cannot keep loading and hide a bug.

It never touches worlds, logs, screenshots or anything else the game creates at runtime.
options.txt is copied only when the dev instance has none, so key binds and video
settings changed while testing survive a re-sync.

Close the dev instance (the game, not Prism) before syncing. If Prism is open while the
instance is first created, it shows up after a moment or a restart of Prism.

Usage:
    python scripts/sync-dev-instance.py                # sync into GonkasMCNations-dev
    python scripts/sync-dev-instance.py --dry-run      # show what would change
    python scripts/sync-dev-instance.py --name Other   # a different instance folder
    python scripts/sync-dev-instance.py --prism PATH   # Prism data dir, if not default
"""

import argparse
import hashlib
import os
import pathlib
import re
import shutil
import stat
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_NAME = "GonkasMCNations-dev"
DISPLAY_NAME = "Gonka's MC Nations (dev)"

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import playerowned  # noqa: E402

# Settings are copied only if the dev instance has none; player data is never touched.
# Seeded files come from seed/, not from minecraft/, which git does not track (#26).
OWNED = playerowned.load()


def prism_dir(arg):
    if arg:
        return pathlib.Path(arg)
    appdata = os.environ.get("APPDATA")
    if not appdata:
        sys.exit("APPDATA is not set; pass --prism with Prism's data folder")
    return pathlib.Path(appdata) / "PrismLauncher"


def instances_dir(prism):
    cfg = prism / "prismlauncher.cfg"
    inst = "instances"
    if cfg.is_file():
        m = re.search(r"^InstanceDir=(.+)$", cfg.read_text(encoding="utf-8"), re.M)
        if m:
            inst = m.group(1).strip()
    p = pathlib.Path(inst)
    return p if p.is_absolute() else prism / p


def pack_files():
    """Repo-relative paths of everything git tracks or would track, minus deletions."""
    out = subprocess.run(
        ["git", "-c", "core.quotepath=off", "ls-files", "-z",
         "--cached", "--others", "--exclude-standard"],
        cwd=ROOT, check=True, capture_output=True).stdout
    files = sorted({p for p in out.decode("utf-8").split("\0") if p})
    return [p for p in files if (ROOT / p).is_file()]


def wanted_in_instance(rel):
    if rel in ("instance.cfg", "mmc-pack.json", "pack.json"):
        return True
    return rel.startswith("minecraft/")


def index_entries():
    """{jar filename: (hash-format, hash)} for every third-party mod in the index."""
    entries = {}
    for toml in sorted((ROOT / "minecraft/mods/.index").glob("*.pw.toml")):
        s = toml.read_text(encoding="utf-8")
        fn = re.search(r"^filename = ['\"](.+?)['\"]$", s, re.M)
        h = re.search(r"^hash = ['\"](\w+)['\"]$", s, re.M)
        fmt = re.search(r"^hash-format = ['\"](\w+)['\"]$", s, re.M)
        if not (fn and h and fmt):
            sys.exit("cannot read filename/hash from %s" % toml)
        entries[fn.group(1)] = (fmt.group(1), h.group(1).lower())
    return entries


def file_hash(path, fmt):
    h = hashlib.new(fmt)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def same_bytes(a, b):
    if not b.is_file() or a.stat().st_size != b.stat().st_size:
        return False
    return file_hash(a, "sha1") == file_hash(b, "sha1")


def make_writable(path):
    # packwiz and Prism leave downloaded jars read-only; overwriting or deleting needs this.
    try:
        os.chmod(path, stat.S_IWRITE | stat.S_IREAD)
    except OSError:
        pass


def copy(src, dst, dry):
    if dry:
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        make_writable(dst)
    shutil.copy2(src, dst)


# Keys Prism writes for this machine (see scripts/clean-instance-cfg.sh); kept from the
# dev instance's existing cfg so a re-sync does not reset its Java choice or playtime.
LOCAL_KEYS = re.compile(r"^(lastLaunchTime|lastTimePlayed|totalTimePlayed|JavaPath|"
                        r"JavaSignature|JavaVendor|JavaVersion|JavaArchitecture|"
                        r"JavaRealArchitecture|OverrideJavaLocation)=")


def dev_instance_cfg(text, existing):
    text = re.sub(r"^name=.*$", "name=" + DISPLAY_NAME, text, count=1, flags=re.M)
    if not existing:
        return text
    local = {}
    ui = ""
    head, sep, tail = existing.partition("\n[UI]")
    if sep:
        ui = sep + tail
    for line in head.splitlines():
        if LOCAL_KEYS.match(line):
            local[line.split("=", 1)[0]] = line
    lines = []
    for line in text.splitlines():
        key = line.split("=", 1)[0]
        lines.append(local.pop(key, line) if "=" in line else line)
    lines += local.values()
    return "\n".join(lines).rstrip("\n") + "\n" + ui.lstrip("\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--name", default=DEFAULT_NAME)
    ap.add_argument("--prism")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    dry = args.dry_run

    prism = prism_dir(args.prism)
    instances = instances_dir(prism)
    if not instances.is_dir():
        sys.exit("Prism instances folder not found: %s" % instances)
    dest = instances / args.name
    creating = not dest.exists()
    print("%s %s" % ("Creating" if creating else "Updating", dest))

    copied = skipped = 0
    for repo_rel in pack_files():
        rel = repo_rel[len("seed/"):] if repo_rel.startswith("seed/") else repo_rel
        if not wanted_in_instance(rel) or OWNED.keep(rel):
            continue
        src, dst = ROOT / repo_rel, dest / rel
        if OWNED.seed(rel) and dst.exists():
            continue
        if rel == "instance.cfg":
            # Pack settings come from the repo, the display name says "dev", and
            # Prism's own counters/Java choice are kept from the existing dev cfg.
            old = dst.read_text(encoding="utf-8") if dst.exists() else ""
            new = dev_instance_cfg(src.read_text(encoding="utf-8"), old)
            if dst.exists() and dst.read_text(encoding="utf-8") == new:
                skipped += 1
                continue
            if not dry:
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_text(new, encoding="utf-8", newline="")
            copied += 1
            continue
        if same_bytes(src, dst):
            skipped += 1
            continue
        copy(src, dst, dry)
        copied += 1
        if not creating:
            print("  update  " + rel)
    print("pack files: %d copied, %d already current" % (copied, skipped))

    # Third-party jars from the index, sourced from other local instances.
    entries = index_entries()
    mods = dest / "minecraft/mods"
    others = [p / "minecraft/mods" for p in sorted(instances.iterdir())
              if p.is_dir() and p != dest and (p / "minecraft/mods").is_dir()]
    filled = present = 0
    missing = []
    for fn, (fmt, want) in sorted(entries.items(), key=lambda e: e[0].lower()):
        dst = mods / fn
        if dst.is_file() and file_hash(dst, fmt) == want:
            present += 1
            continue
        src = next((o / fn for o in others
                    if (o / fn).is_file() and file_hash(o / fn, fmt) == want), None)
        if src is None:
            missing.append(fn)
            continue
        copy(src, dst, dry)
        filled += 1
        if not creating:
            print("  jar     %s  (from %s)" % (fn, src.parent.parent.parent.name))
    print("indexed jars: %d copied from other instances, %d already present"
          % (filled, present))

    # Prune what the pack no longer ships.
    keep_jars = set(entries) | {pathlib.PurePosixPath(r).name for r in pack_files()
                                if r.startswith("minecraft/mods/") and r.endswith(".jar")}
    keep_idx = {pathlib.PurePosixPath(r).name for r in pack_files()
                if r.startswith("minecraft/mods/.index/")}
    removed = []
    if mods.is_dir():
        for p in mods.glob("*.jar"):
            if p.name not in keep_jars:
                removed.append(p)
        idx = mods / ".index"
        if idx.is_dir():
            removed += [p for p in idx.glob("*.pw.toml") if p.name not in keep_idx]
    for p in removed:
        print("  remove  " + p.relative_to(dest).as_posix())
        if not dry:
            make_writable(p)
            p.unlink()

    if missing:
        print("\nMISSING - no local instance has these exact jars. Install them into the")
        print("dev instance through Prism (Edit -> Mods -> Download mods), or drop the")
        print("matching file into %s, then re-run:" % mods)
        for fn in missing:
            print("  - " + fn)
    if dry:
        print("\n(dry run: nothing was written)")
    elif creating:
        print("\nDone. In Prism the instance is listed as \"%s\"." % DISPLAY_NAME)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
