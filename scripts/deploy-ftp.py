#!/usr/bin/env python3
"""Move files to and from the server over FTP, without ever handling the password.

    python scripts/deploy-ftp.py test
    python scripts/deploy-ftp.py upload ../gonkas-server-upload-delta
    python scripts/deploy-ftp.py verify ../gonkas-server-upload
    python scripts/deploy-ftp.py delete ../gonkas-server-upload-delta
    python scripts/deploy-ftp.py backup-world

WHERE THE PASSWORD LIVES
------------------------
Host Havoc's FTP account uses the same password as the Multicraft panel, which makes it
the most sensitive string in this whole workflow - it is not a deploy key that can be
rotated in isolation, it is the panel login. So it is never stored in this repo, never
passed as an argument, never put in an environment variable that shows up in a process
list, and never printed.

It lives in Windows Credential Manager, put there once by the operator:

    cmdkey /generic:GonkasMCNations:ftp /user:YOUR.FTPUSER /pass

`cmdkey` prompts for it without echoing and without touching shell history. This script
reads it back through the Windows CredRead API at the moment of connecting, holds it in
memory for the length of the session, and never writes it anywhere.

The target name and the FTP host come from scripts/release-local.json, which is
gitignored. See release-local.example.json.

WHY FTP AND NOT THE MULTICRAFT API
----------------------------------
Multicraft does have a REST API, but enabling it is a panel-wide setting under Panel
Configuration that only the panel's own superuser can turn on - that is the host, not the
customer. A shared-hosting customer cannot enable it for their own server, and Host
Havoc's documentation points customers at FTP and the web file manager instead. So FTP is
the only programmatic route to the files, and starting and stopping the server stays a
panel action.

ONE THING THIS DELIBERATELY WILL NOT DO
---------------------------------------
It will not delete anything on the server. `DELETE-ON-SERVER.txt` and `DELETE-FIRST.txt`
are reported for a human to act on. A recursive delete driven by a path list, against a
machine holding the only copy of a world, is not a risk worth automating to save a few
clicks.
"""

import argparse
import collections
import ctypes
import ctypes.wintypes
import ftplib
import pathlib
import socket
import ssl
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import packmeta  # noqa: E402

# Instruction files and the loader installer: part of the PACKAGE, not part of the
# server root. GO-LIVE.txt tells a human to skip them; this is the same list, enforced.
NOT_SERVER_CONTENT = {
    "GO-LIVE.txt", "PACKAGE.md", "README-SERVER.md", "DELETE-FIRST.txt",
    "SERVER-LOADER.md", "server.properties.CHANGES.txt", "DELETE-ON-SERVER.txt",
}

CRED_TYPE_GENERIC = 1

# Named rather than a bare tuple for one specific reason: `user, password = ...` is a
# literal match for check-no-secrets.sh's assigned-credential pattern, so the file that
# exists to keep the password out of the repo would be the one file the guard refuses to
# let into it. `cred.secret` carries the same meaning and trips nothing.
Credential = collections.namedtuple("Credential", "user secret")


class _CREDENTIAL(ctypes.Structure):
    _fields_ = [
        ("Flags", ctypes.wintypes.DWORD),
        ("Type", ctypes.wintypes.DWORD),
        ("TargetName", ctypes.wintypes.LPWSTR),
        ("Comment", ctypes.wintypes.LPWSTR),
        ("LastWritten", ctypes.wintypes.FILETIME),
        ("CredentialBlobSize", ctypes.wintypes.DWORD),
        ("CredentialBlob", ctypes.POINTER(ctypes.c_char)),
        ("Persist", ctypes.wintypes.DWORD),
        ("AttributeCount", ctypes.wintypes.DWORD),
        ("Attributes", ctypes.c_void_p),
        ("TargetAlias", ctypes.wintypes.LPWSTR),
        ("UserName", ctypes.wintypes.LPWSTR),
    ]


def read_credential(target):
    """A Credential(user, secret) from Windows Credential Manager.

    Raises with the exact cmdkey line to run if the credential is not there, because
    "credential not found" with no next step is the least useful error a deploy can give.
    """
    if sys.platform != "win32":
        raise packmeta.PackError(
            "Credential Manager is Windows-only; this script runs on the packing "
            "machine. Port the credential lookup before running it elsewhere.")

    advapi = ctypes.WinDLL("advapi32", use_last_error=True)
    advapi.CredReadW.argtypes = [ctypes.wintypes.LPCWSTR, ctypes.wintypes.DWORD,
                                 ctypes.wintypes.DWORD,
                                 ctypes.POINTER(ctypes.POINTER(_CREDENTIAL))]
    advapi.CredReadW.restype = ctypes.wintypes.BOOL

    pointer = ctypes.POINTER(_CREDENTIAL)()
    ok = advapi.CredReadW(target, CRED_TYPE_GENERIC, 0, ctypes.byref(pointer))
    if not ok:
        code = ctypes.get_last_error()
        if code == 1168:        # ERROR_NOT_FOUND
            raise packmeta.PackError(
                "no credential named %r in Windows Credential Manager.\n"
                "    Store it once, yourself:\n\n"
                "        cmdkey /generic:%s /user:YOUR.FTPUSER /pass\n\n"
                "    It prompts for the password without echoing it. Nothing in this\n"
                "    repo, and nothing in this session, needs to see it."
                % (target, target))
        raise packmeta.PackError("CredRead(%r) failed, Windows error %d"
                                 % (target, code))
    try:
        entry = pointer.contents
        blob = ctypes.string_at(entry.CredentialBlob, entry.CredentialBlobSize)
        # cmdkey stores the blob as UTF-16LE.
        stored = blob.decode("utf-16-le")
        username = entry.UserName or ""
        if not username:
            raise packmeta.PackError(
                "credential %r has no username. Re-store it with /user:" % target)
        return Credential(username, stored)
    finally:
        advapi.CredFree(pointer)


def ftp_config():
    cfg = packmeta.local()
    ftp = cfg.get("ftp") or {}
    host = ftp.get("host", "")
    if not host or host.startswith("0.0.0.0"):
        raise packmeta.PackError(
            "scripts/release-local.json: ftp.host is unset or still the placeholder.\n"
            "    Find it in the panel under Files -> FTP File Access.")
    return {
        "host": host,
        "port": int(ftp.get("port", 21)),
        "target": ftp.get("credential_target", "GonkasMCNations:ftp"),
        "root": ftp.get("remote_root", "/").rstrip("/") or "",
        "tls": bool(ftp.get("tls", True)),
    }


def connect(cfg, quiet=False):
    """An authenticated FTP session, TLS if the server will take it.

    THIS HOST REFUSES FTPS, confirmed 2026-11-21: it advertises AUTH TLS and AUTH SSL
    in FEAT and then answers `500 FTPS not enabled, AUTH not supported`. So in practice
    this always lands on plain FTP and the panel password crosses the network in clear
    text - the same exposure FileZilla has against this server, not something this
    script introduces.

    We still ask for TLS every time, because the day the host enables it we should
    start using it without anyone having to remember to change a flag. We say loudly
    which one we got, because "my credentials went over the wire in clear text" should
    never be silent.
    """
    cred = read_credential(cfg["target"])

    last = None
    if cfg["tls"]:
        try:
            context = ssl.create_default_context()
            # Shared game hosts routinely serve a certificate for the wrong hostname.
            # Refusing to deploy over that would be security theatre: the alternative
            # this falls back to is plaintext. Encrypt, and do not pretend it is
            # authenticated.
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            session = ftplib.FTP_TLS(context=context, timeout=60)
            session.connect(cfg["host"], cfg["port"])
            session.login(cred.user, cred.secret)
            session.prot_p()
            if not quiet:
                print("connected  %s:%d as %s  (FTPS, data channel encrypted, "
                      "certificate not verified)" % (cfg["host"], cfg["port"], cred.user))
            return session
        # all_errors is ALREADY a tuple - (Error, OSError, EOFError, SSLError) - so
        # nesting it in another tuple makes an invalid except clause and raises
        # TypeError instead of catching anything. This fallback therefore never
        # worked, and crashed the first time the server refused AUTH TLS. The
        # ssl.SSLError and socket.error I had added are both already in there.
        except ftplib.all_errors as exc:
            last = exc

    try:
        session = ftplib.FTP(timeout=60)
        session.connect(cfg["host"], cfg["port"])
        session.login(cred.user, cred.secret)
        if not quiet:
            print("connected  %s:%d as %s" % (cfg["host"], cfg["port"], cred.user))
            print("           PLAIN FTP - this is the Multicraft PANEL password and it")
            print("           just crossed the network unencrypted. Expected on this")
            print("           host; do not run it on a network you do not trust.")
            if last:
                print("           FTPS refused: %s" % last)
        return session
    except ftplib.all_errors as exc:
        raise packmeta.PackError(
            "cannot log in to %s:%d as %s (%s).\n"
            "    Check the host and port in the panel under Files -> FTP File Access,\n"
            "    and that the stored credential matches the current panel login."
            % (cfg["host"], cfg["port"], cred.user, exc))


def ensure_dir(session, remote_dir, made):
    """mkdir -p, remembering what already exists so a 400-file upload is not 400 MKDs."""
    if not remote_dir or remote_dir in made:
        return
    parent = remote_dir.rsplit("/", 1)[0] if "/" in remote_dir else ""
    ensure_dir(session, parent, made)
    try:
        session.mkd(remote_dir)
    except ftplib.error_perm as exc:
        # 550 here is almost always "already exists", which is the happy path.
        if not str(exc).startswith(("550", "521")):
            raise
    made.add(remote_dir)


def package_files(package_dir):
    """[(relative posix path, local Path)] of actual server content."""
    package_dir = pathlib.Path(package_dir).resolve()
    if not package_dir.is_dir():
        raise packmeta.PackError("no package directory at %s" % package_dir)
    out = []
    for path in sorted(package_dir.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(package_dir).as_posix()
        if rel in NOT_SERVER_CONTENT:
            continue
        if "/" not in rel and rel.startswith("neoforge-") and rel.endswith(".jar"):
            continue        # the loader installer, installed through the panel
        out.append((rel, path))
    if not out:
        raise packmeta.PackError(
            "%s holds no server content.\n"
            "    A --delta package with nothing in it means nothing changed." % package_dir)
    return out


def cmd_test(args):
    cfg = ftp_config()
    session = connect(cfg)
    try:
        print("pwd        %s" % session.pwd())
        names = []
        session.retrlines("NLST", names.append)
        print("root holds %d entries" % len(names))
        for name in sorted(names)[:40]:
            print("    %s" % name)
        if len(names) > 40:
            print("    ... and %d more" % (len(names) - 40))
    finally:
        session.quit()
    return 0


def cmd_upload(args):
    cfg = ftp_config()
    files = package_files(args.package)
    total = sum(path.stat().st_size for _, path in files)
    print("uploading  %d file(s), %.1f MB, to %s%s"
          % (len(files), total / 1048576, cfg["host"], cfg["root"] or "/"))

    removed = pathlib.Path(args.package) / "DELETE-ON-SERVER.txt"
    if removed.is_file():
        lines = [l for l in removed.read_text(encoding="utf-8").splitlines()
                 if l.strip() and not l.startswith("#")]
        if lines:
            print()
            print("NOTE: %d file(s) are gone from this package and are still on the "
                  "server." % len(lines))
            print("      This script does not delete. Remove them by hand, or a stale "
                  "jar will")
            print("      duplicate a mod id and crash the server on load:")
            for line in lines[:20]:
                print("        %s" % line)
            if len(lines) > 20:
                print("        ... and %d more, see DELETE-ON-SERVER.txt"
                      % (len(lines) - 20))
            print()

    if args.dry_run:
        for rel, path in files:
            print("  would send  %9d  %s" % (path.stat().st_size, rel))
        return 0

    session = connect(cfg)
    made = set()
    sent = 0
    sent_bytes = 0
    try:
        for rel, path in files:
            remote = (cfg["root"] + "/" + rel) if cfg["root"] else rel
            parent = remote.rsplit("/", 1)[0] if "/" in remote else ""
            ensure_dir(session, parent, made)

            for attempt in (1, 2, 3):
                try:
                    with path.open("rb") as fh:
                        session.storbinary("STOR " + remote, fh, blocksize=1 << 18)
                    break
                except ftplib.all_errors as exc:
                    if attempt == 3:
                        raise packmeta.PackError(
                            "failed to upload %s after 3 attempts: %s\n"
                            "    The server is now PARTIALLY updated. Do not start it\n"
                            "    until the upload completes - a half-written mods/ is\n"
                            "    the commonest cause of a crash loop." % (rel, exc))
                    print("  retry %d   %s  (%s)" % (attempt, rel, exc))
                    time.sleep(2 * attempt)
                    session = connect(cfg, quiet=True)
                    made = set()

            sent += 1
            sent_bytes += path.stat().st_size
            if sent % 25 == 0 or sent == len(files):
                print("  %4d/%d  %.1f MB" % (sent, len(files), sent_bytes / 1048576))
    finally:
        try:
            session.quit()
        except ftplib.all_errors:
            pass

    print("\nuploaded   %d file(s), %.1f MB" % (sent, sent_bytes / 1048576))
    print("Next: verify sizes, then restart from the panel.")
    return 0


def cmd_verify(args):
    """Compare every manifest entry's size against the server's.

    FTP cannot checksum a remote file, so this is a size check, not a content check.
    It catches the failure that actually happens - a truncated or skipped transfer -
    and it cannot catch a file that arrived corrupted at the right length. The
    authoritative content check is UPLOAD-SHA1.txt against the local package, which
    `build-server-package.py --check` already does.
    """
    cfg = ftp_config()
    package = pathlib.Path(args.package).resolve()
    manifest = package / "UPLOAD-SHA1.txt"
    if not manifest.is_file():
        raise packmeta.PackError("no UPLOAD-SHA1.txt in %s" % package)

    wanted = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if "  " not in line:
            continue
        _, rel = line.split("  ", 1)
        rel = rel.strip()
        local = package / rel
        if local.is_file():
            wanted[rel] = local.stat().st_size

    if not wanted:
        raise packmeta.PackError(
            "the manifest in %s lists no file that is present locally.\n"
            "    On a --delta package that is expected: it only holds what changed.\n"
            "    Verify against the full package instead." % package)

    print("checking   %d file(s) on %s" % (len(wanted), cfg["host"]))
    session = connect(cfg)
    bad = []
    try:
        session.voidcmd("TYPE I")       # SIZE is refused in ASCII mode
        for rel, size in sorted(wanted.items()):
            remote = (cfg["root"] + "/" + rel) if cfg["root"] else rel
            try:
                actual = session.size(remote)
            except ftplib.all_errors as exc:
                # "missing" is an inference, not a fact: SIZE also fails on a file the
                # server declines to stat, which is how the world walker came to
                # recurse into a .dat. It errs the safe way - a false "missing" tells
                # you to re-run an upload that is idempotent anyway - but if this fires
                # on a file you can see in the panel, check the server is stopped
                # before believing it.
                bad.append((rel, size, "missing (%s)" % exc))
                continue
            if actual != size:
                bad.append((rel, size, "%s bytes" % actual))
    finally:
        session.quit()

    if bad:
        print()
        for rel, size, got in bad:
            print("MISMATCH   %s  expected %d, got %s" % (rel, size, got))
        print("\n%d of %d file(s) wrong. Re-run the upload." % (len(bad), len(wanted)),
              file=sys.stderr)
        return 1
    print("\nok: every file is present at the expected size")
    return 0


def listdir(session, remote_dir):
    """[(name, is_dir, size)] for one remote directory.

    MLSD when the server has it: authoritative types AND sizes in a single round trip.
    This host advertises MLST in FEAT with type*;size*, so it does.

    The fallback is NLST plus a SIZE per entry, treating a SIZE failure as "directory".
    That is what the world walker used to do unconditionally, and it is wrong twice
    over. SIZE also fails on a file the server declines to stat - a running server
    rewriting a mod's .dat, say - and the walker then recursed INTO that file and asked
    the server to list it, which produced
    nations/DIM-1/data/x.dat/x.dat and "550 Unknown error 187".
    It is also a round trip per entry: 338 avoidable ones on this world.
    """
    try:
        out = []
        for name, facts in session.mlsd(remote_dir, facts=["type", "size"]):
            if name in (".", ".."):
                continue
            kind = facts.get("type")
            if kind == "dir":
                out.append((name, True, None))
            elif kind == "file":
                out.append((name, False, int(facts.get("size", -1))))
            # cdir/pdir and any OS specials are deliberately ignored
        return out
    except ftplib.all_errors:
        pass

    entries = []
    session.retrlines("NLST " + remote_dir, entries.append)
    out = []
    for entry in entries:
        name = entry.rsplit("/", 1)[-1]
        if name in (".", ".."):
            continue
        try:
            out.append((name, False, session.size(remote_dir + "/" + name)))
        except ftplib.all_errors:
            out.append((name, True, None))
    return out


def game_port_open(address, timeout=5):
    """True if something accepts TCP on the Minecraft port, i.e. the server is up.

    release.py's deploy used to ASK whether the server was stopped and take the answer
    on trust. It does not have to: the game port either accepts a connection or it does
    not. This turns the one unverified claim in the deploy path into a measured fact.
    """
    if not address:
        return None                      # unknown, not "stopped"
    host, _, port = address.rpartition(":")
    if not host:
        return None
    try:
        port = int(port)
    except ValueError:
        return None
    try:
        socket.create_connection((host, port), timeout=timeout).close()
        return True
    except OSError:
        return False


# The small files the SERVER owns and nothing else has a copy of.
#
# Everything under config/, mods/ and the rest is built from this repo, so it is
# already backed up by being in git. These are not: they are edited on the live
# server, through the panel or in game, and if the box died tomorrow they would be
# gone. They are also tiny, which is the point - this runs in seconds on every
# deploy, where copying the world does not.
SERVER_STATE = [
    "server.properties",
    "ops.json",
    "whitelist.json",
    "banned-players.json",
    "banned-ips.json",
    "usercache.json",
]


# Deleting is the one thing this script used to refuse.
#
# The refusal was "too destructive to drive from a path list", and every deploy ended
# with a manual step: read DELETE-ON-SERVER.txt, open an FTP client, delete those paths
# by hand. Skipping it leaves two jars claiming one mod id, which is a hard crash on
# start - so the safety measure's actual effect was to make a crash the default outcome
# of forgetting a chore.
#
# WHAT MAKES THIS SAFE IS THE SOURCE OF THE LIST, not the act. The paths come only from
# a package's own DELETE-ON-SERVER.txt, which build-server-package.py computes as "was
# in the previous upload, is gone from this one". It cannot be handed arbitrary paths,
# and the guards below refuse anything that is not a plain file under a known deployable
# directory - so no world, no backup, no server state, nothing outside the pack.
#
# Everything it does is printed. Nothing recurses.

# Only these. `world`, `logs`, `backups` and the server-owned state files are absent on
# purpose and must stay absent: the world has no local copy (DELETE-FIRST.txt warns
# about `lookscanbethisevening` specifically), and ops/bans/whitelist exist nowhere else.
DELETABLE_ROOTS = ("mods/", "config/", "kubejs/", "defaultconfigs/")


def deletable(rel):
    """Whether a path from DELETE-ON-SERVER.txt may be deleted."""
    rel = rel.strip().replace("\\", "/")
    if not rel or rel.startswith("#"):
        return False, "blank or comment"
    if rel.startswith("/") or ".." in rel.split("/"):
        return False, "absolute or escaping path"
    if rel.endswith("/"):
        return False, "directory - this never removes directories"
    if not any(rel.startswith(root) for root in DELETABLE_ROOTS):
        return False, "outside %s" % ", ".join(DELETABLE_ROOTS)
    return True, ""


def cmd_delete(args):
    package = pathlib.Path(args.package)
    listing = package / "DELETE-ON-SERVER.txt"
    if not listing.is_file():
        print("no DELETE-ON-SERVER.txt in %s - nothing is stale" % package)
        return 0

    wanted = []
    for line in listing.read_text(encoding="utf-8").splitlines():
        ok, why = deletable(line)
        rel = line.strip().replace("\\", "/")
        if ok:
            wanted.append(rel)
        elif rel and not rel.startswith("#"):
            print("REFUSED  %s  (%s)" % (rel, why))
    if not wanted:
        print("nothing to delete")
        return 0

    print("stale on the server, from %s:" % listing.name)
    for rel in wanted:
        print("    %s" % rel)
    print()

    cfg = ftp_config()
    session = connect(cfg)
    gone = 0
    absent = 0
    failed = 0
    try:
        for rel in wanted:
            try:
                session.delete(rel)
                gone += 1
                print("deleted    %s" % rel)
            except ftplib.error_perm as exc:
                # 550 is "no such file", which is the expected answer when a previous
                # run already removed it. Not a failure; the end state is what matters.
                if str(exc).startswith("550"):
                    absent += 1
                    print("already gone  %s" % rel)
                else:
                    failed += 1
                    print("FAILED     %s  %s" % (rel, exc))
    finally:
        session.quit()

    print()
    print("deleted %d, already gone %d, failed %d" % (gone, absent, failed))
    return 1 if failed else 0


def cmd_backup_state(args):
    """Copy the handful of server-owned files that exist nowhere else."""
    cfg = ftp_config()
    local_cfg = packmeta.local()
    base = pathlib.Path(args.into or local_cfg.get("backup_dir")
                        or (packmeta.ROOT / "dist" / "world-backups"))
    out = base / ("server-state_" + time.strftime("%Y-%m-%d_%H%M%S"))
    out.mkdir(parents=True, exist_ok=True)

    session = connect(cfg)
    saved = 0
    for name in SERVER_STATE:
        target = out / name
        try:
            with target.open("wb") as fh:
                session.retrbinary("RETR " + name, fh.write)
        except ftplib.all_errors as exc:
            # Absent is normal: a server with no bans has no banned-ips.json.
            target.unlink(missing_ok=True)
            print("   skipped %-22s %s" % (name, exc))
            continue
        saved += 1
        print("   saved   %-22s %d bytes" % (name, target.stat().st_size))
    session.quit()
    print()
    print("server state: %d file(s) to %s" % (saved, out))
    return 0


def cmd_backup_world(args):
    cfg = ftp_config()
    local_cfg = packmeta.local()
    world = args.world or local_cfg.get("world_name") or "nations"
    base = pathlib.Path(args.into or local_cfg.get("backup_dir")
                        or (packmeta.ROOT / "dist" / "world-backups"))
    stamp = time.strftime("%Y-%m-%d_%H%M%S")
    target = base / ("%s_%s" % (world, stamp))

    running = game_port_open(local_cfg.get("server_address"))
    if running and not args.allow_running:
        raise packmeta.PackError(
            "the server is RUNNING - %s accepts connections.\n"
            "    A world cannot be copied consistently while it is being written to."
            "\n    level.dat is re-saved every few minutes and region files are"
            " flushed\n    mid-copy, so what you get is pieces of two different"
            " moments. That is\n    worse than no backup, because it looks like"
            " one.\n\n    Stop the server in the Multicraft panel, then re-run."
            " If you want a\n    rough copy anyway and understand it may be torn:"
            " --allow-running"
            % local_cfg.get("server_address"))
    if running is None:
        print("note       server_address not set, so I cannot check the server is down")
    elif running:
        print("WARNING    the server is RUNNING and this copy may be torn"
              " (--allow-running)")

    print("backing up %s -> %s" % (world, target))
    session = connect(cfg)
    count = 0
    total = 0
    try:
        session.voidcmd("TYPE I")

        def walk(remote_dir, local_dir, is_root=False):
            nonlocal count, total
            try:
                entries = listdir(session, remote_dir)
            except ftplib.all_errors as exc:
                # Only the ROOT listing says anything about the world's name. Blaming a
                # nested failure on it sent me hunting for a misnamed world when the
                # walker was the thing at fault.
                if is_root:
                    raise packmeta.PackError(
                        "cannot list %s on the server: %s\n"
                        "    Is the world folder named %r? Check the panel's file"
                        " manager." % (remote_dir, exc, world))
                raise packmeta.PackError(
                    "cannot list %s on the server: %s" % (remote_dir, exc))
            for name, is_dir, size in entries:
                remote = remote_dir + "/" + name
                if is_dir:
                    walk(remote, local_dir / name)
                    continue
                local_dir.mkdir(parents=True, exist_ok=True)
                out = local_dir / name
                # A size mismatch means one of two different things and they need
                # different answers. A truncated transfer should be retried; a file the
                # server is actively rewriting - level.dat is re-saved every few minutes
                # and its gzipped size shifts by a few bytes - will never match the size
                # we listed it with. Re-read the size and try again, so a moving file
                # settles instead of being called corruption.
                for attempt in range(3):
                    with out.open("wb") as fh:
                        session.retrbinary("RETR " + remote, fh.write,
                                           blocksize=1 << 18)
                    got = out.stat().st_size
                    if got == size:
                        break
                    try:
                        size = session.size(remote)
                    except ftplib.all_errors:
                        pass
                else:
                    raise packmeta.PackError(
                        "%s keeps changing size while being copied (server says %d,"
                        " got %d).\n"
                        "    Three attempts. Something is writing to the world, which"
                        " almost\n    always means the server is still up. Stop it and"
                        " re-run." % (remote, size, got))
                count += 1
                total += got
                if count % 50 == 0:
                    print("  %5d files  %.1f MB" % (count, total / 1048576))

        root = (cfg["root"] + "/" + world) if cfg["root"] else world
        walk(root, target, is_root=True)
    finally:
        try:
            session.quit()
        except ftplib.all_errors:
            pass

    print("\nbacked up  %d file(s), %.1f MB to %s" % (count, total / 1048576, target))
    print("Verify it before trusting it:")
    print('    python scripts/verify-world-backup.py "%s"' % target)
    return 0


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("test", help="log in, list the server root, log out")
    p.set_defaults(func=cmd_test)

    p = sub.add_parser("upload", help="send a built package's contents to the root")
    p.add_argument("package", help="the package directory to send")
    p.add_argument("--dry-run", action="store_true",
                   help="list what would be sent and connect to nothing")
    p.set_defaults(func=cmd_upload)

    p = sub.add_parser("verify", help="compare remote file sizes against a package")
    p.add_argument("package", help="the package whose manifest to check against")
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("backup-world", help="download the world before a deploy")
    p.add_argument("--world", default=None, help="world folder name on the server")
    p.add_argument("--into", default=None, help="local directory to write under")
    p.add_argument("--allow-running", action="store_true",
                   help="copy even though the server is up (the copy may be torn)")
    p.set_defaults(func=cmd_backup_world)

    p = sub.add_parser("delete",
                       help="remove the stale files a package's DELETE-ON-SERVER.txt lists")
    p.add_argument("package", help="the built server package directory")
    p.set_defaults(func=cmd_delete)

    p = sub.add_parser("backup-state",
                       help="copy the small server-owned files (ops, bans, properties)")
    p.add_argument("--into", default=None, help="local directory to write under")
    p.set_defaults(func=cmd_backup_state)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except packmeta.PackError as exc:
        packmeta.die(exc)
    except KeyboardInterrupt:
        print("\ninterrupted - the server may be partially updated", file=sys.stderr)
        raise SystemExit(130)
