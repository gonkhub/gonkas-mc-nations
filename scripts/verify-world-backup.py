#!/usr/bin/env python3
"""Check that a downloaded Minecraft world is a complete, readable backup.

    python scripts/verify-world-backup.py "F:\\gonkas-backups\\server-worlds\\lookscanbethisevening"

WHY THIS EXISTS
---------------
A world pulled down over FTP can arrive silently broken. FileZilla reports success per file, so
the failure modes that matter are the ones it cannot see:

  - a region file truncated mid-transfer, which looks fine in a listing and throws on load;
  - a transfer that ran in ASCII mode, which corrupts every byte of every NBT and region file
    while leaving the sizes plausible;
  - `level.dat` missing or zero-length, which makes the whole folder unloadable;
  - a directory that quietly did not recurse, so `playerdata` or a dimension is simply absent.

A world nobody has verified is not a backup, it is a folder. This reads enough of the structure
to tell the difference, and writes a checksum manifest so a LATER copy can be compared against
this one.

It never writes inside the world. The manifest goes beside it.
"""

import argparse
import gzip
import hashlib
import pathlib
import struct
import sys

# A region file is a 4 KiB location table, a 4 KiB timestamp table, then chunk data. Anything
# shorter than the two tables cannot contain a single chunk, whatever its name says.
REGION_HEADER = 8192


def sha1(path):
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def check_level_dat(world, problems, notes):
    """`level.dat` is gzipped NBT. If it does not decompress, nothing else matters."""
    level = world / "level.dat"
    if not level.is_file():
        problems.append("level.dat is missing - this folder is not a loadable world")
        return
    if level.stat().st_size == 0:
        problems.append("level.dat is zero bytes")
        return
    try:
        with gzip.open(level, "rb") as handle:
            head = handle.read(2048)
        if not head:
            problems.append("level.dat decompresses to nothing")
        else:
            notes.append("level.dat decompresses (%d bytes on disk)" % level.stat().st_size)
    except OSError as error:
        # The classic symptom of an ASCII-mode FTP transfer.
        problems.append("level.dat is not valid gzip (%s) - a text-mode FTP transfer will do "
                        "exactly this" % error)


def check_regions(world, problems, notes):
    """Every .mca must be at least a header, and a multiple of 4 KiB."""
    regions = sorted(world.rglob("*.mca"))
    if not regions:
        problems.append("no region files (*.mca) anywhere - the download did not recurse")
        return
    short = []
    unaligned = []
    empty = []
    for region in regions:
        size = region.stat().st_size
        if size == 0:
            empty.append(region)
        elif size < REGION_HEADER:
            short.append(region)
        elif size % 4096 != 0:
            unaligned.append(region)
    notes.append("%d region file(s)" % len(regions))
    # ZERO-BYTE REGION FILES ARE LEGAL, and this check originally failed on them. A real,
    # healthy world had 53 of them: Minecraft creates an empty .mca when a region is touched but
    # nothing in it ends up saved, and it loads them as "no chunks here". Calling that a broken
    # backup is the exact false alarm that teaches people to ignore a checker.
    #
    # It only means something if EVERY region is empty, which would mean a download that created
    # the files and transferred none of their contents.
    if empty and len(empty) == len(regions):
        problems.append("all %d region file(s) are zero bytes - the transfer created the files "
                        "but moved no data" % len(empty))
    elif empty:
        notes.append("%d empty region file(s), which is normal - Minecraft writes these"
                     % len(empty))
    if short:
        problems.append("%d region file(s) are shorter than a header, e.g. %s"
                        % (len(short), short[0].name))
    if unaligned:
        # 4 KiB alignment is structural, so a file that is not a multiple of it was truncated.
        problems.append("%d region file(s) are not a multiple of 4 KiB - truncated transfer, "
                        "e.g. %s" % (len(unaligned), unaligned[0].name))

    # Spot-check the location table of the largest region: a non-empty world must reference at
    # least one chunk, and every offset must sit inside the file.
    biggest = max(regions, key=lambda p: p.stat().st_size)
    try:
        with biggest.open("rb") as handle:
            table = handle.read(4096)
        size = biggest.stat().st_size
        referenced = 0
        beyond = 0
        for index in range(0, 4096, 4):
            entry = struct.unpack(">I", table[index:index + 4])[0]
            offset = (entry >> 8) * 4096
            length = (entry & 0xFF) * 4096
            if entry == 0:
                continue
            referenced += 1
            if offset + length > size:
                beyond += 1
        if referenced == 0:
            notes.append("%s references no chunks (an empty region is legal)" % biggest.name)
        else:
            notes.append("%s references %d chunk(s)" % (biggest.name, referenced))
        if beyond:
            problems.append("%s has %d chunk offset(s) pointing past the end of the file - "
                            "truncated" % (biggest.name, beyond))
    except (OSError, struct.error) as error:
        problems.append("could not read %s's chunk table (%s)" % (biggest.name, error))


def check_structure(world, problems, notes):
    """The directories a server world should have, and what their absence means."""
    for name, why in (
            ("region", "the overworld's chunks"),
            ("playerdata", "every player's inventory and position"),
            ("data", "maps, raids and scoreboard state"),
    ):
        path = world / name
        if not path.is_dir():
            problems.append("%s/ is missing - %s would be lost" % (name, why))
        else:
            notes.append("%s/ present (%d file(s))" % (name, sum(1 for _ in path.rglob("*")
                                                                 if _.is_file())))
    # Modded dimensions live in their own folders; absence is not an error but is worth saying.
    dimensions = sorted(p.name for p in world.iterdir()
                        if p.is_dir() and (p / "region").is_dir() and p.name != "region")
    if dimensions:
        notes.append("extra dimension(s): %s" % ", ".join(dimensions))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("world", help="the downloaded world folder")
    parser.add_argument("--no-manifest", action="store_true",
                        help="skip writing the checksum manifest (it hashes every file, which "
                             "on a large world takes a while)")
    args = parser.parse_args()

    world = pathlib.Path(args.world)
    if not world.is_dir():
        print("not a directory: %s" % world, file=sys.stderr)
        return 2

    problems = []
    notes = []
    check_level_dat(world, problems, notes)
    check_structure(world, problems, notes)
    check_regions(world, problems, notes)

    files = [p for p in world.rglob("*") if p.is_file()]
    total = sum(p.stat().st_size for p in files)
    print("world:  %s" % world)
    print("        %d files, %.1f MB" % (len(files), total / 1024 / 1024))
    for note in notes:
        print("  ok    %s" % note)
    for problem in problems:
        print("  FAIL  %s" % problem)

    if not args.no_manifest:
        # Beside the world, never inside it: a backup must not be modified by its own check.
        manifest = world.parent / (world.name + ".sha1")
        lines = []
        for path in sorted(files):
            lines.append("%s  %s" % (sha1(path), path.relative_to(world).as_posix()))
        manifest.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print("        manifest: %s (%d entries)" % (manifest, len(lines)))

    if problems:
        print("\nNOT A USABLE BACKUP - %d problem(s). Re-download in BINARY mode." % len(problems))
        return 1
    print("\nok: this looks like a complete, readable world")
    return 0


if __name__ == "__main__":
    sys.exit(main())
