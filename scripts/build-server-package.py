#!/usr/bin/env python3
"""Build the dedicated-server upload from this pack.

    python scripts/build-server-package.py                     # full package
    python scripts/build-server-package.py --delta             # only what changed since last time
    python scripts/build-server-package.py --check <dir>       # verify a package against its manifest

WHY THIS IS A SCRIPT AND NOT A LIST OF STEPS
--------------------------------------------
A modded server is a derived artifact. The pack in this repo is the source of truth; what the
server needs is the pack minus the client-only mods, minus per-world state, minus anything with a
secret in it. Deriving that by hand is how a server ends up with a client renderer in `mods/` or
a stale `config/` nobody can account for -- and both of those failures look like "the modpack is
broken" rather than "somebody copied the wrong folder".

So: one command, every time, with a checksum manifest that makes the next upload a delta instead
of half a gigabyte.

THE THREE RULES IT ENCODES
--------------------------
1. SHIP EVERYTHING EXCEPT PURELY CLIENT-SIDE RENDERING AND UI. A spare jar costs disk space; a
   missing one costs world generation or a registry desync that only appears when somebody
   connects. The exclusion list below is short and every entry carries its reason.
2. NEVER SHIP PER-WORLD STATE OR SECRETS. Both are enumerated in SKIP below, with why.
3. THE MANIFEST IS PART OF THE PRODUCT. Without it there is no delta, no verification, and no
   way to tell which build is actually on the server.

NOTE ON `mods/.index/*.pw.toml`: its `side` field is NOT a usable signal. It calls Alex's Caves
server-only and AsyncLogger client-only, both wrong. The list below came from each jar's own
`neoforge.mods.toml` plus what the mod does.
"""

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import stat
import sys

# --------------------------------------------------------------------------------------------
# Client-only mods: matched as a substring of the jar filename, so a version bump does not
# silently start shipping a client renderer to the server.
# --------------------------------------------------------------------------------------------
CLIENT_ONLY = {
    "sodium-neoforge": "client renderer",
    "iris-neoforge": "client shaders",
    "reeses-sodium-options": "Sodium's options screen",
    "sodiumdynamiclights": "Sodium dynamic lighting",
    "create-dyn-light": "Sodium dynamic lights for Create",
    "ImmediatelyFast": "client render batching",
    "AsyncParticles": "client particle threading",
    "DistantHorizons": "declares clientSideOnly = true; would refuse to load",
    "dhturbo": "Distant Horizons addon, pointless without it",
    "entityculling": "client-side occlusion culling",
    # Both declare side="CLIENT" on their neoforge AND minecraft dependencies, which
    # is the mod saying it is client-only rather than a dependency saying it is only
    # needed there. icys-better-horses and particular look similar at a glance and are
    # NOT client-only: their core deps are side="BOTH" and only a dependency is CLIENT.
    "cirrus": "client cloud rendering",
    "weatherglass": "client weather HUD",
}

# --------------------------------------------------------------------------------------------
# Pack-owned directories the server needs, and what to leave out of each.
#
# "Pack-owned" is the important idea: every one of these is wholly derived from this repo, so the
# server's copy may be REPLACED rather than merged. Anything the server owns -- server.properties,
# ops.json, the world -- is simply not in this list and is never touched.
# --------------------------------------------------------------------------------------------
TREES = {
    "config": {
        # jei/world embeds the live server address and is per-world state the server rebuilds.
        #
        # bluemap/maps holds one config per world of the CLIENT instance, each pointing at
        # `saves/<name>` - a path that does not exist on a server. Shipping them makes BlueMap
        # log an error per map and render nothing. Left out, BlueMap writes correct ones for
        # whatever worlds the server actually has, on first run.
        "skip_dirs": {"jei/world", "bluemap/maps"},
        "skip_files": set(),
        "why": "every mod's config, server and client alike -- the server ignores the client "
               "ones, and shipping all of them keeps one source of truth for the tuning",
    },
    "defaultconfigs": {
        "skip_dirs": set(), "skip_files": set(),
        "why": "per-world config defaults, applied when a new world is created",
    },
    "kubejs": {
        # Client scripts are no use to a server, and web_server.json holds a generated auth token.
        "skip_dirs": {"client_scripts"},
        "skip_files": {"config/web_server.json"},
        "why": "server and startup scripts: the pack's own recipe and loot changes",
    },
    "blueprints": {
        "skip_dirs": set(), "skip_files": set(),
        "why": "Structurize blueprint packs the Builder needs in order to build anything",
    },
    "moonlight-global-datapacks": {
        "skip_dirs": set(), "skip_files": set(),
        "why": "global datapacks applied to every world",
    },
    "local": {
        "skip_dirs": set(), "skip_files": set(),
        "why": "KubeJS persistent data",
    },
}

# Directories on the server that belong to THIS PACK and must therefore be deleted before an
# upload rather than merged into. Generated into DELETE-FIRST.txt so it travels with the package.
#
# THE REASON THIS MATTERS MORE THAN IT LOOKS: switching packs leaves orphans. The previous server
# ran 107 mods, 46 of which are not in this pack at all -- including structure mods, which change
# world generation -- and several shared mods at DIFFERENT versions, which would leave two jars
# claiming one mod id and crash on load. Merging cannot fix that; only replacing can.
PACK_OWNED = ["mods", "config", "defaultconfigs", "kubejs", "blueprints",
              "moonlight-global-datapacks", "local"]

# Caches and build noise a previous install may have left. Harmless to keep, but they are
# regenerated and a stale one has caused confusing mixin errors before.
STALE_CACHES = [".mixin.out", "dynamic-data-pack-cache", "dynamic-resource-pack-cache",
                ".probe", ".sable", "coremods"]

# What the server owns. Listed so the instructions can be explicit about NOT touching them.
SERVER_OWNED = ["server.properties", "ops.json", "whitelist.json", "banned-players.json",
                "banned-ips.json", "eula.txt", "usercache.json", "usernamecache.json",
                "libraries/", "EVERY world folder - see WORLD below"]

# The old server's world. Named explicitly, because it is the one thing on that machine with no
# local copy, and because a list of directories to delete is exactly the document somebody
# skim-reads before cleaning up a server root.
#
# NOTHING IN THIS MIGRATION TOUCHES IT. The new world is created by pointing `level-name` at a
# new folder, so the old one is never read, written, renamed or moved.
WORLD = "lookscanbethisevening"
NEW_WORLD = "nations"

# The loader the server has to be on. Verified against NeoForged's own published checksum, so a
# corrupted or substituted download is caught before it is ever run.
LOADER_SHA1 = "25ce1d35d619ccfd3080076fa5758c18e11a9778"
LOADER_URL = ("https://maven.neoforged.net/releases/net/neoforged/neoforge/"
              "%s/neoforge-%s-installer.jar")

# server.properties lines the pack needs. Shipped as a list of changes rather than a whole file,
# because the server's own copy holds its identity - port, motd, seed, whitelist - and replacing
# it to set six keys would be the kind of "helpful" overwrite that loses someone's settings.
PROPERTIES = [
    ("level-name", NEW_WORLD,
     "the NEW world. Leaving this at the old value would load `%s` instead, which is the one "
     "thing in this migration that must not be touched." % WORLD),
    ("max-tick-time", "180000",
     "3 minutes. THE MOST IMPORTANT ONE: Minecolonies pathfinding and Create contraption ticks "
     "exceed the 60-second default on a busy colony and the watchdog kills the server "
     "mid-session."),
    ("allow-flight", "true",
     "Create jetpacks and Create Aeronautics airships both trip the vanilla flight check."),
    ("spawn-protection", "0",
     "otherwise nobody can build near spawn, which is where the first colonies go."),
    ("view-distance", "8", "deliberate: this pack keeps work off unloaded chunks, so a smaller "
                           "distance costs it much less than it would a vanilla server."),
    ("simulation-distance", "6", "same reasoning."),
    ("level-type", "minecraft:normal", "the pack's worldgen mods expect a normal overworld."),
]


def sha1(path):
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def loader_version(pack):
    """NeoForge version, read from Prism's own manifest so it cannot go stale."""
    manifest = pack / "mmc-pack.json"
    if not manifest.is_file():
        return "unknown"
    data = json.loads(manifest.read_text(encoding="utf-8"))
    for component in data.get("components", []):
        if "neoforge" in str(component.get("uid", "")).lower():
            return component.get("version", "unknown")
    return "unknown"


def read_manifest(path):
    """A previous UPLOAD-SHA1.txt as {relative path: sha1}."""
    out = {}
    if not path or not pathlib.Path(path).is_file():
        return out
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        if "  " in line:
            digest, rel = line.split("  ", 1)
            out[rel.strip()] = digest.strip()
    return out


# Values that are right for the client this pack is, and wrong for a server. Applied to the
# COPY, never to the pack: the pack is a client instance and these are correct there.
SERVER_TRANSFORMS = {
    "config/bluemap/core.conf": [
        # A server has no game resources of its own, so BlueMap downloads the vanilla client jar
        # for block models - and refuses to start until this is accepted. A client never needs
        # it, which is why the pack's copy says false.
        ("accept-download: false", "accept-download: true"),
    ],
}


def effective_bytes(rel, source, notes):
    """What this file should BE on the server: its source, or its source transformed.

    Everything downstream - the hash, the delta comparison and the copy - uses this, so the
    manifest describes the upload rather than the pack. Getting that wrong made a transformed
    file read as CHANGED on a freshly built package and re-copy on every delta forever.
    """
    raw = source.read_bytes()
    edits = SERVER_TRANSFORMS.get(rel)
    if not edits:
        return raw
    text = raw.decode("utf-8")
    for old, new in edits:
        if old not in text:
            # Not a failure: the pack may already hold the server value, or the mod may have
            # changed its config. Said out loud rather than passed over, because a transform
            # that silently stops applying is how a setting quietly reverts.
            notes.append("%s: %r not found, left as is" % (rel, old))
            continue
        text = text.replace(old, new)
        notes.append("%s: %s -> %s" % (rel, old, new))
    return text.encode("utf-8")


def gather(pack):
    """Everything that should end up on the server, as {relative path: source path}."""
    files = {}
    excluded = []

    mods = pack / "minecraft" / "mods"
    for jar in sorted(mods.glob("*.jar")):
        reason = next((why for key, why in CLIENT_ONLY.items() if key in jar.name), None)
        if reason:
            excluded.append((jar.name, reason))
            continue
        files["mods/" + jar.name] = jar
    # The checksum manifest for the hand-installed jars travels with them: it is the only guard
    # against a wrong `thee` build reaching the server, which has bitten this pack before.
    hand = mods / "hand-installed.sha1"
    if hand.is_file():
        files["mods/hand-installed.sha1"] = hand

    for name, rules in TREES.items():
        source = pack / "minecraft" / name
        if not source.is_dir():
            continue
        for path in sorted(source.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(source).as_posix()
            if any(rel == skip or rel.startswith(skip + "/") for skip in rules["skip_dirs"]):
                continue
            if rel in rules["skip_files"]:
                continue
            files[name + "/" + rel] = path
    return files, excluded


def write_docs(out, pack, files, excluded, delta_of=None, removed=()):
    """README-SERVER.md (prose, from the repo) plus PACKAGE.md (generated facts)."""
    template = pack / "scripts" / "server-readme.md"
    if template.is_file():
        shutil.copy2(template, out / "README-SERVER.md")

    size = sum(p.stat().st_size for p in out.rglob("*") if p.is_file())
    lines = [
        "# Package contents",
        "",
        "Generated by `scripts/build-server-package.py`. Do not edit; re-run the script.",
        "",
        "| | |",
        "|---|---|",
        "| Minecraft | 1.21.1 |",
        "| NeoForge | **%s** -- the server's loader must match exactly |" % loader_version(pack),
        "| Mods | %d |" % len([k for k in files if k.startswith("mods/") and k.endswith(".jar")]),
        "| Files | %d |" % len(files),
        "| Size | %.0f MB |" % (size / 1024 / 1024),
    ]
    if delta_of:
        lines += ["| Kind | **DELTA** against `%s` |" % delta_of]
    lines += ["", "## Client-only mods deliberately left out", "",
              "Clients keep all of these. Each either declares itself client-only or ignores the",
              "server's mod list, so a client that has them connects to a server that does not.",
              "", "| Mod | Why |", "|---|---|"]
    for name, why in excluded:
        lines.append("| `%s` | %s |" % (name, why))

    if removed:
        lines += ["", "## Delete these on the server", "",
                  "Present in the previous upload and gone from this one. Left behind, a stale",
                  "jar duplicates a mod id and a stale config is read as if it were current.",
                  ""]
        lines += ["- `%s`" % rel for rel in sorted(removed)]

    lines += ["", "## Directories this package replaces", "",
              "Each is wholly derived from the pack repo, so the server's copy may be replaced",
              "rather than merged into:", ""]
    for name in PACK_OWNED:
        why = TREES.get(name, {}).get("why", "the pack's mod jars")
        lines.append("- `%s/` -- %s" % (name, why))
    lines += ["", "## Never touched", "",
              "The server owns these and this package does not contain them:", ""]
    lines += ["- `%s`" % name for name in SERVER_OWNED]
    (out / "PACKAGE.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def write_go_live(out, pack):
    """SERVER-LOADER.md, server.properties.CHANGES.txt and GO-LIVE.txt."""
    version = loader_version(pack)
    url = LOADER_URL % (version, version)

    # The loader. Copied in if a local vendor copy exists, so the upload is self-contained;
    # otherwise the URL and checksum are enough to fetch it on the spot.
    vendor = sorted((pack / "scripts" / ".vendor").glob("neoforge-*-installer.jar"))
    installer = None
    if vendor:
        installer = out / vendor[-1].name
        shutil.copy2(vendor[-1], installer)

    (out / "SERVER-LOADER.md").write_text("""# NeoForge %s

The server must be on exactly this version. %s

```
%s
sha1  %s
```

## If your host has a loader picker in its panel

Choose **NeoForge %s** and let the panel install it. Nothing else to do.

## If you have shell access

Run this in the server root. It downloads NeoForge's libraries and writes the run scripts; it
does not touch your world, `server.properties` or `mods/`.

```
java -jar %s --installServer
```

## If you have neither

Some hosts accept the installer jar uploaded to the root and run it for you; others want a
`run.sh`/`run.bat` from a machine where you ran `--installServer` locally. Ask the host which,
because an installer that half-ran leaves a `libraries/` folder that looks complete and is not.

## Verify before running

```powershell
certutil -hashfile %s SHA1
```

must print `%s`. This is NeoForged's own published checksum, not one computed here.
""" % (version,
       "It is currently on 21.1.243." if version != "21.1.243" else "",
       url, LOADER_SHA1, version,
       installer.name if installer else ("neoforge-%s-installer.jar" % version),
       installer.name if installer else ("neoforge-%s-installer.jar" % version),
       LOADER_SHA1),
        encoding="utf-8", newline="\n")

    lines = ["# server.properties - CHANGE THESE KEYS, do not replace the file.",
             "#",
             "# Your server's copy holds its own identity: port, motd, seed, whitelist, rcon.",
             "# Replacing it to set seven keys is how someone loses all of that. Open it, find",
             "# each key below, set it to this value. Keys not listed here are none of this",
             "# pack's business.",
             ""]
    for key, value, why in PROPERTIES:
        lines += ["# %s" % why, "%s=%s" % (key, value), ""]
    (out / "server.properties.CHANGES.txt").write_text("\n".join(lines),
                                                       encoding="utf-8", newline="\n")

    checklist = """GO LIVE - Gonka's MC Nations, new world
=======================================

The server is already offline. Work top to bottom; nothing here is reversible except by
restoring a backup, so do not skip the read-first lines.

  [ ] 0. READ THIS LINE. The folder `%s` on the server is the old world and there
         is NO backup of it anywhere else. Nothing below touches it. If any step seems to
         want you to move, rename or delete it, you have misread the step.

  [ ] 1. In FileZilla, DELETE these directories in the server root. Do not merge into them -
         this pack's mod set differs from the old one, and a leftover jar at a different
         version leaves two jars claiming one mod id, which is a crash before the server
         finishes loading:

             mods  config  defaultconfigs  kubejs  blueprints
             moonlight-global-datapacks  local

         And these caches, which are regenerated:

             .mixin.out  dynamic-data-pack-cache  dynamic-resource-pack-cache
             .probe  .sable  coremods

         DO NOT DELETE: %s, server.properties, ops.json, whitelist.json,
         banned-players.json, banned-ips.json, eula.txt, libraries, or the host's start script.

  [ ] 2. Install NeoForge %s. See SERVER-LOADER.md - panel picker, or
         `java -jar %s --installServer` in the server root.

  [ ] 3. Upload the CONTENTS of this folder into the server root. Everything except the four
         instruction files and the installer is pack content:

             mods/  config/  defaultconfigs/  kubejs/  blueprints/
             moonlight-global-datapacks/  local/

         Skip: GO-LIVE.txt, PACKAGE.md, README-SERVER.md, DELETE-FIRST.txt,
               SERVER-LOADER.md, server.properties.CHANGES.txt, UPLOAD-SHA1.txt,
               and the installer jar if you installed via the panel.

  [ ] 4. Edit server.properties. Seven keys, listed with reasons in
         server.properties.CHANGES.txt. The two that will bite if you miss them:

             level-name=%s          <- or it loads the OLD world
             max-tick-time=180000       <- or the watchdog kills the server mid-session

  [ ] 5. Set the memory allocation to 6-8 GB if the host allows it. See README-SERVER.md s5.

  [ ] 6. Start the server. Watch the console for:
             - "Done (" ................ it booted
             - "[thee]" ................ the pack's own mod loaded
             - no "Found unused register callbacks" and no duplicate-mod-id error

  [ ] 7. Let it finish generating spawn BEFORE anyone joins.

  [ ] 8. /op <your name>

  [ ] 9. Verify, in this order, BEFORE players join - the test suite creates and deletes
         colonies:

             /sculk list        expect "No sculk nodes recorded" on a fresh world
             /theetest all      expect "13 passed, 0 FAILED, 1 skipped" or better
                                (claim-radius may skip - known, harmless)

  [ ] 10. Check BlueMap answers on port 8100, and that the host exposes it.

  [ ] 11. When convenient, download %s to
          F:\\gonkas-backups\\server-worlds\\ in BINARY mode, then verify it:

              python scripts/verify-world-backup.py "F:\\gonkas-backups\\server-worlds\\%s"

          Not a blocker - nothing above touches that world - but it is the only copy.

KNOWN, EXPECTED, NOT A BUG
--------------------------
A brand new world has NO MONEY IN IT. Both spur faucets were removed deliberately; the only
thing that mints spurs is beating back the sculk. A new settlement starts at 0 and nothing
accrues. If you want a float before the first campaign:

    /settlement fund <name> 50000

IF IT WILL NOT START
--------------------
Duplicate mod id, or an unexplained mixin error  -> a leftover jar. You merged instead of
                                                    deleting at step 1.
Watchdog kills it after 60 seconds               -> max-tick-time, step 4.
/sculk and /theetest do not exist                -> thee did not load. Check the jar's sha1
                                                    against mods/hand-installed.sha1.
Unfamiliar structures in the new world           -> an orphan structure mod from the old pack
                                                    generated into it. No clean fix: delete
                                                    the NEW world (`%s`) and redo step 1.
""" % (WORLD, WORLD, version,
       installer.name if installer else ("neoforge-%s-installer.jar" % version),
       NEW_WORLD, WORLD, WORLD, NEW_WORLD)
    (out / "GO-LIVE.txt").write_text(checklist, encoding="utf-8", newline="\n")


def build(pack, out, previous=None):
    files, excluded = gather(pack)
    old = read_manifest(previous)

    if out.exists():
        # `shutil.rmtree` CANNOT delete a read-only file on Windows: os.unlink raises
        # PermissionError. That matters here because packwiz leaves its downloaded jars
        # read-only and `copy2` preserves the bit, so every rebuild over an existing package
        # died partway through - after wiping config/, kubejs/ and blueprints/ and before
        # reaching mods/. A half-deleted package that still has its old manifest is worse than
        # either a clean one or no one at all, so clear the bit and retry rather than fail.
        def unlock(func, path, _exc):
            os.chmod(path, stat.S_IWRITE)
            func(path)

        shutil.rmtree(out, onexc=unlock)
    out.mkdir(parents=True)

    # ONE PASS: decide each file's effective content, hash THAT, and write THAT. A file whose
    # content is identical is skipped even if its timestamp moved, which is most of the package
    # most of the time.
    copied = 0
    notes = []
    digests = {}
    for rel, source in sorted(files.items()):
        content = effective_bytes(rel, source, notes)
        digests[rel] = hashlib.sha1(content).hexdigest()
        if old and old.get(rel) == digests[rel]:
            continue
        target = out / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        copied += 1
    for note in notes:
        print("  transform  %s" % note)

    removed = sorted(set(old) - set(files) - {"UPLOAD-SHA1.txt", "PACKAGE.md",
                                              "README-SERVER.md", "DELETE-FIRST.txt"})
    if removed:
        (out / "DELETE-ON-SERVER.txt").write_text(
            "# Files present in the previous upload and gone from this one.\n"
            "# Delete these on the server, or a stale jar will duplicate a mod id.\n"
            + "\n".join(removed) + "\n", encoding="utf-8", newline="\n")

    # The first-upload cleanup list. Always written, because it is also the answer to "what do I
    # do about the last server's files".
    (out / "DELETE-FIRST.txt").write_text(
        "# FIRST UPLOAD ONLY, or any time you switch pack versions wholesale.\n"
        "#\n"
        "# ############################################################################\n"
        "# DO NOT DELETE, RENAME OR MOVE THE WORLD FOLDER `%s`.\n"
        "# It is the old server's world and there is NO local backup of it.\n"
        "#\n"
        "# You do not need to touch it. Set `level-name=%s` in server.properties and\n"
        "# the new world is created beside it; the old one is never even read.\n"
        "# ############################################################################\n"
        "#\n" % (WORLD, NEW_WORLD) +
        "# Delete these directories on the server BEFORE uploading. Do not merge into them:\n"
        "# this pack's mod set differs from any previous one, and a leftover jar at a different\n"
        "# version leaves two jars claiming one mod id, which is a hard crash on load.\n"
        "#\n"
        "# A stale config is quieter and worse: it is read as if it were current.\n"
        + "\n".join(PACK_OWNED)
        + "\n\n# Regenerated caches. Safe to delete, and a stale one has caused confusing\n"
          "# mixin errors before.\n"
        + "\n".join(STALE_CACHES)
        + "\n\n# NEVER delete these - the server owns them:\n"
        + "\n".join("# " + name for name in SERVER_OWNED) + "\n",
        encoding="utf-8", newline="\n")

    write_go_live(out, pack)
    write_docs(out, pack, files, excluded,
               delta_of=pathlib.Path(previous).name if previous and old else None,
               removed=removed)

    # The manifest describes the WHOLE package, not just this delta, so the next delta compares
    # against the full state of the server rather than against the last upload's diff. It holds
    # the EFFECTIVE hashes, so `--check` on a fresh package passes and a transformed file is not
    # re-copied on every run.
    manifest = "\n".join("%s  %s" % (digests[rel], rel) for rel in sorted(files))
    (out / "UPLOAD-SHA1.txt").write_text(manifest + "\n", encoding="utf-8", newline="\n")

    total = sum(p.stat().st_size for p in out.rglob("*") if p.is_file())
    print("package: %s" % out)
    print("  NeoForge     %s" % loader_version(pack))
    print("  mods         %d shipped, %d client-only excluded"
          % (len([k for k in files if k.startswith("mods/") and k.endswith(".jar")]),
             len(excluded)))
    print("  files        %d tracked, %d written%s"
          % (len(files), copied, " (delta)" if old else ""))
    print("  size         %.1f MB" % (total / 1024 / 1024))
    if removed:
        print("  DELETE       %d stale file(s) -- see DELETE-ON-SERVER.txt" % len(removed))
    if old and copied == 0:
        print("  nothing changed since the previous manifest")
    return 0


def is_delta(out):
    """Whether this package was built as a delta.

    Read off PACKAGE.md, which write_docs marks for the human reading it. The marker is
    there either way, so this needs no new file in the package and no flag threaded
    through the caller.
    """
    doc = pathlib.Path(out) / "PACKAGE.md"
    if not doc.is_file():
        return False
    return "**DELTA**" in doc.read_text(encoding="utf-8")


def check(out):
    """Verify a built package against its own manifest.

    A DELTA IS CHECKED ON WHAT IT CONTAINS, not on what the manifest lists.

    The manifest deliberately describes the WHOLE package rather than this delta (see
    build_package) so the next delta has something to compare against. Which means a
    delta of three files is "missing" the other three hundred BY DESIGN, and checking it
    against the full manifest could never pass - this check was unpassable for every
    delta release, and the only way to ship one was to skip verification, which is
    exactly backwards.

    So: every file present must match its digest, and absences are counted and reported
    rather than failed. A corrupt or truncated file is still caught, which is what this
    check is actually for. A FULL package is still held to the whole manifest, because
    there a missing file is a real hole.
    """
    out = pathlib.Path(out)
    manifest = out / "UPLOAD-SHA1.txt"
    if not manifest.is_file():
        print("no UPLOAD-SHA1.txt in %s" % out, file=sys.stderr)
        return 1
    delta = is_delta(out)
    bad = 0
    missing = 0
    present = 0
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if "  " not in line:
            continue
        digest, rel = line.split("  ", 1)
        path = out / rel.strip()
        if not path.is_file():
            missing += 1
            if not delta:
                print("MISSING  %s" % rel.strip())
        elif sha1(path) != digest.strip():
            bad += 1
            print("CHANGED  %s" % rel.strip())
        else:
            present += 1
    if bad or (missing and not delta):
        print("%d changed, %d missing" % (bad, missing), file=sys.stderr)
        return 1
    if delta:
        print("ok: %d file(s) in this delta match UPLOAD-SHA1.txt"
              " (%d unchanged file(s) already on the server)" % (present, missing))
    else:
        print("ok: every file matches UPLOAD-SHA1.txt")
    return 0


def main():
    here = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack", default=str(here),
                        help="the pack repo root (default: this script's parent)")
    parser.add_argument("--out", default=None,
                        help="output directory (default: ../gonkas-server-upload)")
    parser.add_argument("--delta", nargs="?", const="auto", default=None,
                        help="only write files that changed since this UPLOAD-SHA1.txt "
                             "(default: the one in --out)")
    parser.add_argument("--check", default=None, help="verify a package and exit")
    args = parser.parse_args()

    if args.check:
        return check(args.check)

    pack = pathlib.Path(args.pack).resolve()
    out = pathlib.Path(args.out).resolve() if args.out \
        else pack.parent / "gonkas-server-upload"

    previous = None
    if args.delta:
        previous = (out / "UPLOAD-SHA1.txt") if args.delta == "auto" else pathlib.Path(args.delta)
        if not pathlib.Path(previous).is_file():
            print("no previous manifest at %s -- building a full package" % previous)
            previous = None
        else:
            # Read it before the output directory is wiped.
            previous = str(pathlib.Path(previous).resolve())
            stash = pathlib.Path(previous).read_text(encoding="utf-8")
            tmp = pack / "scripts" / ".last-upload-sha1"
            tmp.write_text(stash, encoding="utf-8", newline="\n")
            previous = str(tmp)
            out = pathlib.Path(str(out) + "-delta")

    try:
        return build(pack, out, previous)
    finally:
        # The stashed manifest is scratch, and scratch inside a git repo becomes litter.
        stash = pack / "scripts" / ".last-upload-sha1"
        if stash.is_file():
            stash.unlink()


if __name__ == "__main__":
    sys.exit(main())
