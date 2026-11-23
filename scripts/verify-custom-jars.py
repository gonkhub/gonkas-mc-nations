#!/usr/bin/env python3
"""Make it impossible for two different builds to share a version string.

    python scripts/verify-custom-jars.py            # check, exit non-zero on a problem
    python scripts/verify-custom-jars.py --record   # check, then record new versions
    python scripts/verify-custom-jars.py --show      # print the ledger and exit

WHAT WENT WRONG, AND WHY A CHECKSUM FILE WAS NOT ENOUGH
-------------------------------------------------------
`thee` shipped at least four distinct builds all stamped `1.0.0`, with the same filename:
399419, 434300, 439118 and 509665 bytes. Two of them differ by whether the Mechanical
Broker exists at all. A client with `thee:mechanical_broker` in its registry cannot
connect to a server without it - the handshake fails on the missing registry path - so
this is a hard connect failure that hits every player at once on launch day. It already
happened once on the gonk server.

`hand-installed.sha1` was the first answer, and it is necessary but not sufficient: it
pins the bytes of the jar that is in `mods/` RIGHT NOW. Rebuild `thee` without bumping
`mod_version`, drop the new jar in, re-run `sha1sum` to "fix" the manifest, and the
manifest is once again perfectly self-consistent and describing a different mod. The
manifest cannot catch that, because the manifest is the thing being updated.

So this script adds the one check that survives the manifest being rewritten: a LEDGER of
every (mod, version) ever seen, with the sha1 it had. A version may be recorded exactly
once. Presenting a different jar under a version already in the ledger is refused, and
the only way forward is a real version bump - which is the actual cure.

THREE CHECKS, IN ORDER OF WHAT THEY CATCH
-----------------------------------------
1. FILENAME vs JAR. `thee-1.28.0.jar` must contain `version="1.28.0"` in its own
   `neoforge.mods.toml`. Catches a renamed jar, which is the cheapest way to fake a bump
   and the easiest mistake to make when copying out of `build/libs`.
2. JAR vs CHECKSUM MANIFEST. The usual guard, and the one that catches a jar swapped
   under a correct name.
3. VERSION vs LEDGER. Catches a rebuilt jar at an unchanged version - the original
   failure, and the one the other two cannot see.

Check 1 is also why the ledger is keyed on the version inside the jar rather than the
version in the filename: the filename is the part an operator can get wrong by hand.
"""

import argparse
import hashlib
import pathlib
import sys
import tomllib
import zipfile
from datetime import date

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import packmeta  # noqa: E402

MODS = packmeta.ROOT / "minecraft" / "mods"
MANIFEST = MODS / "hand-installed.sha1"
LEDGER = MODS / "version-ledger.tsv"

# The jars this pack builds itself. Third-party hand-installed jars (kubejs, lootjs,
# morejs) are deliberately NOT here: they are published artifacts with real version
# discipline upstream, and hand-installed.sha1 already pins them. The ledger is for
# the jars whose version string is set by whoever ran gradle.
CUSTOM_PREFIXES = ("thee-", "chaosgate-", "omnifix-", "atlas-", "maptable-")

LEDGER_HEADER = (
    "# Every version of a pack-built mod ever released, and the sha1 it had.\n"
    "#\n"
    "# APPEND-ONLY. A row is never edited and never deleted. If a line here is wrong,\n"
    "# the fix is a new version, not a corrected row - the whole value of this file is\n"
    "# that it remembers what a version string meant the first time it was used.\n"
    "#\n"
    "# A version appearing twice with two different sha1s is the bug this file exists to\n"
    "# catch; see scripts/verify-custom-jars.py.\n"
    "#\n"
    "# mod_id\tversion\tsha1\tbytes\tfirst_recorded\n")


def sha1_of(path):
    return hashlib.sha1(path.read_bytes()).hexdigest()


def jar_metadata(path):
    """(mod_id, version) from the jar's own neoforge.mods.toml."""
    try:
        with zipfile.ZipFile(path) as z:
            raw = z.read("META-INF/neoforge.mods.toml")
    except KeyError:
        raise packmeta.PackError("%s has no META-INF/neoforge.mods.toml - not a "
                                 "NeoForge mod jar?" % path.name)
    except zipfile.BadZipFile:
        raise packmeta.PackError("%s is not a readable zip - truncated copy?"
                                 % path.name)
    data = tomllib.loads(raw.decode("utf-8"))
    mods = data.get("mods") or []
    if not mods:
        raise packmeta.PackError("%s declares no [[mods]]" % path.name)
    entry = mods[0]
    return entry.get("modId"), str(entry.get("version", ""))


def filename_version(name):
    """`thee-1.28.0.jar` -> `1.28.0`. Returns None if the name has no version."""
    stem = name[:-4] if name.lower().endswith(".jar") else name
    if "-" not in stem:
        return None
    return stem.rsplit("-", 1)[1]


def read_manifest():
    """{relative path: sha1} from hand-installed.sha1."""
    out = {}
    if not MANIFEST.is_file():
        return out
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        digest, _, rel = line.partition(" ")
        rel = rel.strip().lstrip("*")
        if digest and rel:
            out[rel] = digest
    return out


def read_ledger():
    """{(mod_id, version): (sha1, bytes, first_recorded)}."""
    out = {}
    if not LEDGER.is_file():
        return out
    for line in LEDGER.read_text(encoding="utf-8").splitlines():
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        mod_id, ver, digest = parts[0], parts[1], parts[2]
        size = parts[3] if len(parts) > 3 else ""
        seen = parts[4] if len(parts) > 4 else ""
        out[(mod_id, ver)] = (digest, size, seen)
    return out


def append_ledger(rows):
    exists = LEDGER.is_file()
    with LEDGER.open("a", encoding="utf-8", newline="\n") as fh:
        if not exists:
            fh.write(LEDGER_HEADER)
        for mod_id, ver, digest, size in rows:
            fh.write("%s\t%s\t%s\t%d\t%s\n"
                     % (mod_id, ver, digest, size, date.today().isoformat()))


def custom_jars():
    if not MODS.is_dir():
        raise packmeta.PackError("no minecraft/mods directory at %s" % MODS)
    found = []
    for path in sorted(MODS.glob("*.jar")):
        if path.name.startswith(CUSTOM_PREFIXES):
            found.append(path)
    return found


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--record", action="store_true",
                        help="append newly seen versions to the ledger")
    parser.add_argument("--show", action="store_true",
                        help="print the ledger and exit")
    args = parser.parse_args()

    ledger = read_ledger()

    if args.show:
        if not ledger:
            print("ledger is empty (%s)" % LEDGER)
            return 0
        print("%-12s %-10s %-42s %10s  %s"
              % ("mod", "version", "sha1", "bytes", "recorded"))
        for (mod_id, ver), (digest, size, seen) in sorted(ledger.items()):
            print("%-12s %-10s %-42s %10s  %s" % (mod_id, ver, digest, size, seen))
        return 0

    jars = custom_jars()
    if not jars:
        print("verify-custom-jars: no pack-built jars in minecraft/mods", file=sys.stderr)
        return 2

    manifest = read_manifest()
    problems = []
    new_rows = []

    for path in jars:
        rel = path.relative_to(packmeta.ROOT).as_posix()
        mod_id, jar_version = jar_metadata(path)
        digest = sha1_of(path)
        size = path.stat().st_size
        name_version = filename_version(path.name)

        # 1. filename vs jar
        if name_version != jar_version:
            problems.append(
                "%s: filename says %s, the jar's own neoforge.mods.toml says %s.\n"
                "        A renamed jar is not a version bump. Rebuild with "
                "mod_version=%s in gradle.properties,\n"
                "        or rename the file back to %s-%s.jar."
                % (path.name, name_version, jar_version, name_version,
                   mod_id, jar_version))

        # 2. jar vs checksum manifest
        expected = manifest.get(rel)
        if expected is None:
            problems.append(
                "%s: no entry in minecraft/mods/hand-installed.sha1.\n"
                "        Add:  %s *%s" % (path.name, digest, rel))
        elif expected != digest:
            problems.append(
                "%s: sha1 on disk does not match hand-installed.sha1.\n"
                "        manifest: %s\n"
                "        on disk:  %s\n"
                "        This jar is not the one the pack was built against."
                % (path.name, expected, digest))

        # 3. version vs ledger - the check that survives the manifest being rewritten
        known = ledger.get((mod_id, jar_version))
        if known is None:
            new_rows.append((mod_id, jar_version, digest, size))
            print("new      %s %s  %s  %d bytes"
                  % (mod_id, jar_version, digest, size))
        elif known[0] != digest:
            problems.append(
                "%s %s HAS ALREADY SHIPPED AS DIFFERENT BYTES.\n"
                "        ledger (%s): %s  %s bytes\n"
                "        on disk:            %s  %d bytes\n"
                "        This is the exact failure that cost launch day on the gonk\n"
                "        server. Bump mod_version in the source's gradle.properties,\n"
                "        rebuild, and rename the jar to match. Do not edit the ledger."
                % (mod_id, jar_version, known[2] or "?", known[0], known[1] or "?",
                   digest, size))
        else:
            print("ok       %s %s  %s" % (mod_id, jar_version, digest))

    if problems:
        print(file=sys.stderr)
        for problem in problems:
            print("BLOCKED  %s" % problem, file=sys.stderr)
        print("\n%d problem(s). Nothing was recorded." % len(problems), file=sys.stderr)
        return 1

    if new_rows:
        if args.record:
            append_ledger(new_rows)
            print("\nrecorded %d new version(s) in %s"
                  % (len(new_rows), LEDGER.relative_to(packmeta.ROOT).as_posix()))
        else:
            print("\n%d version(s) not in the ledger yet. Re-run with --record to "
                  "add them." % len(new_rows))

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except packmeta.PackError as exc:
        packmeta.die(exc)
