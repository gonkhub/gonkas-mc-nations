#!/usr/bin/env python3
"""Build the player-facing PrismLauncher zip for Gonka's MC Nations.

Players import the result with Prism's "Add Instance -> Import from zip". The zip
therefore has to look like one of Prism's own exports: instance.cfg, mmc-pack.json
and minecraft/ at the ROOT of the archive, not inside a wrapper folder.

This exists instead of using Prism's Export Instance dialog because the dialog is a
hand-driven checkbox tree - it cannot be re-run identically after a pack update, and
it exports instance.cfg verbatim, including this machine's hardcoded JavaPath. That
path does not exist on anyone else's computer and makes the instance refuse to launch.

Nothing is staged to disk first: the zip is written straight from the instance with a
filter. That is not only for speed - packwiz leaves its downloaded jars read-only, and
a copy-then-delete staging step is exactly what broke build-server-package.py.

Usage:
    python scripts/build-client-package.py            # build
    python scripts/build-client-package.py --list     # show what would go in, build nothing
"""

import hashlib
import pathlib
import re
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import packmeta  # noqa: E402

PACK = HERE.parent
OUT_DIR = PACK / "dist"

# The address and the version are READ, not written here.
#
# The address used to be a string literal in this file, and that one line is why this
# script could not be committed: check-no-secrets.sh refuses a literal IP in any tracked
# file, correctly, because this repo is public. So the release tooling sat untracked
# beside a repo whose whole purpose is reproducibility. It now comes from
# scripts/release-local.json, which is gitignored - the seam that lets the tooling be
# tracked while the address is not.
#
# The version comes from pack.json for the same class of reason: it used to be read from
# instance.cfg's ExportVersion, which nothing else in the pack knew about, so the zip
# could be named 0.0.1 while the tag said 0.1.0. One source, checked by release.py.

# Paths are relative to the pack root, POSIX separators. A directory here excludes
# everything under it. .packignore is the shared base; these are the additions that
# only matter for a PLAYER zip.
EXTRA_EXCLUDES = [
    # Singleplayer dev worlds - 191 MB that nobody joining a server will ever load.
    "minecraft/saves",
    # Per-player state carried over from the old 1.21.1 instance.
    "minecraft/XaeroWaypoints_BACKUP240807",
    # Distant Horizons' generated LOD cache for servers. Regenerates, and it is keyed
    # to worlds these players have never visited.
    "minecraft/Distant_Horizons_server_data",
    # Locally saved schematics - the builder's own, not pack content.
    "minecraft/schematics",
    # Holds a generated local web password. Each client should generate its own.
    "minecraft/config/resourceful-config-web.json",
    # packwiz bookkeeping. Players do not run packwiz.
    "minecraft/mods/.index",
    "minecraft/mods/hand-installed.sha1",
    # This script's own output, and the ignore file itself.
    "dist",
    ".packignore",
]

README = """GONKA'S MC NATIONS - HOW TO JOIN
================================

You need Prism Launcher. It is free: https://prismlauncher.org/download/

1. Open Prism Launcher and click "Add Instance" (top left).
2. Choose "Import from zip" in the left-hand list.
3. Click Browse, pick this zip file, and click OK.
4. Wait. It downloads Minecraft 1.21.1 and NeoForge the first time - a few minutes.
5. Select the instance and press Launch. First launch is slow: the mods have to build
   their caches. Two to five minutes is normal. It is not frozen.
6. Multiplayer -> {NAME} is already in the list. Join.

If it asks you to add a Microsoft account, that is Prism, not this pack. You need a
paid Minecraft Java Edition account; this does not work with Bedrock, the Windows 10
edition, or a cracked launcher.

SERVER ADDRESS (if you ever need to re-add it by hand)

    {ADDRESS}

MEMORY

The instance asks for up to 6 GB, which this pack genuinely needs. If you have 8 GB
of RAM in total, close other things before playing. If you have 16 GB or more you can
raise it: right-click the instance -> Edit -> Settings -> Memory.

If it crashes on startup with an "OutOfMemoryError" or a black screen, raising the
maximum is the first thing to try.

WHAT NOT TO CHANGE

Do not add or remove mods. The server checks that your mod list matches its own and
will refuse the connection if it does not. Resource packs and shaders are fine - they
are client-side only.

Your own settings, keybinds and waypoints are yours; the ones in here are only a
starting point.
"""


def read_packignore(path):
    if not path.exists():
        return []
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line.rstrip("/"))
    return out


def clean_instance_cfg(text, server_name, server_address):
    """Strip this machine out of instance.cfg.

    The important one is JavaPath: it points at a javaw.exe under this machine's own
    user profile, so with OverrideJavaLocation=true a player's instance tries to launch
    a JVM that is not there. AutomaticJava=true plus no override makes Prism find its
    own.

    (That sentence named the actual path until check-no-secrets.sh refused to let this
    file into the repo over it - correctly. A docstring is not a comment as far as the
    guard is concerned, and a packer's username is exactly what it is looking for.)
    """
    drop_keys = {
        # machine-specific Java install
        "JavaPath", "JavaSignature", "JavaVendor", "JavaVersion",
        "JavaArchitecture", "JavaRealArchitecture",
        # play-time counters and account binding from this copy
        "lastLaunchTime", "lastTimePlayed", "totalTimePlayed",
        "InstanceAccountId", "UseAccountForInstance",
    }
    replace = {
        "OverrideJavaLocation": "false",
        "AutomaticJava": "true",
        "IgnoreJavaCompatibility": "false",
        # 6 GB min AND max with -XX:+AlwaysPreTouch means the JVM claims all 6 GB
        # before the window even opens, which fails outright on an 8 GB machine.
        # Max stays at 6 GB because the pack genuinely needs it.
        "MinMemAlloc": "2048",
        "MaxMemAlloc": "6096",
        "notes": ("Gonka's MC Nations - the server is already in your multiplayer "
                  "list as " + server_name + " (" + server_address + ")."),
    }

    out = []
    seen = set()
    in_ui = False
    for line in text.splitlines():
        # The [UI] section is this launcher window's column widths. Useless and ugly.
        if line.startswith("["):
            in_ui = line.strip() == "[UI]"
            if not in_ui:
                out.append(line)
            continue
        if in_ui:
            continue
        m = re.match(r"^([A-Za-z0-9_\\]+)=(.*)$", line)
        if not m:
            out.append(line)
            continue
        key = m.group(1)
        if key in drop_keys:
            continue
        if key in replace:
            out.append(key + "=" + replace[key])
            seen.add(key)
            continue
        out.append(line)

    for key, val in replace.items():
        if key not in seen:
            out.append(key + "=" + val)

    return "\n".join(out).rstrip() + "\n"


def servers_dat(name, ip):
    """Hand-build servers.dat.

    It is uncompressed NBT - a root compound holding one list "servers" of compounds
    with string name, string ip, byte hidden. Written by hand so the build needs no
    NBT dependency; the layout was read back off the working file to confirm it.
    """
    def tag_string(key, value):
        k = key.encode("utf-8")
        v = value.encode("utf-8")
        return (b"\x08" + len(k).to_bytes(2, "big") + k
                + len(v).to_bytes(2, "big") + v)

    def tag_byte(key, value):
        k = key.encode("utf-8")
        return b"\x01" + len(k).to_bytes(2, "big") + k + bytes([value])

    entry = tag_string("name", name) + tag_string("ip", ip) + tag_byte("hidden", 0) + b"\x00"
    key = b"servers"
    return (b"\x0a\x00\x00"                                  # root compound, no name
            + b"\x09" + len(key).to_bytes(2, "big") + key    # list "servers"
            + b"\x0a" + (1).to_bytes(4, "big")               # of 1 compound
            + entry
            + b"\x00")                                       # end root


def main():
    list_only = "--list" in sys.argv

    meta = packmeta.pack()
    version = meta["version"]
    # --list is an inspection of the tree and needs no server, so the address is only
    # required for a real build. That keeps `--list` working on a fresh clone.
    local = packmeta.local(required=not list_only)
    server_name = local.get("server_name") or meta["name"]
    server_address = (packmeta.server_address() if not list_only
                      else local.get("server_address", ""))

    excludes = read_packignore(PACK / ".packignore") + EXTRA_EXCLUDES

    def excluded(rel):
        for pat in excludes:
            if rel == pat or rel.startswith(pat + "/"):
                return True
        return False

    files = []
    for path in sorted(PACK.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(PACK).as_posix()
        if excluded(rel):
            continue
        files.append((rel, path))

    cfg_path = PACK / "instance.cfg"
    cfg_text = cfg_path.read_text(encoding="utf-8")
    stamped = re.search(r"^ExportVersion=(.*)$", cfg_text, re.M)
    stamped = stamped.group(1).strip() if stamped else ""
    if stamped and stamped != version:
        print("note: instance.cfg ExportVersion=%s but pack.json says %s - using %s. "
              "`release.py bump` keeps them in step." % (stamped, version, version))

    total = sum(p.stat().st_size for _, p in files)
    jars = [r for r, _ in files if r.startswith("minecraft/mods/") and r.endswith(".jar")]
    print("files:  %d" % (len(files) + 1))   # +1 for HOW-TO-JOIN.txt, added below
    print("mods:   %d" % len(jars))
    print("raw:    %.1f MB" % (total / 1048576))

    if list_only:
        for rel, p in files:
            print("  %9d  %s" % (p.stat().st_size, rel))
        return 0

    OUT_DIR.mkdir(exist_ok=True)
    zip_path = OUT_DIR / ("%s-%s.zip" % (meta["slug"], version))

    # Jars and images are already deflated internally; recompressing them costs minutes
    # and saves nothing. The config tree is raw JSON and compresses by ~80%.
    stored_ext = {".jar", ".zip", ".png", ".ogg", ".gz"}

    readme = README.replace("{ADDRESS}", server_address).replace("{NAME}", server_name)

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for rel, path in files:
            if rel == "instance.cfg":
                z.writestr("instance.cfg",
                           clean_instance_cfg(cfg_text, server_name, server_address))
                continue
            if rel == "minecraft/servers.dat":
                continue  # written fresh below
            method = (zipfile.ZIP_STORED
                      if path.suffix.lower() in stored_ext else zipfile.ZIP_DEFLATED)
            z.write(path, rel, compress_type=method)
        z.writestr("minecraft/servers.dat", servers_dat(server_name, server_address))
        z.writestr("HOW-TO-JOIN.txt", readme)

    size = zip_path.stat().st_size
    sha1 = hashlib.sha1(zip_path.read_bytes()).hexdigest()
    print()
    print("wrote:  %s" % zip_path)
    print("size:   %.1f MB" % (size / 1048576))
    print("sha1:   %s" % sha1)
    (OUT_DIR / (zip_path.name + ".sha1")).write_text(
        "%s  %s\n" % (sha1, zip_path.name), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
