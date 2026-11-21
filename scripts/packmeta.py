#!/usr/bin/env python3
"""Shared facts about this pack, read from one place each.

Four scripts needed the version, the repo root and the server address, and before this
module they each found them a different way: the client builder read `ExportVersion` out
of instance.cfg, the server builder read the loader out of mmc-pack.json, and the address
was a string literal in the client builder. That is three sources of truth for two facts,
and the literal address is the reason the build scripts could not be committed at all
(check-no-secrets.sh blocks a literal IP in a tracked file).

So: `pack.json` is the version, `mmc-pack.json` is the loader, and `release-local.json`
is this machine's view of the live server. The first two are tracked; the third is not.

Every accessor here fails loudly with the fix in the message. A release script that
guesses a version is worse than one that stops.
"""

import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"


class PackError(Exception):
    """Something the operator has to fix before a release can continue."""


def _read_json(path, what):
    if not path.is_file():
        raise PackError("%s: missing %s" % (what, path))
    text = path.read_text(encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise PackError("%s: %s is not valid JSON (%s)" % (what, path, exc))


def pack():
    """pack.json, the version's one home."""
    data = _read_json(ROOT / "pack.json", "pack metadata")
    for key in ("name", "slug", "version", "minecraft", "neoforge"):
        if not data.get(key):
            raise PackError("pack.json is missing %r" % key)
    if not re.fullmatch(r"\d+\.\d+\.\d+", data["version"]):
        raise PackError("pack.json version %r is not MAJOR.MINOR.PATCH"
                        % data["version"])
    return data


def version():
    return pack()["version"]


def loader_version():
    """The NeoForge version Prism will actually launch.

    mmc-pack.json is authoritative because it is what the launcher reads; pack.json
    carries a copy only so a mismatch can be detected.
    """
    data = _read_json(ROOT / "mmc-pack.json", "launcher pack")
    for component in data.get("components", []):
        if component.get("uid") == "net.neoforged":
            return component.get("version")
    raise PackError("mmc-pack.json has no net.neoforged component")


def local(required=True):
    """release-local.json: the live server, this machine's copy, never tracked.

    Returns {} when it is absent and `required` is False, so a build that does not
    need the address still works on a fresh clone.
    """
    path = SCRIPTS / "release-local.json"
    if not path.is_file():
        if not required:
            return {}
        raise PackError(
            "no scripts/release-local.json.\n"
            "    Copy scripts/release-local.example.json to scripts/release-local.json\n"
            "    and fill in the server address and FTP account. It is gitignored.\n"
            "    The FTP password does NOT go in it - see the comment in the example.")
    data = _read_json(path, "local release config")
    return data


def server_address(required=True):
    cfg = local(required=required)
    addr = cfg.get("server_address", "")
    if required and (not addr or addr.startswith("0.0.0.0")):
        raise PackError("scripts/release-local.json: server_address is still the "
                        "placeholder. Set it to the live server's host:port.")
    return addr


def git(*args, check=True):
    """Run git in the repo and return stdout stripped."""
    proc = subprocess.run(["git", "-C", str(ROOT)] + list(args),
                          capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise PackError("git %s failed: %s" % (" ".join(args), proc.stderr.strip()))
    return proc.stdout.strip()


def tracked_files():
    out = git("ls-files")
    return [line for line in out.splitlines() if line]


def working_tree_dirty():
    """Paths with uncommitted changes, ignoring untracked files.

    Untracked is deliberately not dirty: dist/ and release-local.json are both
    untracked by design, and a release that refused to run because of them would
    teach the operator to pass --force every time.
    """
    out = git("status", "--porcelain", "--untracked-files=no")
    return [line for line in out.splitlines() if line.strip()]


def die(message):
    print("error: %s" % message, file=sys.stderr)
    raise SystemExit(1)
